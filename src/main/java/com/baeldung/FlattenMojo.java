package com.baeldung;

import org.apache.maven.model.DependencyManagement;
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
import java.util.Map;
import java.util.LinkedHashMap;

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
			org.apache.maven.project.ProjectBuildingRequest buildingRequest =
				new org.apache.maven.project.DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			buildingRequest.setProject(project);

			DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

			// Collect ALL transitive deps, deduplicated with highest version winning.
			// This is the flattened tree — every transitive dep becomes a direct dep.
			Map<String, Dependency> allResolved = new LinkedHashMap<>();
			collectRecursive(rootNode, allResolved);

			List<Dependency> flattenedDeps = new ArrayList<>(allResolved.values());

			// flattenedDeps serves double duty:
			// - as <dependencies>: declares all transitives as direct deps (flattening)
			// - as <dependencyManagement>: pins versions to satisfy DependencyConvergence
			writeToMainPom(flattenedDeps, flattenedDeps);

		} catch (Exception e) {
			throw new MojoExecutionException("Failed to flatten dependencies", e);
		}
	}

// Remove collectDependencies() wrapper — call collectRecursive directly above
	private void collectDependencies(DependencyNode node, List<Dependency> list) {
		// Use a map to deduplicate: groupId:artifactId -> Dependency
		// keeping the highest version when conflicts arise
		Map<String, Dependency> seen = new LinkedHashMap<>();
		collectRecursive(node, seen);
		list.addAll(seen.values());
	}

	private void collectRecursive(DependencyNode node, Map<String, Dependency> seen) {
		if (node.getParent() != null) {
			String key = node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId();
			String incomingVersion = node.getArtifact().getVersion();

			if (seen.containsKey(key)) {
				// Keep the higher of the two versions
				String existingVersion = seen.get(key).getVersion();
				if (compareVersions(incomingVersion, existingVersion) > 0) {
					getLog().info("Upgrading " + key + " from " + existingVersion + " to " + incomingVersion);
					Dependency dep = seen.get(key);
					dep.setVersion(incomingVersion);
				} else {
					getLog().info("Keeping " + key + " at " + existingVersion + " (ignoring " + incomingVersion + ")");
				}
			} else {
				Dependency dep = new Dependency();
				dep.setGroupId(node.getArtifact().getGroupId());
				dep.setArtifactId(node.getArtifact().getArtifactId());
				dep.setVersion(incomingVersion);
				dep.setScope(node.getArtifact().getScope());
				if (node.getArtifact().hasClassifier()) {
					dep.setClassifier(node.getArtifact().getClassifier());
				}
				seen.put(key, dep);
			}
		}

		for (DependencyNode child : node.getChildren()) {
			collectRecursive(child, seen);
		}
	}

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

	private void writeToMainPom(List<Dependency> deps, List<Dependency> mgmtDeps) throws Exception {
		Model model = project.getOriginalModel();

		// Flattened: all transitives declared as direct dependencies
		model.setDependencies(deps);

		// Pinned: same set in dependencyManagement to force convergence
		DependencyManagement dm = new DependencyManagement();
		dm.setDependencies(mgmtDeps);
		model.setDependencyManagement(dm);

		File pomFile = project.getFile();
		try (FileWriter writer = new FileWriter(pomFile)) {
			new MavenXpp3Writer().write(writer, model);
			getLog().info("POM flattened: " + deps.size() + " direct deps, " + mgmtDeps.size() + " version pins.");
		}
	}
}