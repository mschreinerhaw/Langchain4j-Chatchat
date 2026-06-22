package com.chatchat.agents.evidence;

import java.util.List;

public record EvidenceExecutionContract(
    String contractVersion,
    EvidenceExecutionDecision decision,
    EvidencePathState pathState,
    List<String> evidencePath,
    List<String> sqlLineage,
    List<String> sourceRefs,
    List<TrustedSqlFact> trustedSql,
    List<DeterministicFact> deterministicFacts,
    ExecutionSpec executionSpec,
    EvidenceTiers evidence,
    ExecutionDag executionDag,
    String graphViewHash,
    String contractHash,
    boolean fromGraphOnly,
    boolean executable
) {

    public static final String CONTRACT_VERSION = "evidence_execution_contract_v2_2";

    public EvidenceExecutionContract {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        decision = decision == null ? EvidenceExecutionDecision.EMPTY_RESULT : decision;
        pathState = pathState == null ? EvidencePathState.NO_PATH : pathState;
        evidencePath = evidencePath == null ? List.of() : List.copyOf(evidencePath);
        sqlLineage = sqlLineage == null ? List.of() : List.copyOf(sqlLineage);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        trustedSql = trustedSql == null ? List.of() : List.copyOf(trustedSql);
        deterministicFacts = deterministicFacts == null ? List.of() : List.copyOf(deterministicFacts);
        executionSpec = executionSpec == null ? ExecutionSpec.empty() : executionSpec;
        evidence = evidence == null ? EvidenceTiers.empty() : evidence;
        executionDag = executionDag == null ? ExecutionDag.empty() : executionDag;
        graphViewHash = graphViewHash == null ? "" : graphViewHash;
        contractHash = contractHash == null ? "" : contractHash;
    }

    public record TrustedSqlFact(
        String nodeId,
        String sourceRef,
        String sqlType,
        String normalizedSql,
        List<String> tables,
        boolean executionVerified,
        double validationScore
    ) {

        public TrustedSqlFact {
            nodeId = nodeId == null ? "" : nodeId;
            sourceRef = sourceRef == null ? "" : sourceRef;
            sqlType = sqlType == null ? "" : sqlType;
            normalizedSql = normalizedSql == null ? "" : normalizedSql;
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record DeterministicFact(
        String nodeId,
        String sourceRef,
        EvidenceGraphNodeType nodeType,
        String content,
        double confidence
    ) {

        public DeterministicFact {
            nodeId = nodeId == null ? "" : nodeId;
            sourceRef = sourceRef == null ? "" : sourceRef;
            content = content == null ? "" : content;
        }
    }

    public record ExecutionSpec(
        String type,
        List<ExecutionStep> steps
    ) {

        public ExecutionSpec {
            type = type == null || type.isBlank() ? "execution_spec" : type;
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public static ExecutionSpec empty() {
            return new ExecutionSpec("execution_spec", List.of());
        }
    }

    public record ExecutionStep(
        int id,
        String nodeId,
        String action,
        String source,
        double confidence
    ) {

        public ExecutionStep {
            nodeId = nodeId == null ? "" : nodeId;
            action = action == null ? "" : action;
            source = source == null ? "" : source;
        }
    }

    public record EvidenceTiers(
        List<EvidenceItem> direct,
        List<EvidenceItem> supporting,
        List<EvidenceItem> context
    ) {

        public EvidenceTiers {
            direct = direct == null ? List.of() : List.copyOf(direct);
            supporting = supporting == null ? List.of() : List.copyOf(supporting);
            context = context == null ? List.of() : List.copyOf(context);
        }

        public static EvidenceTiers empty() {
            return new EvidenceTiers(List.of(), List.of(), List.of());
        }
    }

    public record EvidenceItem(
        String nodeId,
        String refId,
        String type,
        String text,
        double confidence
    ) {

        public EvidenceItem {
            nodeId = nodeId == null ? "" : nodeId;
            refId = refId == null ? "" : refId;
            type = type == null ? "" : type;
            text = text == null ? "" : text;
        }
    }

    public record ExecutionDag(
        List<DagNode> nodes,
        List<DagEdge> edges
    ) {

        public ExecutionDag {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        public static ExecutionDag empty() {
            return new ExecutionDag(List.of(), List.of());
        }
    }

    public record DagNode(
        String id,
        String type,
        String text,
        String source,
        double confidence
    ) {

        public DagNode {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            text = text == null ? "" : text;
            source = source == null ? "" : source;
        }
    }

    public record DagEdge(
        String from,
        String to,
        String type,
        double confidence,
        String reasoning
    ) {

        public DagEdge {
            from = from == null ? "" : from;
            to = to == null ? "" : to;
            type = type == null ? "" : type;
            reasoning = reasoning == null ? "" : reasoning;
        }
    }
}
