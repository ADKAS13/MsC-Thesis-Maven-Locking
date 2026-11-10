package com.baeldung;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// requiresDependencyResolution = TEST ensures Maven resolves all scopes
// (compile, runtime, provided, test) before this Mojo runs, so
// project.getArtifacts() returns the complete, effective artifact set.
@Mojo(name = "init", requiresDependencyResolution = ResolutionScope.TEST)
public class InitMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * Controls whether versions are written as Maven version ranges instead of
     * pinned concrete versions. The lower bound is always the resolved version.
     * Accepted values:
     *
     *   (unset / empty)  — concrete pinned version, e.g. 9.4.2          (default)
     *   major            — allows major, allows minor+patch:  [9.4.2,)
     *   minor            — allows minor+patch:               [9.4.2, 10.0.0-alpha)
     *   patch            — allows patch:                     [9.4.2, 9.5.0-alpha)
     *
     * Usage:  -DallowVersionRanges=minor
     */
    @Parameter(property = "allowVersionRanges", defaultValue = "")
    private String useVersionRanges;

    /**
     * When true, only the dependencyManagement entries for artifacts that are part of the
     * resolved set are copied into pom.deps.xml. The default (false) copies the whole
     * effective dependencyManagement, which is what lets range resolution in the flatten
     * step still honour management for artifacts that a newer version brings in.
     */
    @Parameter(property = "pruneDependencyManagement", defaultValue = "false")
    private boolean pruneDependencyManagement;

    /** File destination for storing the pre-flatten dependency tree snapshot. */
    @Parameter(property = "depTreeSnapshotFile", defaultValue = "${project.build.directory}/dependency-tree-before.txt")
    private File depTreeSnapshotFile;

    @Override
    public void execute() throws MojoExecutionException {
        // Validate the flag value early so the user gets a clear error message.
        VersionRangeMode rangeMode = VersionRangeMode.parse(useVersionRanges);

        File baseDir = project.getBasedir();
        File pomDeps = new File(baseDir, "pom.deps.xml");

        // Capture and persist the dependency graph before potential modifications.
        writeDependencyTreeSnapshot();

        // Protection: don't overwrite an existing backup.
        if (pomDeps.exists()) {
            getLog().warn("pom.deps.xml already exists! Skipping init to protect your original backup.");
            return;
        }

        Model lockModel = new Model();
        lockModel.setModelVersion("4.0.0");
        lockModel.setGroupId(project.getGroupId());
        lockModel.setArtifactId(project.getArtifactId() + "-dependencies");
        lockModel.setVersion(project.getVersion());

        // Exclusions live on dependency edges, so they have to be read from the graph and
        // copied onto each flattened dependency, otherwise whatever they suppressed returns.
        Map<String, List<Exclusion>> effectiveExclusions = computeEffectiveExclusions();
        Map<String, Dependency> declared = declaredDependencies();

        List<Dependency> resolvedDeps = new ArrayList<>();
        int withExclusions = 0;

        // project.getArtifacts() returns the *effective*, fully-resolved artifact
        // set that Maven computed from the dependency graph — properties expanded,
        // parent POM versions inherited, version ranges pinned, conflicts mediated.
        @SuppressWarnings("unchecked")
        Set<Artifact> artifacts = (Set<Artifact>) project.getArtifacts();
        for (Artifact artifact : artifacts) {
            // Skip artifacts without a scope; they are not real dependencies
            // (e.g. the project's own artifact can appear scopeless).
            if (artifact.getScope() == null) {
                continue;
            }

            Dependency dep = new Dependency();
            dep.setGroupId(artifact.getGroupId());
            dep.setArtifactId(artifact.getArtifactId());

            // getBaseVersion() normalises SNAPSHOT timestamps back to "X.Y-SNAPSHOT"
            // so the lock file stays stable across repeated builds.
            String concreteVersion = artifact.getBaseVersion();
            boolean isInternal = artifact.getGroupId().equals(project.getGroupId());
            boolean isProvided = "provided".equals(artifact.getScope());
            boolean isSystem   = "system".equals(artifact.getScope());

            boolean applyRange = !isInternal && !isProvided && !isSystem;
            dep.setVersion(applyRange ? toVersionString(concreteVersion, rangeMode) : concreteVersion);
            dep.setScope(artifact.getScope());

            if (artifact.hasClassifier()) {
                dep.setClassifier(artifact.getClassifier());
            }

            // Only write <type> when it's non-default; jar is implicit in Maven.
            if (artifact.getType() != null && !artifact.getType().equals("jar")) {
                dep.setType(artifact.getType());
            }

            String key = ResolutionSupport.key(artifact);

            if (artifact.isOptional()) {
                dep.setOptional(true);
            }

            // A system-scoped dependency cannot be resolved without its systemPath.
            if (isSystem) {
                Dependency original = declared.get(key);
                if (original != null && original.getSystemPath() != null) {
                    dep.setSystemPath(original.getSystemPath());
                } else if (artifact.getFile() != null) {
                    dep.setSystemPath(artifact.getFile().getAbsolutePath());
                }
            }

            List<Exclusion> exclusions = effectiveExclusions.get(key);
            if (exclusions != null && !exclusions.isEmpty()) {
                for (Exclusion exclusion : exclusions) {
                    dep.addExclusion(exclusion.clone());
                }
                withExclusions++;
            }

            resolvedDeps.add(dep);
        }

        // Sort alphabetically for deterministic, diff-friendly output.
        resolvedDeps.sort(Comparator.comparing(Dependency::getGroupId)
                                    .thenComparing(Dependency::getArtifactId));

        lockModel.setDependencies(resolvedDeps);

        // The effective dependencyManagement (inherited and imported BOMs already merged in).
        // The flatten step resolves against this instead of whatever the project happens to
        // inherit, so pom.deps.xml carries every input that resolution depends on.
        int managedEntries = copyDependencyManagement(lockModel, resolvedDeps);

        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(pomDeps), StandardCharsets.UTF_8)) {
            writer.write(fw, lockModel);
            getLog().info("Initialized pom.deps.xml with " + resolvedDeps.size()
                    + " dependencies (" + withExclusions + " carrying exclusions, "
                    + managedEntries + " dependencyManagement entries, version range mode: "
                    + (rangeMode == VersionRangeMode.NONE ? "pinned" : rangeMode.name().toLowerCase()) + ").");
        } catch (Exception e) {
            throw new MojoExecutionException("Error writing pom.deps.xml", e);
        }
    }

    private Map<String, List<Exclusion>> computeEffectiveExclusions() throws MojoExecutionException {
        try {
            ProjectBuildingRequest request =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            request.setProject(project);
            request.setRemoteRepositories(project.getRemoteArtifactRepositories());
            DependencyNode root = dependencyGraphBuilder.buildDependencyGraph(request, null);
            return ResolutionSupport.effectiveExclusions(root);
        } catch (DependencyGraphBuilderException e) {
            // Failing loudly is deliberate: without the exclusions the flatten step would
            // silently resurrect everything they used to suppress.
            throw new MojoExecutionException("Could not compute effective exclusions: " + e.getMessage(), e);
        }
    }

    /** The project's own declared dependencies, keyed like {@link ResolutionSupport#key}. */
    private Map<String, Dependency> declaredDependencies() {
        Map<String, Dependency> map = new HashMap<>();
        if (project.getDependencies() != null) {
            // Older maven-core versions return a raw List, hence Object + cast.
            for (Object obj : project.getDependencies()) {
                if (!(obj instanceof Dependency)) {
                    continue;
                }
                Dependency d = (Dependency) obj;
                String type = d.getType() != null ? d.getType() : "jar";
                String classifier = d.getClassifier() != null ? d.getClassifier() : "";
                map.put(d.getGroupId() + ":" + d.getArtifactId() + ":" + type
                        + (classifier.isEmpty() ? "" : ":" + classifier), d);
            }
        }
        return map;
    }

    private int copyDependencyManagement(Model lockModel, List<Dependency> resolvedDeps) {
        DependencyManagement managed = project.getDependencyManagement();
        if (managed == null || managed.getDependencies() == null || managed.getDependencies().isEmpty()) {
            return 0;
        }

        Set<String> resolvedGa = new HashSet<>();
        for (Dependency d : resolvedDeps) {
            resolvedGa.add(d.getGroupId() + ":" + d.getArtifactId());
        }

        DependencyManagement copy = new DependencyManagement();
        for (Dependency d : managed.getDependencies()) {
            if (pruneDependencyManagement
                    && !resolvedGa.contains(d.getGroupId() + ":" + d.getArtifactId())) {
                continue;
            }
            copy.addDependency(d.clone());
        }

        if (copy.getDependencies().isEmpty()) {
            return 0;
        }
        lockModel.setDependencyManagement(copy);
        return copy.getDependencies().size();
    }

    private void writeDependencyTreeSnapshot() throws MojoExecutionException {
        try {
            // Output directly into the project's build directory (or buildcache target)
            File buildDir = new File(project.getBuild().getDirectory());
            File outputFile = new File(buildDir, "dependency-tree-before.txt");

            if (!buildDir.exists()) {
                buildDir.mkdirs();
            }

            getLog().info("[LOCK-PLUGIN] Writing dependency tree snapshot to: " + outputFile.getAbsolutePath());

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
                // Write root node
                writer.write(project.getGroupId() + ":" + project.getArtifactId() + ":"
                          + project.getPackaging() + ":" + project.getVersion() + System.lineSeparator());

                // Write resolved dependency artifacts
                @SuppressWarnings("unchecked")
                Set<Artifact> artifacts = (Set<Artifact>) project.getArtifacts();
                if (artifacts != null) {
                    for (Artifact artifact : artifacts) {
                        writer.write("  +- " + artifact.getGroupId() + ":"
                                            + artifact.getArtifactId() + ":"
                                            + artifact.getType() + ":"
                                            + artifact.getVersion() + ":"
                                            + artifact.getScope() + System.lineSeparator());
                    }
                }
            }
        } catch (Exception e) {
            getLog().error("[LOCK-PLUGIN] Failed to write dependency snapshot: " + e.getMessage(), e);
            throw new MojoExecutionException("Failed to capture dependency snapshot", e);
        }
    }

    private void writeNode(Writer writer, DependencyNode node, String indent, boolean isRoot) throws IOException {
        String line = isRoot
                ? node.getArtifact().toString()
                : indent + node.getArtifact().toString();
        writer.write(line);
        writer.write(System.lineSeparator());

        for (DependencyNode child : node.getChildren()) {
            writeNode(writer, child, indent + "  ", false);
        }
    }

    /**
     * Converts a concrete version string to the appropriate representation
     * based on the requested range mode.
     *
     * The upper bounds are exclusive and use an "-alpha" qualifier: in Maven's version
     * ordering "2.0.0-alpha" sorts below every 2.0.0 release and pre-release, so the next
     * major/minor is fully excluded. (A qualifier such as "-!" sorts ABOVE the release, which
     * would let the next version through.)
     */
    private String toVersionString(String version, VersionRangeMode mode) {
        if (mode == VersionRangeMode.NONE) {
            return version;
        }

        if (version == null || version.contains("SNAPSHOT") || version.contains("RELEASE")) {
            getLog().warn("Cannot compute version range for non-release version '" + version
                    + "' — falling back to pinned.");
            return version;
        }

        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            getLog().warn("Version '" + version + "' has fewer than 2 segments — falling back to pinned.");
            return version;
        }

        int majorNum;
        int minorNum;
        try {
            majorNum = Integer.parseInt(parts[0]);
            minorNum = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            getLog().warn("Non-numeric version segment in '" + version + "' — falling back to pinned.");
            return version;
        }

        switch (mode) {
            case MAJOR: return "[" + version + ",)";
            case MINOR: return "[" + version + "," + (majorNum + 1) + ".0.0-alpha)";
            case PATCH: return "[" + version + "," + majorNum + "." + (minorNum + 1) + ".0-alpha)";
            default:    return version;
        }
    }

    // -------------------------------------------------------------------------

    enum VersionRangeMode {
        NONE, MAJOR, MINOR, PATCH;

        static VersionRangeMode parse(String value) throws MojoExecutionException {
            if (value == null || value.trim().isEmpty()) {
                return NONE;
            }
            switch (value.trim().toLowerCase()) {
                case "minor": return MINOR;
                case "patch": return PATCH;
                case "major": return MAJOR;
                default:
                    throw new MojoExecutionException(
                        "Invalid value for -DallowVersionRanges: '" + value + "'. "
                        + "Accepted values: major, minor, patch, (or omit for pinned versions).");
            }
        }
    }
}