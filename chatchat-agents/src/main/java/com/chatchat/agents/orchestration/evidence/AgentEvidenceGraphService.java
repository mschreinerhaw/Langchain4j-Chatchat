package com.chatchat.agents.orchestration.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Builds and merges the versioned hypothesis and evidence graph contracts. */
public final class AgentEvidenceGraphService {

    private final ObjectMapper objectMapper;

    public AgentEvidenceGraphService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> normalizeHypotheses(Object raw, String currentEvidenceId) {
        List<?> values;
        if (raw instanceof List<?> list) values = list;
        else if (raw instanceof Map<?, ?> map) values = List.of(map);
        else return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> source = asStringObjectMap(map);
            String statement = stringValue(firstObject(source, "statement", "hypothesis", "description"));
            if (statement == null || statement.isBlank()) continue;
            String status = normalizedStatus(stringValue(firstObject(source, "status", "state")));
            String hypothesisId = firstNonBlank(
                stringValue(firstObject(source, "hypothesis_id", "hypothesisId", "id")),
                stableId(statement));
            List<String> support = new ArrayList<>(stringList(firstObject(source,
                "support_evidence_ids", "supportEvidenceIds", "support")));
            List<String> contradict = new ArrayList<>(stringList(firstObject(source,
                "contradict_evidence_ids", "contradictEvidenceIds", "contradict")));
            if (currentEvidenceId != null && !currentEvidenceId.isBlank()) {
                if ("SUPPORTED".equals(status) && !support.contains(currentEvidenceId)) support.add(currentEvidenceId);
                else if ("CONTRADICTED".equals(status) && !contradict.contains(currentEvidenceId)) {
                    contradict.add(currentEvidenceId);
                }
            }
            Map<String, Object> hypothesis = new LinkedHashMap<>();
            hypothesis.put("hypothesisId", hypothesisId);
            hypothesis.put("contractVersion", "hypothesis_tree_v1");
            String parentId = stringValue(firstObject(source,
                "parent_hypothesis_id", "parentHypothesisId", "parentId"));
            hypothesis.put("parentHypothesisId",
                parentId == null || parentId.isBlank() || hypothesisId.equals(parentId) ? null : parentId);
            hypothesis.put("childHypothesisIds", stringList(firstObject(source,
                "child_hypothesis_ids", "childHypothesisIds", "children")));
            hypothesis.put("statement", statement.trim());
            hypothesis.put("supportEvidenceIds", List.copyOf(support));
            hypothesis.put("contradictEvidenceIds", List.copyOf(contradict));
            hypothesis.put("confidence", score(firstObject(source, "confidence", "score")));
            hypothesis.put("status", status);
            result.add(hypothesis);
        }
        return List.copyOf(result);
    }

    public List<Map<String, Object>> mergeHypotheses(List<Map<String, Object>> previousEvidence,
                                                      List<Map<String, Object>> currentHypotheses) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (previousEvidence != null) {
            for (Map<String, Object> snapshot : previousEvidence) {
                for (Map<String, Object> hypothesis : normalizeHypotheses(
                    snapshot == null ? null : snapshot.get("hypotheses"), null)) {
                    mergeOne(merged, hypothesis);
                }
            }
        }
        if (currentHypotheses != null) currentHypotheses.forEach(item -> mergeOne(merged, item));
        rebuildTree(merged);
        return List.copyOf(merged.values());
    }

    public Map<String, Object> buildEvidenceGraph(int iteration,
                                                   List<Map<String, Object>> previousEvidence,
                                                   List<Map<String, Object>> currentEvidence,
                                                   List<Map<String, Object>> hypotheses) {
        LinkedHashMap<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        if (previousEvidence != null) {
            for (Map<String, Object> snapshot : previousEvidence) {
                collectEvidenceNodes(nodes, snapshot == null ? null : snapshot.get("toolEvidence"));
            }
        }
        collectEvidenceNodes(nodes, currentEvidence);
        if (hypotheses != null) {
            for (Map<String, Object> hypothesis : hypotheses) {
                String id = stringValue(hypothesis == null ? null : hypothesis.get("hypothesisId"));
                if (id == null || id.isBlank()) continue;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nodeId", id);
                node.put("nodeType", "HYPOTHESIS");
                node.put("refId", id);
                node.put("status", hypothesis.getOrDefault("status", "UNRESOLVED"));
                node.put("statement", hypothesis.get("statement"));
                nodes.put(id, node);
            }
        }
        LinkedHashMap<String, Map<String, Object>> relations = new LinkedHashMap<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        if (hypotheses != null) {
            for (Map<String, Object> hypothesis : hypotheses) {
                String id = stringValue(hypothesis == null ? null : hypothesis.get("hypothesisId"));
                if (id == null || id.isBlank()) continue;
                addRelations(relations, rejected, nodes,
                    stringList(hypothesis.get("supportEvidenceIds")), id, "SUPPORTS", iteration);
                addRelations(relations, rejected, nodes,
                    stringList(hypothesis.get("contradictEvidenceIds")), id, "CONTRADICTS", iteration);
                String parentId = stringValue(hypothesis.get("parentHypothesisId"));
                if (parentId != null && !parentId.isBlank() && nodes.containsKey(parentId)) {
                    Map<String, Object> relation = relation(parentId, id, "DECOMPOSES_TO", iteration);
                    relations.put(stringValue(relation.get("relationId")), relation);
                }
            }
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("contractVersion", "evidence_graph_v1");
        graph.put("graphId", "evidence-graph:iteration:" + iteration);
        graph.put("iteration", iteration);
        graph.put("nodes", List.copyOf(nodes.values()));
        graph.put("relations", List.copyOf(relations.values()));
        graph.put("rejectedRelations", List.copyOf(rejected));
        graph.put("createdAt", System.currentTimeMillis());
        return graph;
    }

    public double evidenceConfidence(List<Map<String, Object>> toolEvidence,
                                     List<Map<String, Object>> hypotheses) {
        List<Double> scores = new ArrayList<>();
        if (hypotheses != null) {
            hypotheses.stream().map(item -> score(item.get("confidence")))
                .filter(value -> value > 0.0).forEach(scores::add);
        }
        if (scores.isEmpty()) collectQualityScores(toolEvidence, scores);
        return scores.isEmpty() ? 0.0
            : clamp(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    public String evidenceConfidenceType(List<Map<String, Object>> toolEvidence,
                                         List<Map<String, Object>> hypotheses) {
        if (hypotheses != null && hypotheses.stream()
            .map(item -> score(item.get("confidence"))).anyMatch(value -> value > 0.0)) {
            return "MODEL_ESTIMATED";
        }
        List<Double> scores = new ArrayList<>();
        collectQualityScores(toolEvidence, scores);
        return scores.isEmpty() ? "UNKNOWN" : "EVIDENCE_QUALITY_DERIVED";
    }

    private void mergeOne(Map<String, Map<String, Object>> target, Map<String, Object> incoming) {
        String id = stringValue(incoming == null ? null : incoming.get("hypothesisId"));
        if (id == null || id.isBlank()) return;
        Map<String, Object> existing = target.get(id);
        if (existing == null) {
            target.put(id, new LinkedHashMap<>(incoming));
            return;
        }
        LinkedHashSet<String> support = new LinkedHashSet<>(stringList(existing.get("supportEvidenceIds")));
        support.addAll(stringList(incoming.get("supportEvidenceIds")));
        LinkedHashSet<String> contradict = new LinkedHashSet<>(stringList(existing.get("contradictEvidenceIds")));
        contradict.addAll(stringList(incoming.get("contradictEvidenceIds")));
        existing.put("statement", firstNonBlank(
            stringValue(incoming.get("statement")), stringValue(existing.get("statement"))));
        Object parent = incoming.get("parentHypothesisId");
        if (parent != null && !String.valueOf(parent).isBlank() && !id.equals(String.valueOf(parent))) {
            existing.put("parentHypothesisId", parent);
        }
        existing.put("supportEvidenceIds", List.copyOf(support));
        existing.put("contradictEvidenceIds", List.copyOf(contradict));
        existing.put("confidence", incoming.getOrDefault("confidence", existing.getOrDefault("confidence", 0.0)));
        existing.put("status", incoming.getOrDefault("status", existing.getOrDefault("status", "UNRESOLVED")));
    }

    private void rebuildTree(Map<String, Map<String, Object>> hypotheses) {
        if (hypotheses.isEmpty()) return;
        hypotheses.values().forEach(item -> {
            item.put("contractVersion", "hypothesis_tree_v1");
            item.put("childHypothesisIds", new ArrayList<String>());
        });
        hypotheses.forEach((id, item) -> {
            String parentId = stringValue(item.get("parentHypothesisId"));
            if (parentId == null || parentId.isBlank() || id.equals(parentId) || !hypotheses.containsKey(parentId)) {
                item.put("parentHypothesisId", null);
            } else {
                @SuppressWarnings("unchecked")
                List<String> children = (List<String>) hypotheses.get(parentId).get("childHypothesisIds");
                if (!children.contains(id)) children.add(id);
            }
        });
        hypotheses.forEach((id, item) -> {
            item.put("childHypothesisIds", List.copyOf(stringList(item.get("childHypothesisIds"))));
            item.put("level", level(id, hypotheses));
        });
        hypotheses.forEach((id, item) -> {
            List<String> children = stringList(item.get("childHypothesisIds"));
            if (children.isEmpty()) {
                item.put("aggregateStatus", item.getOrDefault("status", "UNRESOLVED"));
                item.put("childStatusCounts", Map.of());
                return;
            }
            Map<String, Long> counts = children.stream().map(hypotheses::get).filter(Objects::nonNull)
                .map(child -> normalizedStatus(stringValue(child.get("status"))))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
            String aggregate = counts.getOrDefault("UNRESOLVED", 0L) > 0 ? "UNRESOLVED"
                : counts.getOrDefault("SUPPORTED", 0L) > 0 ? "SUPPORTED" : "CONTRADICTED";
            item.put("aggregateStatus", aggregate);
            item.put("childStatusCounts", counts);
        });
    }

    private int level(String id, Map<String, Map<String, Object>> hypotheses) {
        int level = 0;
        String current = id;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && hypotheses.containsKey(current) && level < 20) {
            if (!visited.add(current)) {
                hypotheses.get(id).put("parentHypothesisId", null);
                return 0;
            }
            String parent = stringValue(hypotheses.get(current).get("parentHypothesisId"));
            if (parent == null || parent.isBlank() || !hypotheses.containsKey(parent)) break;
            level++;
            current = parent;
        }
        if (level >= 20) {
            hypotheses.get(id).put("parentHypothesisId", null);
            return 0;
        }
        return level;
    }

    private void collectEvidenceNodes(Map<String, Map<String, Object>> nodes, Object rawEvidence) {
        if (!(rawEvidence instanceof Iterable<?> values)) return;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> evidence = asStringObjectMap(raw);
            String id = stringValue(evidence.get("evidenceId"));
            if (id == null || id.isBlank()) continue;
            nodes.put(id, new LinkedHashMap<>(Map.of(
                "nodeId", id, "nodeType", "EVIDENCE", "refId", id,
                "tool", stringValue(evidence.get("tool")) == null ? "" : stringValue(evidence.get("tool")),
                "iteration", evidence.getOrDefault("iteration", 0),
                "success", evidence.getOrDefault("success", false))));
        }
    }

    private void addRelations(Map<String, Map<String, Object>> relations,
                              List<Map<String, Object>> rejected,
                              Map<String, Map<String, Object>> nodes,
                              List<String> evidenceIds,
                              String hypothesisId,
                              String type,
                              int iteration) {
        for (String evidenceId : evidenceIds) {
            if (evidenceId == null || evidenceId.isBlank()) continue;
            if (!nodes.containsKey(evidenceId)) {
                rejected.add(metadataOf("from", evidenceId, "to", hypothesisId,
                    "relationType", type, "reason", "UNKNOWN_EVIDENCE_REFERENCE"));
                continue;
            }
            Map<String, Object> relation = relation(evidenceId, hypothesisId, type, iteration);
            relations.put(stringValue(relation.get("relationId")), relation);
        }
    }

    private Map<String, Object> relation(String from, String to, String type, int iteration) {
        String key = from + "|" + type + "|" + to;
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("relationId", "R-" + Integer.toUnsignedString(key.hashCode(), 16).toUpperCase(Locale.ROOT));
        relation.put("relationType", type);
        relation.put("from", from);
        relation.put("to", to);
        relation.put("iteration", iteration);
        relation.put("status", "ACTIVE");
        return relation;
    }

    private void collectQualityScores(List<Map<String, Object>> toolEvidence, List<Double> target) {
        if (toolEvidence == null) return;
        for (Map<String, Object> evidence : toolEvidence) {
            Map<String, Object> quality = asMap(evidence.get("evidenceQuality"));
            for (String dimension : List.of("sourceReliability", "freshness", "completeness", "consistency")) {
                Double value = assessedMetric(quality.get(dimension));
                if (value != null) target.add(value);
            }
        }
    }

    private Double assessedMetric(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> metric = asStringObjectMap(map);
        if (!"ASSESSED".equalsIgnoreCase(stringValue(metric.get("status"))) || metric.get("value") == null) {
            return null;
        }
        return score(metric.get("value"));
    }

    private double score(Object value) {
        if (value instanceof Number number) return clamp(number.doubleValue());
        try {
            return value == null ? 0.0 : clamp(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return asStringObjectMap(map);
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private Map<String, Object> asStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return result;
    }

    private String normalizedStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("SUPPORTED", "CONTRADICTED", "UNRESOLVED").contains(normalized)
            ? normalized : "UNRESOLVED";
    }

    private String stableId(String statement) {
        String normalized = statement == null ? "" : statement.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return "H-" + Integer.toUnsignedString(normalized.hashCode(), 16).toUpperCase(Locale.ROOT);
    }
}
