package com.baeldung;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.Artifact;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Mojo(name = "init", requiresDependencyResolution = ResolutionScope.COMPILE)
public class InitMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        File pomDeps = new File(project.getBasedir(), "pom.deps.xml");

        if (pomDeps.exists()) {
            getLog().warn("pom.deps.xml already exists! Skipping init.");
            return;
        }

        Model lockModel = new Model();
        lockModel.setModelVersion("4.0.0");
        lockModel.setGroupId(project.getGroupId());
        lockModel.setArtifactId(project.getArtifactId() + "-dependencies");
        lockModel.setVersion(project.getVersion());

        // 1. Build a map of groupId:artifactId -> resolved version
        Map<String, String> resolvedVersions = new HashMap<>();
        for (Object obj : project.getArtifacts()) {
            if (!(obj instanceof Artifact)) continue;
            Artifact artifact = (Artifact) obj;
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getType()
                    + (artifact.hasClassifier() ? ":" + artifact.getClassifier() : "");
            resolvedVersions.put(key, artifact.getVersion());
        }

        // 2. Walk the original (pre-resolution) dependencies and apply semver ranges
        List<Dependency> adjustedDeps = new ArrayList<>();
        List<?> originalDeps = project.getOriginalModel().getDependencies();

        for (Object obj : originalDeps) {
            if (!(obj instanceof Dependency)) continue;

            Dependency original = (Dependency) obj;
            Dependency clone = original.clone();

            String resolvedVersion = resolvedVersions.get(clone.getManagementKey());

            if (resolvedVersion != null) {
                // Always replace whatever was there (exact, range, or property)
                // with a proper semver range: [resolvedVersion, nextMajor)
                clone.setVersion(toSemverRange(resolvedVersion));
            } else {
                getLog().warn("No resolved version found for " + clone.getManagementKey()
                        + " — keeping original: " + clone.getVersion());
            }

            adjustedDeps.add(clone);
        }
        lockModel.setDependencies(adjustedDeps);

        // 3. Preserve dependency management block as-is
        if (project.getOriginalModel().getDependencyManagement() != null) {
            lockModel.setDependencyManagement(project.getOriginalModel().getDependencyManagement());
        }

        // 4. Write to disk
        try (FileWriter fw = new FileWriter(pomDeps, StandardCharsets.UTF_8)) {
            new MavenXpp3Writer().write(fw, lockModel);
            getLog().info("Initialized pom.deps.xml with semver ranges.");
        } catch (Exception e) {
            throw new MojoExecutionException("Error writing pom.deps.xml", e);
        }
    }

    /**
     * Converts a resolved version like "2.3.1" into a semver range "[2.3.1, 3.0.0)"
     * that accepts new minor and patch versions within the same major.
     *
     * If the version string is malformed (no dots), falls back to an exact lock "[x, )".
     */
    private String toSemverRange(String resolvedVersion) {
        String[] parts = resolvedVersion.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int nextMajor = major + 1;
            return "[" + resolvedVersion + ", " + nextMajor + ".0.0)";
        } catch (NumberFormatException e) {
            getLog().warn("Could not parse major version from '" + resolvedVersion + "', using exact lock.");
            return "[" + resolvedVersion + "]";
        }
    }
}