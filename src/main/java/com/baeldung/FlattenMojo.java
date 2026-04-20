package com.baeldung;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

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
        // Create the building request from the current session
        org.apache.maven.project.ProjectBuildingRequest buildingRequest = 
            new org.apache.maven.project.DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        
        // Link the specific project to the request
        buildingRequest.setProject(project);

        // Build the full resolved dependency graph using the request
        // The 'null' filter means we collect everything
        DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
        
        List<Dependency> flattenedDeps = new ArrayList<>();
        collectDependencies(rootNode, flattenedDeps);

        writeToMainPom(flattenedDeps);

    } catch (Exception e) {
        throw new MojoExecutionException("Failed to flatten dependencies", e);
    }
}

    private void collectDependencies(DependencyNode node, List<Dependency> list) {
        // Skip the root node (the project itself)
        if (node.getParent() != null) {
            Dependency dep = new Dependency();
            dep.setGroupId(node.getArtifact().getGroupId());
            dep.setArtifactId(node.getArtifact().getArtifactId());
            dep.setVersion(node.getArtifact().getVersion());
            dep.setScope(node.getArtifact().getScope());
            list.add(dep);
        }
        for (DependencyNode child : node.getChildren()) {
            collectDependencies(child, list);
        }
    }

    private void writeToMainPom(List<Dependency> deps) throws Exception {
        Model model = project.getOriginalModel();
        model.setDependencies(deps);
        
        File pomFile = project.getFile();
        try (FileWriter writer = new FileWriter(pomFile)) {
            new MavenXpp3Writer().write(writer, model);
            getLog().info("Main POM flattened with resolved versions.");
        }
    }
}