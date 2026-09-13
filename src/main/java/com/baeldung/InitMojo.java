package com.baeldung;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
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
import java.util.List;
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
     *   minor            — allows minor+patch:               [9.4.2, 9.999999.999999)
     *   patch            — allows patch:                     [9.4.2, 9.4.999999)
     *
     * Usage:  -DallowVersionRanges=minor
     */
    @Parameter(property = "allowVersionRanges", defaultValue = "")
    private String useVersionRanges;

    /** File destination for storing the pre-flatten dependency tree snapshot. */
    @Parameter(property = "depTreeSnapshotFile", defaultValue = "${project.build.directory}/dependency-tree-before.txt")
    private File depTreeSnapshotFile;

    /** Sentinel used as "infinity" for numeric version segments. */
    private static final String INF = String.valueOf(Integer.MAX_VALUE);

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

        List<Dependency> resolvedDeps = new ArrayList<>();

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
            boolean isProvided  = "provided".equals(artifact.getScope());
            boolean isSystem    = "system".equals(artifact.getScope());


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

            resolvedDeps.add(dep);
        }

        // Sort alphabetically for deterministic, diff-friendly output.
        resolvedDeps.sort(Comparator.comparing(Dependency::getGroupId)
                                    .thenComparing(Dependency::getArtifactId));

        lockModel.setDependencies(resolvedDeps);

        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(pomDeps), StandardCharsets.UTF_8)) {
            writer.write(fw, lockModel);
            getLog().info("Initialized pom.deps.xml with " + resolvedDeps.size()
                    + " dependencies (version range mode: "
                    + (rangeMode == VersionRangeMode.NONE ? "pinned" : rangeMode.name().toLowerCase()) + ").");
        } catch (Exception e) {
            throw new MojoExecutionException("Error writing pom.deps.xml", e);
        }
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

        String major = parts[0];
        String minor = parts.length > 1 ? parts[1] : "0";

        switch (mode) {
            case MAJOR:
                return "[" + version + ",)";

            case MINOR:
                return "[" + version + ", " + major + "." + INF + "." + INF + ")";

            case PATCH:
                return "[" + version + ", " + major + "." + minor + "." + INF + ")";

            default:
                return version;
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