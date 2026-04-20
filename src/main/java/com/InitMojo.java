package com.baeldung;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

@Mojo(name = "init")
public class InitMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        File baseDir = project.getBasedir();
        File pomDeps = new File(baseDir, "pom.deps.xml");

        // PROTECTION: Don't overwrite if it already exists
        if (pomDeps.exists()) {
            getLog().warn("pom.deps.xml already exists! Skipping init to protect your original dependencies.");
            return;
        }

        // 1. Create a mini-model using the ORIGINAL dependencies (pre-flattening)
        // Using getOriginalModel() ensures we capture what the user actually wrote
        Model lockModel = new Model();
        lockModel.setModelVersion("4.0.0");
        lockModel.setGroupId(project.getGroupId());
        lockModel.setArtifactId(project.getArtifactId() + "-dependencies");
        lockModel.setVersion(project.getVersion());
        lockModel.setDependencies(project.getOriginalModel().getDependencies());

        // 2. Write the handle to disk
        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (FileWriter fw = new FileWriter(pomDeps, StandardCharsets.UTF_8)) {
            writer.write(fw, lockModel);
            getLog().info("Initialized pom.deps.xml with original dependencies.");
        } catch (Exception e) {
            throw new MojoExecutionException("Error writing pom.deps.xml", e);
        }
    }
}