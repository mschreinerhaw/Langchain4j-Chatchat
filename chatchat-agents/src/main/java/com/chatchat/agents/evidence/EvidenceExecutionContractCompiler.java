package com.chatchat.agents.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EvidenceExecutionContractCompiler {

    private final EvidenceExecutionContractValidator validator = new EvidenceExecutionContractValidator();

    public EvidenceExecutionContract compile(EvidenceGraph graph, EvidenceExecutionReport report) {
        EvidenceExecutionDecision decision = report == null ? EvidenceExecutionDecision.EMPTY_RESULT : report.decision();
        EvidencePath selectedPath = report == null ? null : report.selectedPath();
        List<String> pathNodeIds = selectedPath == null ? List.of() : selectedPath.nodeIds();
        Map<String, EvidenceGraphNode> nodes = graph == null ? Map.of() : graph.nodes();

        Set<String> sourceRefs = new LinkedHashSet<>();
        List<EvidenceExecutionContract.TrustedSqlFact> trustedSql = new ArrayList<>();
        List<EvidenceExecutionContract.DeterministicFact> deterministicFacts = new ArrayList<>();
        for (String nodeId : pathNodeIds) {
            EvidenceGraphNode node = nodes.get(nodeId);
            if (node == null) {
                continue;
            }
            if (node.sourceRef() != null && !node.sourceRef().isBlank()) {
                sourceRefs.add(node.sourceRef());
            }
            if (node.type() == EvidenceGraphNodeType.TRUSTED_SQL) {
                trustedSql.add(trustedSqlFact(node));
                continue;
            }
            if (isAnswerFact(node)) {
                deterministicFacts.add(new EvidenceExecutionContract.DeterministicFact(
                    node.id(),
                    node.sourceRef(),
                    node.type(),
                    firstNonBlank(node.normalizedContent(), node.rawContent()),
                    node.confidence()
                ));
            }
        }

        List<String> sqlLineage = selectedPath == null ? List.of() : selectedPath.sqlLineage();
        boolean executable = report != null
            && report.answerContract() != null
            && report.answerContract().executable()
            && decision == EvidenceExecutionDecision.ANSWER_ALLOWED;
        boolean fromGraphOnly = report != null
            && report.answerContract() != null
            && report.answerContract().fromGraphOnly()
            && decision == EvidenceExecutionDecision.ANSWER_ALLOWED;

        String graphHash = hash(graphFingerprint(graph));
        EvidenceExecutionContract.ExecutionSpec executionSpec = executionSpec(pathNodeIds, nodes);
        EvidenceExecutionContract.EvidenceTiers evidenceTiers = evidenceTiers(graph, pathNodeIds);
        EvidenceExecutionContract.ExecutionDag executionDag = executionDag(graph);
        EvidencePathState pathState = pathState(graph, report, executionDag);
        String contractHash = hash(contractFingerprint(
            decision,
            pathState,
            pathNodeIds,
            sqlLineage,
            List.copyOf(sourceRefs),
            trustedSql,
            deterministicFacts,
            executionSpec,
            evidenceTiers,
            executionDag,
            graphHash,
            fromGraphOnly,
            executable
        ));
        EvidenceExecutionContract contract = new EvidenceExecutionContract(
            EvidenceExecutionContract.CONTRACT_VERSION,
            decision,
            pathState,
            pathNodeIds,
            sqlLineage,
            List.copyOf(sourceRefs),
            trustedSql,
            deterministicFacts,
            executionSpec,
            evidenceTiers,
            executionDag,
            graphHash,
            contractHash,
            fromGraphOnly,
            executable
        );
        validator.validateOrThrow(contract);
        return contract;
    }

    private EvidencePathState pathState(EvidenceGraph graph,
                                        EvidenceExecutionReport report,
                                        EvidenceExecutionContract.ExecutionDag executionDag) {
        if (report != null
            && report.decision() == EvidenceExecutionDecision.ANSWER_ALLOWED
            && report.answerContract() != null
            && report.answerContract().executable()) {
            return EvidencePathState.STRONG_PATH;
        }
        boolean hasGraphNodes = graph != null && !graph.nodes().isEmpty();
        boolean hasCandidatePath = report != null
            && report.selectedPath() != null
            && !report.selectedPath().nodeIds().isEmpty();
        boolean hasDagEdges = executionDag != null && !executionDag.edges().isEmpty();
        boolean hasConflicts = executionDag != null && executionDag.edges().stream()
            .anyMatch(edge -> "CONTRADICTS".equalsIgnoreCase(edge.type()));
        if ((hasCandidatePath || hasGraphNodes || hasDagEdges) && hasConflicts) {
            return EvidencePathState.CONFLICTED_PATH;
        }
        if (hasCandidatePath || hasGraphNodes || hasDagEdges) {
            return EvidencePathState.WEAK_PATH;
        }
        return EvidencePathState.NO_PATH;
    }

    private EvidenceExecutionContract.ExecutionSpec executionSpec(List<String> pathNodeIds,
                                                                  Map<String, EvidenceGraphNode> nodes) {
        List<EvidenceExecutionContract.ExecutionStep> steps = new ArrayList<>();
        int stepId = 1;
        for (String nodeId : pathNodeIds == null ? List.<String>of() : pathNodeIds) {
            EvidenceGraphNode node = nodes.get(nodeId);
            if (node == null) {
                continue;
            }
            steps.add(new EvidenceExecutionContract.ExecutionStep(
                stepId++,
                node.id(),
                actionText(node),
                node.sourceRef(),
                round(node.confidence())
            ));
        }
        return new EvidenceExecutionContract.ExecutionSpec("execution_spec", steps);
    }

    private EvidenceExecutionContract.EvidenceTiers evidenceTiers(EvidenceGraph graph,
                                                                  List<String> pathNodeIds) {
        if (graph == null || graph.nodes().isEmpty()) {
            return EvidenceExecutionContract.EvidenceTiers.empty();
        }
        Set<String> directNodeIds = new LinkedHashSet<>(pathNodeIds == null ? List.of() : pathNodeIds);
        List<EvidenceExecutionContract.EvidenceItem> direct = new ArrayList<>();
        List<EvidenceExecutionContract.EvidenceItem> supporting = new ArrayList<>();
        List<EvidenceExecutionContract.EvidenceItem> context = new ArrayList<>();
        for (EvidenceGraphNode node : graph.nodes().values()) {
            EvidenceExecutionContract.EvidenceItem item = evidenceItem(node);
            if (directNodeIds.contains(node.id()) && isEvidenceNode(node)) {
                direct.add(item);
            } else if (node.validated() && isEvidenceNode(node)) {
                supporting.add(item);
            } else if (node.sourceRef() != null && !node.sourceRef().isBlank()) {
                context.add(item);
            }
        }
        return new EvidenceExecutionContract.EvidenceTiers(
            direct.stream().limit(12).toList(),
            supporting.stream().limit(12).toList(),
            context.stream().limit(12).toList()
        );
    }

    private EvidenceExecutionContract.ExecutionDag executionDag(EvidenceGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return EvidenceExecutionContract.ExecutionDag.empty();
        }
        List<EvidenceExecutionContract.DagNode> dagNodes = graph.nodes().values().stream()
            .map(node -> new EvidenceExecutionContract.DagNode(
                node.id(),
                node.type() == null ? "" : node.type().name(),
                actionText(node),
                node.sourceRef(),
                round(node.confidence())
            ))
            .toList();
        List<EvidenceExecutionContract.DagEdge> dagEdges = graph.edges().stream()
            .map(edge -> new EvidenceExecutionContract.DagEdge(
                edge.fromNodeId(),
                edge.toNodeId(),
                edge.type() == null ? "" : edge.type().name(),
                round(edge.weight()),
                edge.reasoning()
            ))
            .toList();
        return new EvidenceExecutionContract.ExecutionDag(dagNodes, dagEdges);
    }

    private EvidenceExecutionContract.EvidenceItem evidenceItem(EvidenceGraphNode node) {
        return new EvidenceExecutionContract.EvidenceItem(
            node.id(),
            node.sourceRef(),
            node.type() == null ? "" : node.type().name(),
            firstNonBlank(node.normalizedContent(), node.rawContent()),
            round(node.confidence())
        );
    }

    private boolean isEvidenceNode(EvidenceGraphNode node) {
        return node.type() == EvidenceGraphNodeType.DOC_CHUNK
            || node.type() == EvidenceGraphNodeType.WEB_CHUNK
            || node.type() == EvidenceGraphNodeType.TEXT_FACT
            || node.type() == EvidenceGraphNodeType.TABLE_FACT
            || node.type() == EvidenceGraphNodeType.TRUSTED_SQL;
    }

    private String actionText(EvidenceGraphNode node) {
        String text = firstNonBlank(node.normalizedContent(), node.rawContent());
        if (text == null || text.isBlank()) {
            return node.type() == null ? node.id() : node.type().name();
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96) + "...";
    }

    private EvidenceExecutionContract.TrustedSqlFact trustedSqlFact(EvidenceGraphNode node) {
        return new EvidenceExecutionContract.TrustedSqlFact(
            node.id(),
            node.sourceRef(),
            stringValue(node.metadata().get("sqlType")),
            firstNonBlank(node.normalizedContent(), node.rawContent()),
            stringList(node.metadata().get("tables")),
            Boolean.parseBoolean(stringValue(node.metadata().get("executionVerified"))),
            doubleValue(node.metadata().get("validationScore"))
        );
    }

    private boolean isAnswerFact(EvidenceGraphNode node) {
        return node.type() == EvidenceGraphNodeType.DOC_CHUNK
            || node.type() == EvidenceGraphNodeType.WEB_CHUNK
            || node.type() == EvidenceGraphNodeType.TEXT_FACT
            || node.type() == EvidenceGraphNodeType.TABLE_FACT;
    }

    private String graphFingerprint(EvidenceGraph graph) {
        if (graph == null) {
            return "graph:null";
        }
        StringBuilder value = new StringBuilder();
        value.append(graph.contractVersion()).append('|').append(graph.queryId()).append('\n');
        graph.nodes().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                EvidenceGraphNode node = entry.getValue();
                value.append("N|")
                    .append(entry.getKey()).append('|')
                    .append(node.type()).append('|')
                    .append(node.sourceRef()).append('|')
                    .append(node.validated()).append('|')
                    .append(round(node.confidence())).append('|')
                    .append(node.normalizedContent()).append('\n');
            });
        graph.edges().stream()
            .sorted(Comparator.comparing(EvidenceGraphEdge::fromNodeId)
                .thenComparing(EvidenceGraphEdge::toNodeId)
                .thenComparing(edge -> edge.type() == null ? "" : edge.type().name()))
            .forEach(edge -> value.append("E|")
                .append(edge.fromNodeId()).append('|')
                .append(edge.toNodeId()).append('|')
                .append(edge.type()).append('|')
                .append(round(edge.weight())).append('|')
                .append(edge.reasoning()).append('\n'));
        graph.validPaths().stream()
            .sorted(Comparator.comparing(path -> String.join(">", path.nodeIds())))
            .forEach(path -> value.append("P|")
                .append(String.join(">", path.nodeIds())).append('|')
                .append(round(path.score())).append('|')
                .append(String.join(",", path.sqlLineage())).append('|')
                .append(path.executable()).append('\n'));
        return value.toString();
    }

    private String contractFingerprint(EvidenceExecutionDecision decision,
                                       EvidencePathState pathState,
                                       List<String> pathNodeIds,
                                       List<String> sqlLineage,
                                       List<String> sourceRefs,
                                       List<EvidenceExecutionContract.TrustedSqlFact> trustedSql,
                                       List<EvidenceExecutionContract.DeterministicFact> deterministicFacts,
                                       EvidenceExecutionContract.ExecutionSpec executionSpec,
                                       EvidenceExecutionContract.EvidenceTiers evidenceTiers,
                                       EvidenceExecutionContract.ExecutionDag executionDag,
                                       String graphHash,
                                       boolean fromGraphOnly,
                                       boolean executable) {
        StringBuilder value = new StringBuilder();
        value.append(EvidenceExecutionContract.CONTRACT_VERSION).append('|')
            .append(decision).append('|')
            .append(pathState).append('|')
            .append(String.join(">", pathNodeIds)).append('|')
            .append(String.join(",", sqlLineage)).append('|')
            .append(String.join(",", sourceRefs)).append('|')
            .append(graphHash).append('|')
            .append(fromGraphOnly).append('|')
            .append(executable).append('\n');
        trustedSql.forEach(sql -> value.append("SQL|")
            .append(sql.nodeId()).append('|')
            .append(sql.sourceRef()).append('|')
            .append(sql.sqlType()).append('|')
            .append(sql.normalizedSql()).append('|')
            .append(String.join(",", sql.tables())).append('|')
            .append(sql.executionVerified()).append('|')
            .append(round(sql.validationScore())).append('\n'));
        deterministicFacts.forEach(fact -> value.append("FACT|")
            .append(fact.nodeId()).append('|')
            .append(fact.sourceRef()).append('|')
            .append(fact.nodeType()).append('|')
            .append(round(fact.confidence())).append('|')
            .append(fact.content()).append('\n'));
        executionSpec.steps().forEach(step -> value.append("STEP|")
            .append(step.id()).append('|')
            .append(step.nodeId()).append('|')
            .append(step.source()).append('|')
            .append(round(step.confidence())).append('|')
            .append(step.action()).append('\n'));
        appendEvidenceFingerprint(value, "DIRECT", evidenceTiers.direct());
        appendEvidenceFingerprint(value, "SUPPORTING", evidenceTiers.supporting());
        appendEvidenceFingerprint(value, "CONTEXT", evidenceTiers.context());
        executionDag.nodes().forEach(node -> value.append("DAG_NODE|")
            .append(node.id()).append('|')
            .append(node.type()).append('|')
            .append(node.source()).append('|')
            .append(round(node.confidence())).append('|')
            .append(node.text()).append('\n'));
        executionDag.edges().forEach(edge -> value.append("DAG_EDGE|")
            .append(edge.from()).append('|')
            .append(edge.to()).append('|')
            .append(edge.type()).append('|')
            .append(round(edge.confidence())).append('|')
            .append(edge.reasoning()).append('\n'));
        return value.toString();
    }

    private void appendEvidenceFingerprint(StringBuilder value,
                                           String tier,
                                           List<EvidenceExecutionContract.EvidenceItem> items) {
        items.forEach(item -> value.append("EVIDENCE_").append(tier).append('|')
            .append(item.nodeId()).append('|')
            .append(item.refId()).append('|')
            .append(item.type()).append('|')
            .append(round(item.confidence())).append('|')
            .append(item.text()).append('\n'));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        String single = stringValue(value);
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
