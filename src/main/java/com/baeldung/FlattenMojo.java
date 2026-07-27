package com.baeldung;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

@Mojo(name = "flatten")
public class FlattenMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;

    public void execute() throws MojoExecutionException {
        try {
            File baseDir = project.getBasedir();
            File pomDepsFile = new File(baseDir, "pom.deps.xml");

            // 1. Build the project reference we'll hand to the graph builder.
            //    If pom.deps.xml exists we clone the live project, swap in the
            //    range-based dependency list, and clear any already-resolved
            //    artifact caches so the graph builder performs a fresh resolution
            //    rather than picking up a stale pre-resolved set from an earlier
            //    lifecycle phase.  We deliberately never mutate the original
            //    `project` object, which avoids polluting other mojos that run
            //    after us in the same build.
            MavenProject resolutionProject = (MavenProject) project.clone();

            if (pomDepsFile.exists()) {
                getLog().info("Found pom.deps.xml — reading configured version ranges...");
                MavenXpp3Reader depsReader = new MavenXpp3Reader();
                try (FileReader fr = new FileReader(pomDepsFile)) {
                    Model depsModel = depsReader.read(fr);
                    resolutionProject.setDependencies(depsModel.getDependencies());
                }
            } else {
                getLog().warn("No pom.deps.xml found — falling back to default project dependency resolution.");
            }

            // Discard any artifact sets that were resolved against the *original*
            // pom.xml so the graph builder is forced to re-resolve from scratch
            // using whatever dependency list we just set above.
            resolutionProject.setArtifacts(null);
            resolutionProject.setDependencyArtifacts(null);

            // 2. Prepare the request context against the cloned project.
            ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(resolutionProject);
            buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

            // 3. Build dependency graph.
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

            // Keyed by groupId:artifactId:type[:classifier]; includes depth so
            // that shallower (more direct) nodes always win on scope.
            Map<String, DepEntry> allResolved = new LinkedHashMap<>();
            collectRecursive(rootNode, allResolved, 0);

            List<Dependency> flattenedDeps = new ArrayList<>();
            for (DepEntry entry : allResolved.values()) {
                flattenedDeps.add(entry.dependency);
            }

            // 4. Read the existing POM to preserve all other metadata.
            File pomFile = project.getFile();
            Model targetModel;
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            try (FileReader fr = new FileReader(pomFile)) {
                targetModel = pomReader.read(fr);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to read existing POM file: " + e.getMessage(), e);
            }

            if ("pom".equalsIgnoreCase(project.getPackaging())) {
                // Aggregator / parent POM — leave it untouched.
                getLog().info("Detected aggregator/parent POM — preserving project layout definitions.");
            } else {
                getLog().info("Flattening leaf module dependencies for: " + project.getArtifactId());

                flattenedDeps.sort(Comparator.comparing(Dependency::getGroupId)
                                             .thenComparing(Dependency::getArtifactId));

                targetModel.setDependencies(flattenedDeps);
                targetModel.setDependencyManagement(null);
            }

            // 5. Write the updated model back to pom.xml.
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(pomFile), StandardCharsets.UTF_8)) {
                new MavenXpp3Writer().write(writer, targetModel);
                getLog().info("POM updated successfully: " + project.getArtifactId());
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to write updated POM file: " + e.getMessage(), e);
            }

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error during flattening process: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Graph traversal
    // -------------------------------------------------------------------------

    /** Carrier that pairs a resolved Dependency with the graph depth it was
     *  first seen at, so shallower occurrences always win on scope. */
    private static class DepEntry {
        Dependency dependency;
        int depth;

        DepEntry(Dependency dependency, int depth) {
            this.dependency = dependency;
            this.depth = depth;
        }
    }

    /**
     * Builds a stable dedup map from the dependency graph via preorder DFS.
     *
     * Dedup key: groupId:artifactId:type[:classifier]
     *   — type is included so e.g. a jar and a test-jar of the same GA are
     *     treated as distinct dependencies, not merged.
     *   — classifier is included for the same reason.
     *   — scope is intentionally excluded from the key; see merge logic below.
     *
     * Merge strategy when the same key appears more than once (as is common
     * for transitive deps pulled in from multiple paths):
     *   • Version: keep whichever is higher (matches Maven's nearest-wins after
     *     we've already resolved ranges, so in practice they should be equal,
     *     but we keep the higher one defensively).
     *   • Scope: keep the occurrence that is closest to the root (lowest depth),
     *     because that's the declaration that Maven itself would honour.  This
     *     prevents a transitive test-scope pull from overriding a direct
     *     compile-scope declaration just because DFS happened to visit the
     *     test subtree first.
     */
    private void collectRecursive(DependencyNode node,
                                  Map<String, DepEntry> seen,
                                  int depth) {
        if (node.getParent() != null) {   // skip the synthetic root node
            org.apache.maven.artifact.Artifact artifact = node.getArtifact();

            if (artifact.getScope() != null) {
                String type       = artifact.getType() != null ? artifact.getType() : "jar";
                String classifier = artifact.hasClassifier() ? artifact.getClassifier() : "";
                String key        = artifact.getGroupId() + ":"
                                  + artifact.getArtifactId() + ":"
                                  + type
                                  + (classifier.isEmpty() ? "" : ":" + classifier);

                String incomingVersion = artifact.getBaseVersion();

                if (seen.containsKey(key)) {
                    DepEntry existing = seen.get(key);

                    // Always prefer the higher version.
                    if (compareVersions(incomingVersion, existing.dependency.getVersion()) > 0) {
                        existing.dependency.setVersion(incomingVersion);
                    }

                    // Prefer the scope of the shallower (more direct) occurrence.
                    if (depth < existing.depth) {
                        existing.dependency.setScope(artifact.getScope());
                        existing.depth = depth;
                    }
                } else {
                    Dependency dep = new Dependency();
                    dep.setGroupId(artifact.getGroupId());
                    dep.setArtifactId(artifact.getArtifactId());
                    dep.setVersion(incomingVersion);
                    dep.setScope(artifact.getScope());
                    if (!classifier.isEmpty()) {
                        dep.setClassifier(classifier);
                    }
                    if (!"jar".equals(type)) {
                        dep.setType(type);
                    }
                    seen.put(key, new DepEntry(dep, depth));
                }
            }
        }

        for (DependencyNode child : node.getChildren()) {
            collectRecursive(child, seen, depth + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Version comparison
    // -------------------------------------------------------------------------

    private int compareVersions(String a, String b) {
        String[] partsA = a.split("[.\\-]");
        String[] partsB = b.split("[.\\-]");
        int len = Math.max(partsA.length, partsB.length);

        for (int i = 0; i < len; i++) {
            String segA = i < partsA.length ? partsA[i] : "0";
            String segB = i < partsB.length ? partsB[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(segA), Integer.parseInt(segB));
            } catch (NumberFormatException e) {
                cmp = segA.compareTo(segB);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}