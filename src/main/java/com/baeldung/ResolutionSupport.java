package com.baeldung;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers shared by {@link InitMojo} and {@link FlattenMojo}.
 */
final class ResolutionSupport {

    private ResolutionSupport() {
    }

    /** Identity of a resolved artifact: groupId:artifactId:type[:classifier]. */
    static String key(Artifact a) {
        String type = a.getType() != null ? a.getType() : "jar";
        String classifier = a.hasClassifier() ? a.getClassifier() : "";
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + type
                + (classifier.isEmpty() ? "" : ":" + classifier);
    }

    /** Same identity as {@link #key(Artifact)}, for a declared dependency. */
    static String key(Dependency d) {
        String type = d.getType() != null ? d.getType() : "jar";
        String classifier = d.getClassifier() != null ? d.getClassifier() : "";
        return d.getGroupId() + ":" + d.getArtifactId() + ":" + type
                + (classifier.isEmpty() ? "" : ":" + classifier);
    }

    /**
     * For every artifact in a resolved dependency graph, computes the exclusions that were
     * in force for that artifact's subtree: the union of the exclusions declared on every
     * dependency edge from the root down to, and including, the artifact's own declaration.
     *
     * Exclusions live on edges, not on artifacts. Once an artifact is promoted to a direct
     * dependency of the flattened POM, only the exclusions attached to that direct dependency
     * remain, so the ones inherited from its original ancestors have to be copied onto it.
     *
     * If an artifact appears more than once, the shallowest occurrence wins (the same rule
     * Maven's nearest-wins mediation uses).
     */
    static Map<String, List<Exclusion>> effectiveExclusions(DependencyNode root) {
        Map<String, Integer> depthSeen = new HashMap<>();
        Map<String, List<Exclusion>> result = new LinkedHashMap<>();
        walk(root, new LinkedHashMap<String, Exclusion>(), 0, depthSeen, result);
        return result;
    }

    private static void walk(DependencyNode node,
                             Map<String, Exclusion> inherited,
                             int depth,
                             Map<String, Integer> depthSeen,
                             Map<String, List<Exclusion>> result) {
        Map<String, Exclusion> current = inherited;

        if (node.getParent() != null) {
            current = new LinkedHashMap<>(inherited);
            List<Exclusion> own = node.getExclusions();
            if (own != null) {
                for (Exclusion e : own) {
                    current.putIfAbsent(e.getGroupId() + ":" + e.getArtifactId(), e);
                }
            }

            Artifact artifact = node.getArtifact();
            if (artifact != null && artifact.getScope() != null) {
                String key = key(artifact);
                Integer previous = depthSeen.get(key);
                if (previous == null || depth < previous) {
                    depthSeen.put(key, depth);
                    result.put(key, copyOf(current.values()));
                }
            }
        }

        List<DependencyNode> children = node.getChildren();
        if (children != null) {
            for (DependencyNode child : children) {
                walk(child, current, depth + 1, depthSeen, result);
            }
        }
    }

    private static List<Exclusion> copyOf(Collection<Exclusion> exclusions) {
        List<Exclusion> copy = new ArrayList<>(exclusions.size());
        for (Exclusion e : exclusions) {
            copy.add(e.clone());
        }
        return copy;
    }
}