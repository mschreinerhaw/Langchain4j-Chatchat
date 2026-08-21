package com.chatchat.agents.orchestration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime-validated grouping derived only from explicitly supplied dataset relationships. */
public record DatasetRelationshipPlan(
    String schemaVersion,
    List<Group> groups,
    List<Edge> edges,
    List<String> unresolvedReferences,
    Map<String, Object> governance
) {
    public static final String SCHEMA_VERSION = "dataset_relationship_plan.v1";

    public DatasetRelationshipPlan {
        schemaVersion = SCHEMA_VERSION;
        groups = groups == null ? List.of() : List.copyOf(groups);
        edges = edges == null ? List.of() : List.copyOf(edges);
        unresolvedReferences = unresolvedReferences == null
            ? List.of() : List.copyOf(unresolvedReferences);
        governance = governance == null ? Map.of() : Map.copyOf(governance);
    }

    public static DatasetRelationshipPlan create(List<Dataset> datasets) {
        List<Dataset> safeDatasets = datasets == null ? List.of() : List.copyOf(datasets);
        Map<String, String> aliases = aliases(safeDatasets);
        UnionFind components = new UnionFind(safeDatasets.stream().map(Dataset::reference).toList());
        List<Edge> edges = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Map<String, List<String>> explicitGroups = new LinkedHashMap<>();

        for (Dataset dataset : safeDatasets) {
            Object relationships = dataset.analysisContext().get("relationships");
            collectRelations(dataset.reference(), relationships, "$", aliases, components,
                edges, unresolved, explicitGroups, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        explicitGroups.values().forEach(members -> {
            if (members.size() < 2) return;
            String first = members.get(0);
            for (int index = 1; index < members.size(); index++) {
                components.union(first, members.get(index));
                edges.add(new Edge(first, members.get(index), "EXPLICIT_GROUP", "$.groupId"));
            }
        });

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Dataset dataset : safeDatasets) {
            grouped.computeIfAbsent(components.find(dataset.reference()), ignored -> new ArrayList<>())
                .add(dataset.reference());
        }
        List<Group> groups = new ArrayList<>();
        int relationGroup = 0;
        for (List<String> members : grouped.values()) {
            boolean related = members.size() > 1;
            String groupId = related
                ? "relationship-group-" + (++relationGroup)
                : "standalone:" + members.get(0);
            groups.add(new Group(groupId, List.copyOf(members), related));
        }
        return new DatasetRelationshipPlan(SCHEMA_VERSION, groups, edges, List.copyOf(unresolved), Map.of(
            "relationshipPolicy", "EXPLICIT_RELATIONSHIPS_ONLY",
            "unknownRelationshipPolicy", "KEEP_DATASET_STANDALONE",
            "datasetCoverageRequired", true
        ));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("groups", groups.stream().map(Group::toMap).toList());
        result.put("edges", edges.stream().map(Edge::toMap).toList());
        result.put("unresolvedReferences", unresolvedReferences);
        result.put("governance", governance);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> aliases(List<Dataset> datasets) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Dataset dataset : datasets) {
            addAlias(aliases, dataset.reference(), dataset.reference());
            Map<String, Object> source = map(dataset.analysisContext().get("source"));
            for (String key : List.of("id", "toolName", "remoteToolName", "runtimeReference")) {
                addAlias(aliases, text(source.get(key)), dataset.reference());
            }
        }
        return aliases;
    }

    private static void addAlias(Map<String, String> aliases, String alias, String reference) {
        if (alias != null && !alias.isBlank()) aliases.putIfAbsent(alias, reference);
    }

    private static void collectRelations(
        String source,
        Object value,
        String path,
        Map<String, String> aliases,
        UnionFind components,
        List<Edge> edges,
        Set<String> unresolved,
        Map<String, List<String>> explicitGroups,
        Set<Object> visited
    ) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (isGroupKey(key)) {
                    String group = text(child);
                    if (group != null && !group.isBlank()) {
                        explicitGroups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(source);
                    }
                }
                if (isTargetKey(key)) {
                    collectTargets(source, child, path + "." + key, aliases, components, edges, unresolved);
                } else {
                    collectRelations(source, child, path + "." + key, aliases, components,
                        edges, unresolved, explicitGroups, visited);
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object child : iterable) {
                collectRelations(source, child, path + "[" + index++ + "]", aliases, components,
                    edges, unresolved, explicitGroups, visited);
            }
        }
    }

    private static void collectTargets(String source, Object value, String path,
                                       Map<String, String> aliases, UnionFind components,
                                       List<Edge> edges, Set<String> unresolved) {
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                collectTargets(source, item, path + "[" + index++ + "]", aliases,
                    components, edges, unresolved);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectTargets(source, item, path, aliases, components, edges, unresolved);
            }
            return;
        }
        String declared = text(value);
        if (declared == null || declared.isBlank()) return;
        String target = aliases.get(declared);
        if (target == null) {
            unresolved.add(source + " -> " + declared + " @ " + path);
        } else if (!source.equals(target)) {
            components.union(source, target);
            edges.add(new Edge(source, target, "EXPLICIT_REFERENCE", path));
        }
    }

    private static boolean isTargetKey(String key) {
        String normalized = key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase();
        return Set.of("target", "to", "dataset", "datasetid", "datasetreference",
            "targetdataset", "targetdatasetid", "relateddataset", "relateddatasets").contains(normalized);
    }

    private static boolean isGroupKey(String key) {
        String normalized = key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase();
        return Set.of("group", "groupid", "relationshipgroup", "relationshipgroupid").contains(normalized);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    public record Dataset(String reference, Map<String, Object> analysisContext) {
        public Dataset {
            analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
        }
    }

    public record Group(String groupId, List<String> datasetReferences, boolean explicitRelationship) {
        public Map<String, Object> toMap() {
            return Map.of("groupId", groupId, "datasetReferences", datasetReferences,
                "explicitRelationship", explicitRelationship);
        }
    }

    public record Edge(String fromDataset, String toDataset, String relationType, String declarationPath) {
        public Map<String, Object> toMap() {
            return Map.of("fromDataset", fromDataset, "toDataset", toDataset,
                "relationType", relationType, "declarationPath", declarationPath);
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();

        private UnionFind(List<String> nodes) {
            nodes.forEach(node -> parent.put(node, node));
        }

        private String find(String node) {
            String current = parent.get(node);
            if (current == null || current.equals(node)) return node;
            String root = find(current);
            parent.put(node, root);
            return root;
        }

        private void union(String left, String right) {
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (!leftRoot.equals(rightRoot)) parent.put(rightRoot, leftRoot);
        }
    }
}
