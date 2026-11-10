package com.baeldung;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
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
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A test Mojo that replaces the project's dependencies in-place inside pom.xml
 * with version-ranged or pinned bounds derived from the baseline build artifacts,
 * while strictly preserving the original dependency declaration order.
 */
@Mojo(name = "range-test", requiresDependencyResolution = ResolutionScope.TEST)
public class RangeTestMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Parameter(property = "allowVersionRanges", defaultValue = "")
    private String useVersionRanges;

    @Parameter(property = "pruneDependencyManagement", defaultValue = "false")
    private boolean pruneDependencyManagement;

    @Override
    public void execute() throws MojoExecutionException {
        if ("pom".equalsIgnoreCase(project.getPackaging())) {
            getLog().info("[RANGE-TEST] Skipping aggregator/parent module: " + project.getArtifactId());
            return;
        }

        VersionRangeMode rangeMode = VersionRangeMode.parse(useVersionRanges);
        File pomFile = project.getFile();

        if (pomFile == null || !pomFile.exists()) {
            throw new MojoExecutionException("Could not find target pom.xml file for project: " + project.getArtifactId());
        }

        // 1. Capture baseline snapshot before modifying anything
        writeDependencyTreeSnapshot("dependency-tree-before-ranges.txt");

        // 2. Compute effective exclusions and map resolved artifacts
        Map<String, List<Exclusion>> effectiveExclusions = computeEffectiveExclusions();
        
        @SuppressWarnings("unchecked")
        Set<Artifact> artifacts = (Set<Artifact>) project.getArtifacts();
        Map<String, Artifact> resolvedArtifactMap = new HashMap<>();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if (artifact.getScope() != null) {
                    resolvedArtifactMap.put(ResolutionSupport.key(artifact), artifact);
                }
            }
        }

        // 3. Build ranged dependencies preserving original declaration order
        List<Dependency> rangedDeps = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();
        int withExclusions = 0;

        @SuppressWarnings("unchecked")
        List<Object> originalDeclared = project.getDependencies();

        if (originalDeclared != null) {
            for (Object obj : originalDeclared) {
                if (!(obj instanceof Dependency)) {
                    continue;
                }
                Dependency origDep = (Dependency) obj;
                String type = origDep.getType() != null ? origDep.getType() : "jar";
                String classifier = origDep.getClassifier() != null ? origDep.getClassifier() : "";
                String key = origDep.getGroupId() + ":" + origDep.getArtifactId() + ":" + type
                        + (classifier.isEmpty() ? "" : ":" + classifier);

                Artifact artifact = resolvedArtifactMap.get(key);
                if (artifact != null) {
                    processedKeys.add(key);
                    Dependency dep = buildDependency(artifact, origDep, rangeMode, effectiveExclusions);
                    if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
                        withExclusions++;
                    }
                    rangedDeps.add(dep);
                } else {
                    // If not found in resolved set, preserve original declaration as-is
                    rangedDeps.add(origDep.clone());
                }
            }
        }

        // Append any remaining transitive dependencies that weren't in direct declarations
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if (artifact.getScope() == null) {
                    continue;
                }
                String key = ResolutionSupport.key(artifact);
                if (!processedKeys.contains(key)) {
                    processedKeys.add(key);
                    Dependency dep = buildDependency(artifact, null, rangeMode, effectiveExclusions);
                    if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
                        withExclusions++;
                    }
                    rangedDeps.add(dep);
                }
            }
        }

        // 4. Read existing model from pom.xml
        Model targetModel;
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (FileReader fr = new FileReader(pomFile)) {
            targetModel = pomReader.read(fr);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read existing pom.xml: " + e.getMessage(), e);
        }

        // 5. Update dependencies in-place
        targetModel.setDependencies(rangedDeps);
        int managedEntries = updateDependencyManagement(targetModel, rangedDeps);

        // 6. Write updated pom.xml back in-place
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(pomFile), StandardCharsets.UTF_8)) {
            new MavenXpp3Writer().write(writer, targetModel);
            getLog().info("[RANGE-TEST] Updated " + pomFile.getAbsolutePath() + " in-place with original order preserved (" 
                    + rangedDeps.size() + " dependencies, " + withExclusions + " carrying exclusions, "
                    + managedEntries + " managed entries, mode: " + rangeMode.name().toLowerCase() + ").");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to write updated pom.xml: " + e.getMessage(), e);
        }
    }

    private Dependency buildDependency(Artifact artifact, Dependency originalDeclared, VersionRangeMode rangeMode, 
                                       Map<String, List<Exclusion>> effectiveExclusions) {
        Dependency dep = new Dependency();
        dep.setGroupId(artifact.getGroupId());
        dep.setArtifactId(artifact.getArtifactId());

        String concreteVersion = artifact.getBaseVersion();
        boolean isInternal = artifact.getGroupId().equals(project.getGroupId());
        boolean isProvided = "provided".equals(artifact.getScope());
        boolean isSystem = "system".equals(artifact.getScope());

        boolean applyRange = !isInternal && !isProvided && !isSystem;
        dep.setVersion(applyRange ? toVersionString(concreteVersion, rangeMode) : concreteVersion);
        dep.setScope(artifact.getScope());

        if (artifact.hasClassifier()) {
            dep.setClassifier(artifact.getClassifier());
        }

        if (artifact.getType() != null && !artifact.getType().equals("jar")) {
            dep.setType(artifact.getType());
        }

        if (artifact.isOptional()) {
            dep.setOptional(true);
        }

        if (isSystem) {
            if (originalDeclared != null && originalDeclared.getSystemPath() != null) {
                dep.setSystemPath(originalDeclared.getSystemPath());
            } else if (artifact.getFile() != null) {
                dep.setSystemPath(artifact.getFile().getAbsolutePath());
            }
        }

        String key = ResolutionSupport.key(artifact);
        List<Exclusion> exclusions = effectiveExclusions.get(key);
        if (exclusions != null && !exclusions.isEmpty()) {
            for (Exclusion exclusion : exclusions) {
                dep.addExclusion(exclusion.clone());
            }
        }

        return dep;
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
            throw new MojoExecutionException("Could not compute effective exclusions: " + e.getMessage(), e);
        }
    }

    private int updateDependencyManagement(Model targetModel, List<Dependency> resolvedDeps) {
        DependencyManagement managed = targetModel.getDependencyManagement();
        if (managed == null || managed.getDependencies() == null || managed.getDependencies().isEmpty()) {
            return 0;
        }

        Set<String> resolvedGa = new HashSet<>();
        for (Dependency d : resolvedDeps) {
            resolvedGa.add(d.getGroupId() + ":" + d.getArtifactId());
        }

        if (pruneDependencyManagement) {
            List<Dependency> pruned = new ArrayList<>();
            for (Dependency d : managed.getDependencies()) {
                if (resolvedGa.contains(d.getGroupId() + ":" + d.getArtifactId())) {
                    pruned.add(d);
                }
            }
            managed.setDependencies(pruned);
        }
        return managed.getDependencies().size();
    }

    private void writeDependencyTreeSnapshot(String fileName) throws MojoExecutionException {
        try {
            File buildDir = new File(project.getBuild().getDirectory());
            File outputFile = new File(buildDir, fileName);

            if (!buildDir.exists()) {
                buildDir.mkdirs();
            }

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
                writer.write(project.getGroupId() + ":" + project.getArtifactId() + ":"
                          + project.getPackaging() + ":" + project.getVersion() + System.lineSeparator());

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
            throw new MojoExecutionException("Failed to write dependency snapshot", e);
        }
    }

    private String toVersionString(String version, VersionRangeMode mode) {
        if (mode == VersionRangeMode.NONE) {
            return version;
        }

        if (version == null || version.contains("SNAPSHOT") || version.contains("RELEASE")) {
            return version;
        }

        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return version;
        }

        int majorNum;
        int minorNum;
        try {
            majorNum = Integer.parseInt(parts[0]);
            minorNum = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return version;
        }

        switch (mode) {
            case MAJOR: return "[" + version + ",)";
            case MINOR: return "[" + version + "," + (majorNum + 1) + ".0.0-alpha)";
            case PATCH: return "[" + version + "," + majorNum + "." + (minorNum + 1) + ".0-alpha)";
            default:    return version;
        }
    }

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
                        + "Accepted values: major, minor, patch.");
            }
        }
    }
}