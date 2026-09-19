package com.baeldung;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "flatten")
public class FlattenMojo extends AbstractMojo {

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("^\\$\\{([^}]+)\\}$");

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;

    public void execute() throws MojoExecutionException {
        try {
            File baseDir     = project.getBasedir();
            File pomDepsFile = new File(baseDir, "pom.deps.xml");

            // Skip parent/aggregator modules entirely
            if ("pom".equalsIgnoreCase(project.getPackaging())) {
                getLog().info("[LOCK-PLUGIN] Skipping aggregator/parent module: " + project.getArtifactId());
                return;
            }

            // Map declared exclusions from the current project model
            Map<String, List<Exclusion>> declaredExclusions = new LinkedHashMap<>();
            if (project.getDependencies() != null) {
                for (Object obj : project.getDependencies()) {
                    if (obj instanceof Dependency) {
                        Dependency d = (Dependency) obj;
                        if (d.getExclusions() != null && !d.getExclusions().isEmpty()) {
                            String ga = d.getGroupId() + ":" + d.getArtifactId();
                            declaredExclusions.put(ga, d.getExclusions());
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 1. Resolve baseline original dependency graph
            // ------------------------------------------------------------------
            ProjectBuildingRequest baselineRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            baselineRequest.setProject(project);
            baselineRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

            DependencyNode baselineRoot = dependencyGraphBuilder.buildDependencyGraph(baselineRequest, null);
            Map<String, DepEntry> baselineResolved = new LinkedHashMap<>();
            collectRecursive(baselineRoot, baselineResolved, 0, declaredExclusions);

            List<Dependency> beforeDeps = new ArrayList<>();
            for (DepEntry entry : baselineResolved.values()) {
                beforeDeps.add(entry.dependency);
            }

            // ------------------------------------------------------------------
            // 2. Clone project and (optionally) resolve ranges
            // ------------------------------------------------------------------
            MavenProject resolutionProject = (MavenProject) project.clone();

            if (pomDepsFile.exists()) {
                getLog().info("Found pom.deps.xml — reading configured version ranges...");
                MavenXpp3Reader depsReader = new MavenXpp3Reader();
                try (FileReader fr = new FileReader(pomDepsFile)) {
                    Model depsModel = depsReader.read(fr);
                    resolutionProject.setDependencies(depsModel.getDependencies());
                }
            } else {
                getLog().warn("No pom.deps.xml found — using default project dependency resolution.");
            }

            resolutionProject.setArtifacts(null);
            resolutionProject.setDependencyArtifacts(null);

            // ------------------------------------------------------------------
            // 3. Resolve target dependency graph
            // ------------------------------------------------------------------
            ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(resolutionProject);
            buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

            DependencyNode rootNode;
            try {
                rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
            } catch (Exception e) {
                getLog().warn("[LOCK-PLUGIN] Range resolution failed for " + project.getArtifactId()
                        + " — falling back to pinned lower bounds: " + e.getMessage());

                if (pomDepsFile.exists()) {
                    MavenXpp3Reader fallbackReader = new MavenXpp3Reader();
                    try (FileReader fr = new FileReader(pomDepsFile)) {
                        Model depsModel = fallbackReader.read(fr);
                        for (Dependency dep : depsModel.getDependencies()) {
                            dep.setVersion(extractLowerBound(dep.getVersion()));
                        }
                        resolutionProject.setDependencies(depsModel.getDependencies());
                    }
                    resolutionProject.setArtifacts(null);
                    resolutionProject.setDependencyArtifacts(null);
                    rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
                } else {
                    throw new MojoExecutionException("Resolution failed and no pom.deps.xml to fall back to", e);
                }
            }

            Map<String, DepEntry> allResolved = new LinkedHashMap<>();
            collectRecursive(rootNode, allResolved, 0, declaredExclusions);

            List<Dependency> flattenedDeps = new ArrayList<>();
            for (DepEntry entry : allResolved.values()) {
                flattenedDeps.add(entry.dependency);
            }

            // ------------------------------------------------------------------
            // 4. Read existing POM
            // ------------------------------------------------------------------
            File pomFile = project.getFile();
            Model targetModel;
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            try (FileReader fr = new FileReader(pomFile)) {
                targetModel = pomReader.read(fr);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to read existing POM file: " + e.getMessage(), e);
            }

            // ------------------------------------------------------------------
            // 5. Process properties and dependency versions
            // ------------------------------------------------------------------
            getLog().info("Flattening leaf module dependencies for: " + project.getArtifactId());

            flattenedDeps.sort(Comparator.comparing(Dependency::getGroupId)
                                         .thenComparing(Dependency::getArtifactId));

            writeFlattenedSnapshot(flattenedDeps);
            writeComparisonReport(beforeDeps, flattenedDeps);

            // Map declared versions from original pom.xml to preserve property expressions
            Map<String, String> declaredVersionsMap = new HashMap<>();
            if (targetModel.getDependencies() != null) {
                for (Dependency d : targetModel.getDependencies()) {
                    if (d.getVersion() != null) {
                        declaredVersionsMap.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
                    }
                }
            }

            Properties targetProperties = targetModel.getProperties();
            if (targetProperties == null) {
                targetProperties = new Properties();
            }

            for (Dependency dep : flattenedDeps) {
                String gaKey = dep.getGroupId() + ":" + dep.getArtifactId();
                String originalVersionExpr = declaredVersionsMap.get(gaKey);

                if (originalVersionExpr != null) {
                    Matcher matcher = PROPERTY_PATTERN.matcher(originalVersionExpr.trim());
                    if (matcher.matches()) {
                        String propName = matcher.group(1);
                        // Case A: Property is defined directly in this POM -> update the property value
                        if (targetProperties.containsKey(propName)) {
                            targetProperties.setProperty(propName, dep.getVersion());
                            dep.setVersion(originalVersionExpr);
                        } 
                        // Case B: Property is inherited -> keep expression so it resolves via parent
                        else {
                            dep.setVersion(originalVersionExpr);
                        }
                    }
                }
            }

            targetModel.setProperties(targetProperties);
            targetModel.setDependencies(flattenedDeps);

            // ------------------------------------------------------------------
            // 6. Write updated pom.xml
            // ------------------------------------------------------------------
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(pomFile), StandardCharsets.UTF_8)) {
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

    private String extractLowerBound(String version) {
        if (version == null) return version;
        if (version.startsWith("[") || version.startsWith("(")) {
            String inner = version.replaceAll("[\\[\\]\\(\\)]", "").trim();
            String[] parts = inner.split(",");
            String lower = parts[0].trim();
            return lower.isEmpty() ? version : lower;
        }
        return version;
    }

    // -------------------------------------------------------------------------
    // Snapshot + comparison reporting
    // -------------------------------------------------------------------------

    private void writeFlattenedSnapshot(List<Dependency> flattenedDeps) {
        try {
            File buildDir   = new File(project.getBuild().getDirectory());
            File outputFile = new File(buildDir, "dependency-tree-after.txt");
            if (!buildDir.exists()) buildDir.mkdirs();

            getLog().info("[LOCK-PLUGIN] Writing post-flatten snapshot to: " + outputFile.getAbsolutePath());

            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {

                writer.write(project.getGroupId() + ":" + project.getArtifactId() + ":"
                        + project.getPackaging() + ":" + project.getVersion()
                        + System.lineSeparator());

                for (Dependency dep : flattenedDeps) {
                    writer.write("  +- "
                            + dep.getGroupId() + ":"
                            + dep.getArtifactId() + ":"
                            + (dep.getType() != null ? dep.getType() : "jar") + ":"
                            + dep.getVersion() + ":"
                            + dep.getScope()
                            + System.lineSeparator());
                }
            }
        } catch (Exception e) {
            getLog().warn("[LOCK-PLUGIN] Failed to write post-flatten snapshot: " + e.getMessage());
        }
    }

    private void writeComparisonReport(List<Dependency> beforeDeps, List<Dependency> afterDeps) {
        try {
            File buildDir   = new File(project.getBuild().getDirectory());
            File reportFile = new File(buildDir, "dependency-comparison-report.txt");

            Map<String, String[]> beforeMap = new LinkedHashMap<>();
            for (Dependency dep : beforeDeps) {
                String ga      = dep.getGroupId() + ":" + dep.getArtifactId();
                String version = dep.getVersion();
                String scope   = dep.getScope() != null ? dep.getScope() : "compile";
                beforeMap.put(ga, new String[]{version, scope});
            }

            Map<String, String[]> afterMap = new LinkedHashMap<>();
            for (Dependency dep : afterDeps) {
                String ga      = dep.getGroupId() + ":" + dep.getArtifactId();
                String version = dep.getVersion();
                String scope   = dep.getScope() != null ? dep.getScope() : "compile";
                afterMap.put(ga, new String[]{version, scope});
            }

            int pinned = 0, upgraded = 0, downgraded = 0, added = 0, removed = 0, scopeChanged = 0;
            List<String> lines = new ArrayList<>();

            for (Map.Entry<String, String[]> entry : beforeMap.entrySet()) {
                String ga = entry.getKey();
                String[] before = entry.getValue();
                if (!afterMap.containsKey(ga)) {
                    removed++;
                    lines.add("REMOVED      " + ga + "  " + before[0] + " (" + before[1] + ")");
                } else {
                    String[] after = afterMap.get(ga);
                    int vCmp = compareVersions(after[0], before[0]);
                    boolean scopeDiff = !before[1].equals(after[1]);
                    if (vCmp == 0 && !scopeDiff) {
                        pinned++;
                    } else if (vCmp == 0 && scopeDiff) {
                        scopeChanged++;
                        lines.add("SCOPE_CHANGE " + ga + "  " + before[0]
                                + "  [" + before[1] + " -> " + after[1] + "]");
                    } else if (vCmp > 0) {
                        upgraded++;
                        lines.add("UPGRADED     " + ga + "  " + before[0] + " -> " + after[0]
                                + (scopeDiff ? "  [" + before[1] + " -> " + after[1] + "]" : ""));
                    } else {
                        downgraded++;
                        lines.add("DOWNGRADED   " + ga + "  " + before[0] + " -> " + after[0]
                                + (scopeDiff ? "  [" + before[1] + " -> " + after[1] + "]" : ""));
                    }
                }
            }

            for (String ga : afterMap.keySet()) {
                if (!beforeMap.containsKey(ga)) {
                    String[] after = afterMap.get(ga);
                    added++;
                    lines.add("ADDED        " + ga + "  " + after[0] + " (" + after[1] + ")");
                }
            }

            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(reportFile), StandardCharsets.UTF_8)) {

                writer.write("MODULE=" + project.getArtifactId() + System.lineSeparator());
                writer.write("PINNED=" + pinned + System.lineSeparator());
                writer.write("UPGRADED=" + upgraded + System.lineSeparator());
                writer.write("DOWNGRADED=" + downgraded + System.lineSeparator());
                writer.write("ADDED=" + added + System.lineSeparator());
                writer.write("REMOVED=" + removed + System.lineSeparator());
                writer.write("SCOPE_CHANGED=" + scopeChanged + System.lineSeparator());
                writer.write("---" + System.lineSeparator());
                for (String l : lines) {
                    writer.write(l + System.lineSeparator());
                }
            }

            getLog().info("[LOCK-PLUGIN] Dependency comparison for " + project.getArtifactId() + ":"
                    + " pinned=" + pinned
                    + " upgraded=" + upgraded
                    + " downgraded=" + downgraded
                    + " added=" + added
                    + " removed=" + removed
                    + " scope_changed=" + scopeChanged);

        } catch (Exception e) {
            getLog().warn("[LOCK-PLUGIN] Failed to write comparison report: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Graph traversal
    // -------------------------------------------------------------------------

    private static class DepEntry {
        Dependency dependency;
        int depth;
        DepEntry(Dependency dependency, int depth) {
            this.dependency = dependency;
            this.depth = depth;
        }
    }

    private void collectRecursive(DependencyNode node,
                                  Map<String, DepEntry> seen,
                                  int depth,
                                  Map<String, List<Exclusion>> declaredExclusions) {
        if (node.getParent() != null) {
            org.apache.maven.artifact.Artifact artifact = node.getArtifact();

            if (artifact.getScope() != null) {
                String type       = artifact.getType() != null ? artifact.getType() : "jar";
                String classifier = artifact.hasClassifier() ? artifact.getClassifier() : "";
                String key        = artifact.getGroupId() + ":"
                                  + artifact.getArtifactId() + ":"
                                  + type
                                  + (classifier.isEmpty() ? "" : ":" + classifier);

                String gaKey      = artifact.getGroupId() + ":" + artifact.getArtifactId();
                String incomingVersion = artifact.getBaseVersion();

                // If scope is inherited as test/provided, ensure depth prioritization
                if (seen.containsKey(key)) {
                    DepEntry existing = seen.get(key);
                    if (depth < existing.depth) {
                        existing.dependency.setScope(artifact.getScope());
                        existing.dependency.setVersion(incomingVersion);
                        existing.depth = depth;
                    }
                } else {
                    Dependency dep = new Dependency();
                    dep.setGroupId(artifact.getGroupId());
                    dep.setArtifactId(artifact.getArtifactId());
                    dep.setVersion(incomingVersion);
                    dep.setScope(artifact.getScope());
                    dep.setOptional(artifact.isOptional());
                    
                    if (!classifier.isEmpty()) dep.setClassifier(classifier);
                    if (!"jar".equals(type))   dep.setType(type);

                    if (declaredExclusions.containsKey(gaKey)) {
                        for (Exclusion ex : declaredExclusions.get(gaKey)) {
                            dep.addExclusion(ex);
                        }
                    }

                    seen.put(key, new DepEntry(dep, depth));
                }
            }
        }

        for (DependencyNode child : node.getChildren()) {
            collectRecursive(child, seen, depth + 1, declaredExclusions);
        }
    }

    // -------------------------------------------------------------------------
    // Version comparison
    // -------------------------------------------------------------------------

    private int compareVersions(String a, String b) {
        if (a == null) return -1;
        if (b == null) return  1;
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