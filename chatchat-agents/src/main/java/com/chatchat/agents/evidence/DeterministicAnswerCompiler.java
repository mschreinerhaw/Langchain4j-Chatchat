package com.chatchat.agents.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class DeterministicAnswerCompiler {

    public static final String LOCK_HEADER = "Deterministic answer lock";
    public static final String BEGIN_LOCKED_ANSWER = "---BEGIN_LOCKED_ANSWER---";
    public static final String END_LOCKED_ANSWER = "---END_LOCKED_ANSWER---";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String compile(EvidenceExecutionContract contract) {
        if (contract == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(LOCK_HEADER)
            .append(" (contractVersion=")
            .append(contract.contractVersion())
            .append("):\n");
        builder.append("decision: ").append(contract.decision()).append('\n');
        builder.append("pathState: ").append(contract.pathState()).append('\n');
        builder.append("contractHash: ").append(contract.contractHash()).append('\n');
        builder.append("graphViewHash: ").append(contract.graphViewHash()).append('\n');
        builder.append("fromGraphOnly: ").append(contract.fromGraphOnly()).append('\n');
        builder.append("executable: ").append(contract.executable()).append('\n');
        if (!contract.evidencePath().isEmpty()) {
            builder.append("evidencePath: ").append(String.join(" -> ", contract.evidencePath())).append('\n');
        }
        if (!contract.sourceRefs().isEmpty()) {
            builder.append("sourceRefs: ").append(String.join(", ", contract.sourceRefs())).append('\n');
        }
        if (!contract.sqlLineage().isEmpty()) {
            builder.append("sqlLineage: ").append(String.join(", ", contract.sqlLineage())).append('\n');
        }
        builder.append("lockedAnswer:\n")
            .append(BEGIN_LOCKED_ANSWER)
            .append('\n')
            .append(lockedAnswer(contract).trim())
            .append('\n')
            .append(END_LOCKED_ANSWER);
        return builder.toString();
    }

    private String lockedAnswer(EvidenceExecutionContract contract) {
        String reasoningBlock = "```json\n" + reasoningJson(contract) + "\n```";
        if (contract.decision() != EvidenceExecutionDecision.ANSWER_ALLOWED || !contract.executable()) {
            return reasoningBlock
                + "\n\n" + unavailableOrDegradedAnswer(contract);
        }

        StringBuilder answer = new StringBuilder(reasoningBlock);
        answer.append("\n\n\u6839\u636e\u5df2\u6267\u884c\u7684\u8bc1\u636e\u56fe\u8def\u5f84\uff0c\u53ef\u4ee5\u57fa\u4e8e\u4ee5\u4e0b\u53ef\u8ffd\u6eaf\u8bc1\u636e\u56de\u7b54\u3002");
        if (!contract.sourceRefs().isEmpty()) {
            answer.append("\n\n\u5f15\u7528\u6765\u6e90\uff1a")
                .append(String.join("\uff1b", contract.sourceRefs()))
                .append("\u3002");
        }
        if (!contract.deterministicFacts().isEmpty()) {
            answer.append("\n\n\u4e8b\u5b9e\u4f9d\u636e\uff1a");
            for (int i = 0; i < contract.deterministicFacts().size(); i++) {
                EvidenceExecutionContract.DeterministicFact fact = contract.deterministicFacts().get(i);
                answer.append("\n").append(i + 1).append(". ");
                if (fact.sourceRef() != null && !fact.sourceRef().isBlank()) {
                    answer.append("[").append(fact.sourceRef()).append("] ");
                }
                answer.append(limit(fact.content(), 900));
            }
        }
        if (!contract.trustedSql().isEmpty()) {
            answer.append("\n\n\u53ef\u4fe1 SQL / \u4e8b\u5b9e\u94fe\u8def\uff1a");
            for (int i = 0; i < contract.trustedSql().size(); i++) {
                EvidenceExecutionContract.TrustedSqlFact sql = contract.trustedSql().get(i);
                answer.append("\n").append(i + 1).append(". ");
                if (sql.sourceRef() != null && !sql.sourceRef().isBlank()) {
                    answer.append("\u6765\u6e90 ").append(sql.sourceRef()).append("\uff1a");
                }
                answer.append("\u7c7b\u578b ").append(sql.sqlType()).append("\uff1b")
                    .append("\u6267\u884c\u6821\u9a8c ").append(sql.executionVerified() ? "\u901a\u8fc7" : "\u672a\u901a\u8fc7").append("\uff1b")
                    .append("\u6821\u9a8c\u5206 ").append(round(sql.validationScore())).append("\u3002");
                if (!sql.tables().isEmpty()) {
                    answer.append("\u8868\u8840\u7f18\uff1a").append(String.join(", ", sql.tables())).append("\u3002");
                }
                answer.append("\nSQL:\n").append(sql.normalizedSql());
            }
        }
        answer.append("\n\n\u6267\u884c\u7ea6\u675f\uff1a\u672c\u7b54\u6848\u53ea\u4f7f\u7528 Evidence Execution Contract \u4e2d\u7684\u8def\u5f84\u3001\u6765\u6e90\u548c\u53ef\u4fe1 SQL\uff0c\u672a\u5f15\u5165\u5408\u540c\u5916\u8bc1\u636e\u3002");
        return answer.toString();
    }

    private String reasoningJson(EvidenceExecutionContract contract) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "evidence_reasoning_v2");
        root.put("contractVersion", contract.contractVersion());
        root.put("pathState", contract.pathState());
        root.put("contractHash", contract.contractHash());
        root.put("graphViewHash", contract.graphViewHash());
        root.put("decision", contract.decision());
        root.put("fromGraphOnly", contract.fromGraphOnly());
        root.put("executable", contract.executable());
        root.put("result", result(contract));
        root.put("executionSpec", contract.executionSpec());
        root.put("evidence", contract.evidence());
        root.put("executionDag", contract.executionDag());
        root.put("trustedSql", contract.trustedSql());
        root.put("deterministicFacts", contract.deterministicFacts());
        root.put("reasoningTrace", reasoningTrace(contract));
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            return "{\"type\":\"evidence_reasoning_v2\",\"serializationError\":\"" + limit(ex.getMessage(), 160) + "\"}";
        }
    }

    private Map<String, Object> reasoningTrace(EvidenceExecutionContract contract) {
        Map<String, Object> trace = new LinkedHashMap<>();
        List<String> selectedPath = contract.evidencePath();
        List<EvidenceExecutionContract.DagNode> pathNodes = selectedPath.stream()
            .map(nodeId -> findNode(contract.executionDag().nodes(), nodeId))
            .filter(node -> node != null)
            .toList();
        List<EvidenceExecutionContract.DagEdge> pathEdges = pathEdges(contract.executionDag().edges(), selectedPath);
        List<EvidenceExecutionContract.DagEdge> conflicts = contract.executionDag().edges().stream()
            .filter(edge -> "CONTRADICTS".equalsIgnoreCase(edge.type()))
            .toList();
        double weakestNode = pathNodes.stream()
            .mapToDouble(EvidenceExecutionContract.DagNode::confidence)
            .min()
            .orElse(0.0);
        double weakestEdge = pathEdges.stream()
            .mapToDouble(EvidenceExecutionContract.DagEdge::confidence)
            .min()
            .orElse(pathEdges.isEmpty() ? weakestNode : 0.0);
        double weakestLink = pathEdges.isEmpty()
            ? weakestNode
            : Math.min(weakestNode, weakestEdge);
        double averageEdge = pathEdges.stream()
            .mapToDouble(EvidenceExecutionContract.DagEdge::confidence)
            .average()
            .orElse(0.0);
        double pathCoherence = round(clamp(weakestLink * 0.58 + averageEdge * 0.42));

        trace.put("type", "evidence_reasoning_trace_v13");
        trace.put("layers", Map.of(
            "graph", "structure",
            "path", "selection",
            "explanation", "why-this-path"
        ));
        trace.put("pathDecision", Map.of(
            "selectedPath", selectedPath,
            "nodeCount", pathNodes.size(),
            "edgeCount", pathEdges.size(),
            "weakestLink", round(weakestLink),
            "pathCoherence", pathCoherence,
            "bottleneckPenalty", round(1.0 - weakestLink),
            "pathState", contract.pathState(),
            "decision", pathDecisionText(contract.pathState())
        ));
        trace.put("conflictResolutions", conflictResolutions(conflicts));
        trace.put("explanation", explanation(contract, pathCoherence, weakestLink, conflicts));
        return trace;
    }

    private Map<String, Object> result(EvidenceExecutionContract contract) {
        Map<String, Object> value = new LinkedHashMap<>();
        List<Map<String, Object>> claims = boundClaims(contract);
        boolean exists = resultExists(contract, claims);
        String conclusion = exists ? resultConclusion(contract, claims) : "";
        String evidenceSummary = exists ? resultEvidenceSummary(claims) : "";
        String uncertainty = exists ? resultUncertainty(contract.pathState()) : "";
        Map<String, Object> structure = exists ? resultStructure(claims) : Map.of();
        List<String> supportingEvidence = exists ? resultSupportingEvidence(claims) : List.of();
        List<Map<String, Object>> evidenceClaims = exists ? resultEvidenceClaims(claims) : List.of();
        value.put("exists", exists);
        value.put("mode", resultMode(contract.pathState()));
        value.put("confidence", resultConfidence(contract));
        value.put("conclusion", conclusion);
        value.put("evidenceSummary", evidenceSummary);
        value.put("supportingEvidence", supportingEvidence);
        value.put("evidenceClaims", evidenceClaims);
        value.put("uncertainty", uncertainty);
        value.put("structure", structure);
        value.put("claims", claims);
        value.put("claimGraph", claimGraph(contract, claims));
        value.put("answer", exists ? resultAnswer(contract, claims, conclusion, evidenceSummary, uncertainty) : "");
        value.put("quality", resultQuality(contract.pathState()));
        return value;
    }

    private boolean resultExists(EvidenceExecutionContract contract) {
        return resultExists(contract, boundClaims(contract));
    }

    private boolean resultExists(EvidenceExecutionContract contract, List<Map<String, Object>> claims) {
        return contract.pathState() != EvidencePathState.NO_PATH
            && claims != null
            && !claims.isEmpty();
    }

    private String resultMode(EvidencePathState pathState) {
        return switch (pathState == null ? EvidencePathState.NO_PATH : pathState) {
            case STRONG_PATH -> "CONFIRMED";
            case WEAK_PATH -> "PRELIMINARY";
            case CONFLICTED_PATH -> "CONFLICTED";
            case NO_PATH -> "UNAVAILABLE";
        };
    }

    private String resultQuality(EvidencePathState pathState) {
        return switch (pathState == null ? EvidencePathState.NO_PATH : pathState) {
            case STRONG_PATH -> "high_confidence";
            case WEAK_PATH -> "needs_review";
            case CONFLICTED_PATH -> "conflict_requires_resolution";
            case NO_PATH -> "insufficient_evidence";
        };
    }

    private String resultAnswer(EvidenceExecutionContract contract) {
        List<Map<String, Object>> claims = boundClaims(contract);
        String conclusion = resultConclusion(contract, claims);
        String evidenceSummary = resultEvidenceSummary(claims);
        String uncertainty = resultUncertainty(contract.pathState());
        return resultAnswer(contract, claims, conclusion, evidenceSummary, uncertainty);
    }

    private String resultAnswer(EvidenceExecutionContract contract,
                                List<Map<String, Object>> claims,
                                String conclusion,
                                String evidenceSummary,
                                String uncertainty) {
        String evidenceText = evidenceSummary == null || evidenceSummary.isBlank() ? claimText(claims) : evidenceSummary;
        if (conclusion != null && !conclusion.isBlank()) {
            StringBuilder builder = new StringBuilder();
            builder.append("\u7ed3\u8bba\uff1a").append(conclusion);
            String supportingText = supportingEvidenceText(primaryClaim(claims));
            if (!supportingText.isBlank()) {
                builder.append("\n\u652f\u6301\u4f9d\u636e\uff1a").append(supportingText);
            }
            if (uncertainty != null && !uncertainty.isBlank()) {
                builder.append("\n\u4e0d\u786e\u5b9a\u6027\uff1a").append(uncertainty);
            }
            return builder.toString();
        }
        if (contract.pathState() == EvidencePathState.STRONG_PATH) {
            return evidenceText.isBlank() ? "已形成可追溯的确定结论。" : evidenceText;
        }
        if (contract.pathState() == EvidencePathState.WEAK_PATH) {
            return evidenceText.isBlank()
                ? "初步判断：当前证据支持一个候选结果，但路径稳定性一般，建议补充资料后再用于决策。"
                : "初步判断：" + evidenceText + " 该结果可信度一般，建议结合更多资料验证。";
        }
        if (contract.pathState() == EvidencePathState.CONFLICTED_PATH) {
            return evidenceText.isBlank()
                ? "候选结果存在，但证据之间存在冲突，需先完成冲突核验。"
                : "候选判断：" + evidenceText + " 但证据之间存在冲突，需先完成冲突核验。";
        }
        return "";
    }

    private String resultConclusion(EvidenceExecutionContract contract, List<Map<String, Object>> claims) {
        Map<String, Object> primary = primaryClaim(claims);
        String primaryText = primary == null ? "" : String.valueOf(primary.getOrDefault("text", "")).trim();
        if (primaryText.isBlank()) {
            return "";
        }
        String conclusion = conciseClaim(primaryText);
        return switch (contract.pathState() == null ? EvidencePathState.NO_PATH : contract.pathState()) {
            case STRONG_PATH, WEAK_PATH -> conclusion;
            case CONFLICTED_PATH -> "\u5019\u9009\u5224\u65ad\uff1a" + conclusion;
            case NO_PATH -> "";
        };
    }

    private String conciseClaim(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("(?i)^\\s*\\[?doc://[^\\]\\s]+\\]?\\s*", "");
        int max = 180;
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    private String resultEvidenceSummary(List<Map<String, Object>> claims) {
        String supportingText = supportingEvidenceText(primaryClaim(claims));
        return supportingText.isBlank() ? claimText(claims) : supportingText;
    }

    private List<String> resultSupportingEvidence(List<Map<String, Object>> claims) {
        Map<String, Object> primary = primaryClaim(claims);
        List<String> values = claimSupportingEvidence(primary);
        if (!values.isEmpty()) {
            return values;
        }
        String text = primary == null ? "" : String.valueOf(primary.getOrDefault("text", "")).trim();
        return text.isBlank() ? List.of() : List.of(limit(text, 120));
    }

    private List<Map<String, Object>> resultEvidenceClaims(List<Map<String, Object>> claims) {
        Map<String, Object> primary = primaryClaim(claims);
        List<Map<String, Object>> values = claimEvidenceClaims(primary);
        if (!values.isEmpty()) {
            return values;
        }
        String text = primary == null ? "" : String.valueOf(primary.getOrDefault("text", "")).trim();
        String sourceRef = primary == null ? "" : String.valueOf(primary.getOrDefault("sourceRef", "")).trim();
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(evidenceClaim(limit(text, 120), sourceRef, limit(text, 120)));
    }

    private Map<String, Object> resultStructure(List<Map<String, Object>> claims) {
        Map<String, Object> primary = primaryClaim(claims);
        List<Map<String, Object>> steps = claimSteps(primary);
        if (steps.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("type", "evidence_structure_v1");
        structure.put("claimId", claimId(primary));
        structure.put("concept", String.valueOf(primary.getOrDefault("concept", "")).trim());
        structure.put("summary", String.valueOf(primary.getOrDefault("summary", primary.getOrDefault("text", ""))).trim());
        structure.put("steps", steps);
        return structure;
    }

    private String resultUncertainty(EvidencePathState pathState) {
        return switch (pathState == null ? EvidencePathState.NO_PATH : pathState) {
            case STRONG_PATH -> "";
            case WEAK_PATH -> "\u8bc1\u636e\u8def\u5f84\u7a33\u5b9a\u6027\u4e00\u822c\uff0c\u7ed3\u679c\u5e94\u4f5c\u4e3a\u521d\u6b65\u5224\u65ad\u5e76\u7ed3\u5408\u66f4\u591a\u8d44\u6599\u9a8c\u8bc1\u3002";
            case CONFLICTED_PATH -> "\u5df2\u8bc6\u522b\u5019\u9009\u7ed3\u679c\uff0c\u4f46\u8bc1\u636e\u4e4b\u95f4\u5b58\u5728\u51b2\u7a81\uff0c\u9700\u5148\u5b8c\u6210\u51b2\u7a81\u6838\u9a8c\u3002";
            case NO_PATH -> "\u672a\u627e\u5230\u53ef\u652f\u6491\u7ed3\u679c\u7684\u6700\u5c0f\u8bc1\u636e\u96c6\u3002";
        };
    }

    private Map<String, Object> claimGraph(EvidenceExecutionContract contract, List<Map<String, Object>> claims) {
        Map<String, Object> graph = new LinkedHashMap<>();
        Map<String, Object> primary = primaryClaim(claims);
        String primaryClaimId = claimId(primary);
        graph.put("primaryClaimId", primaryClaimId);
        graph.put("dominanceRule", "highest_confidence_bound_claim");
        graph.put("dominanceScore", primary == null ? 0.0 : claimConfidence(primary));
        graph.put("relations", claimRelations(claims, primaryClaimId));
        graph.put("conflicts", claimConflicts(contract, claims));
        return graph;
    }

    private Map<String, Object> primaryClaim(List<Map<String, Object>> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        Map<String, Object> primary = null;
        for (Map<String, Object> claim : claims) {
            if (primary == null || claimConfidence(claim) > claimConfidence(primary)) {
                primary = claim;
            }
        }
        return primary;
    }

    private List<Map<String, Object>> claimRelations(List<Map<String, Object>> claims, String primaryClaimId) {
        if (claims == null || claims.isEmpty() || primaryClaimId == null || primaryClaimId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> relations = new ArrayList<>();
        for (Map<String, Object> claim : claims) {
            String claimId = claimId(claim);
            if (claimId.isBlank() || primaryClaimId.equals(claimId)) {
                continue;
            }
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("from", claimId);
            relation.put("to", primaryClaimId);
            relation.put("type", "SUPPORTS");
            relation.put("confidence", claimConfidence(claim));
            relations.add(relation);
        }
        return List.copyOf(relations);
    }

    private List<Map<String, Object>> claimConflicts(EvidenceExecutionContract contract, List<Map<String, Object>> claims) {
        if (contract == null || claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (EvidenceExecutionContract.DagEdge edge : contract.executionDag().edges()) {
            if (!"CONTRADICTS".equalsIgnoreCase(edge.type())) {
                continue;
            }
            String fromClaim = claimIdByNode(claims, edge.from());
            String toClaim = claimIdByNode(claims, edge.to());
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("from", fromClaim.isBlank() ? edge.from() : fromClaim);
            conflict.put("to", toClaim.isBlank() ? edge.to() : toClaim);
            conflict.put("type", edge.type());
            conflict.put("confidence", round(edge.confidence()));
            conflict.put("resolutionRule", "prefer_dominant_claim_and_require_review");
            conflict.put("reason", edge.reasoning());
            conflicts.add(conflict);
        }
        return List.copyOf(conflicts);
    }

    private String claimIdByNode(List<Map<String, Object>> claims, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "";
        }
        for (Map<String, Object> claim : claims) {
            if (nodeId.equals(String.valueOf(claim.getOrDefault("nodeId", "")))) {
                return claimId(claim);
            }
        }
        return "";
    }

    private String claimId(Map<String, Object> claim) {
        return claim == null ? "" : String.valueOf(claim.getOrDefault("id", "")).trim();
    }

    private double claimConfidence(Map<String, Object> claim) {
        Object value = claim == null ? null : claim.get("confidence");
        if (value instanceof Number number) {
            return round(number.doubleValue());
        }
        try {
            return round(Double.parseDouble(String.valueOf(value)));
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    private List<Map<String, Object>> boundClaims(EvidenceExecutionContract contract) {
        List<Map<String, Object>> values = new ArrayList<>();
        int index = 1;
        for (EvidenceExecutionContract.DeterministicFact fact : contract.deterministicFacts()) {
            if (fact.content().isBlank() || fact.sourceRef().isBlank()) {
                continue;
            }
            EvidenceStructure structure = extractEvidenceStructure(fact.content(), fact.sourceRef());
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("id", "claim-" + index++);
            claim.put("nodeId", fact.nodeId());
            claim.put("sourceRef", fact.sourceRef());
            claim.put("lockRef", "evidence_execution_lock_v1");
            claim.put("locks", List.of("L1"));
            claim.put("supportWeight", round(fact.confidence()));
            if (structure.hasSteps()) {
                claim.put("type", "structured_evidence_bundle");
                claim.put("concept", structure.concept());
                claim.put("summary", structure.summary());
                claim.put("supportingEvidence", structure.supportingEvidence());
                claim.put("evidenceClaims", structure.evidenceClaims());
                claim.put("steps", structure.stepMaps());
                claim.put("text", structure.summary());
            } else {
                claim.put("type", "atomic_fact");
                claim.put("text", limit(fact.content(), 220));
            }
            claim.put("confidence", round(fact.confidence()));
            values.add(claim);
            if (values.size() >= 3) {
                return List.copyOf(values);
            }
        }
        for (EvidenceExecutionContract.TrustedSqlFact sql : contract.trustedSql()) {
            if (sql.sourceRef().isBlank()) {
                continue;
            }
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("id", "claim-" + index++);
            claim.put("nodeId", sql.nodeId());
            claim.put("sourceRef", sql.sourceRef());
            claim.put("lockRef", "evidence_execution_lock_v1");
            claim.put("locks", List.of("L1"));
            claim.put("supportWeight", round(sql.validationScore()));
            claim.put("text", "可信 SQL 校验" + (sql.executionVerified() ? "通过" : "未通过") + "，校验分 "
                + round(sql.validationScore()) + "。");
            claim.put("confidence", round(sql.validationScore()));
            values.add(claim);
            if (values.size() >= 3) {
                return List.copyOf(values);
            }
        }
        return List.copyOf(values);
    }

    private EvidenceStructure extractEvidenceStructure(String content, String sourceRef) {
        String normalized = limit(content, 2400);
        if (normalized.isBlank()) {
            return EvidenceStructure.empty();
        }
        String concept = deriveConcept(normalized);
        List<String> candidates = structureCandidates(normalized);
        if (candidates.size() < 2) {
            return EvidenceStructure.empty();
        }
        List<StructureStep> steps = new ArrayList<>();
        int index = 1;
        for (String candidate : candidates) {
            String title = cleanStepText(candidate);
            if (title.isBlank()) {
                continue;
            }
            steps.add(new StructureStep(index++, title, List.of(sourceRef)));
            if (steps.size() >= 8) {
                break;
            }
        }
        if (steps.size() < 2) {
            return EvidenceStructure.empty();
        }
        List<Map<String, Object>> evidenceClaims = evidenceClaims(concept, sourceRef, steps);
        return new EvidenceStructure(
            concept,
            structureSummary(concept, steps),
            supportingEvidence(evidenceClaims),
            evidenceClaims,
            List.copyOf(steps)
        );
    }

    private List<String> structureCandidates(String content) {
        List<String> values = new ArrayList<>();
        if (containsAll(content, "livedata", "\u6570\u636e\u7f16\u7ec7")) {
            addCandidateIfPresent(values, content,
                List.of("livedata", "\u6570\u636e\u7f16\u7ec7\u6a21\u5757"),
                "\u8fdb\u5165 livedata \u6570\u636e\u7f16\u7ec7\u6a21\u5757");
            addCandidateIfPresent(values, content,
                List.of("\u7ef4\u62a4", "\u6570\u636e\u6e90"),
                "\u7ef4\u62a4\u9700\u8981\u5206\u6790\u7684\u6570\u636e\u6e90");
            addCandidateIfPresent(values, content,
                List.of("\u6dfb\u52a0", "\u6570\u636e\u76ee\u5f55", "\u6570\u636e\u6e90"),
                "\u6dfb\u52a0\u6570\u636e\u76ee\u5f55\u5e76\u7ef4\u62a4\u7f16\u7ec7\u6570\u636e\u6e90");
            addCandidateIfPresent(values, content,
                List.of("\u6570\u636e\u89c6\u7a97", "\u62a5\u8868SQL"),
                "\u5728\u6570\u636e\u89c6\u7a97\u4e2d\u5f00\u53d1\u62a5\u8868 SQL");
            addCandidateIfPresent(values, content,
                List.of("\u4fdd\u5b58", "SQL", "\u6570\u636e\u96c6"),
                "\u4fdd\u5b58 SQL \u6570\u636e\u96c6\u914d\u7f6e");
            addCandidateIfPresent(values, content,
                List.of("\u6d4b\u8bd5", "\u8fde\u63a5"),
                "\u6d4b\u8bd5\u8fde\u63a5\u5e76\u9884\u89c8\u6570\u636e");
            addCandidateIfPresent(values, content,
                List.of("\u9884\u89c8", "\u62a5\u8868"),
                "\u9884\u89c8\u5e76\u53d1\u5e03\u62a5\u8868");
        }

        String marked = content.replaceAll("(?=\\s*(?:\\d{1,2}[\\.、)]|[一二三四五六七八九十][、.]))", "\n");
        String[] fragments = marked.split("[\\n。；;]");
        for (String fragment : fragments) {
            String step = cleanStepText(fragment);
            if (looksLikeProcessStep(step)) {
                values.add(step);
            }
        }
        return dedupeSteps(values);
    }

    private void addCandidateIfPresent(List<String> values, String content, List<String> requiredTokens, String title) {
        for (String token : requiredTokens) {
            if (!content.contains(token)) {
                return;
            }
        }
        values.add(title);
    }

    private boolean containsAll(String content, String first, String second) {
        return content != null && content.contains(first) && content.contains(second);
    }

    private List<String> dedupeSteps(List<String> values) {
        List<String> deduped = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String value : values) {
            String cleaned = cleanStepText(value);
            if (cleaned.isBlank()) {
                continue;
            }
            String key = cleaned.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", "").toLowerCase();
            boolean duplicate = false;
            for (String existing : keys) {
                if (existing.contains(key) || key.contains(existing)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.add(cleaned);
                keys.add(key);
            }
            if (deduped.size() >= 8) {
                break;
            }
        }
        return List.copyOf(deduped);
    }

    private String cleanStepText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.replaceAll("^\\s*(?:#+\\s*)?", "")
            .replaceAll("^\\s*(?:\\d{1,2}[\\.、)]|[一二三四五六七八九十][、.])\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
        cleaned = cleaned.replaceAll("^[-:：\\s]+", "").trim();
        if (cleaned.length() < 4) {
            return "";
        }
        return limit(cleaned, 80);
    }

    private boolean looksLikeProcessStep(String value) {
        if (value == null || value.length() < 6 || value.length() > 120) {
            return false;
        }
        return value.matches(".*(\u8fdb\u5165|\u7ef4\u62a4|\u9009\u62e9|\u6dfb\u52a0|\u5f00\u53d1|\u4fdd\u5b58|\u914d\u7f6e|\u6d4b\u8bd5|\u8fde\u63a5|\u9884\u89c8|\u53d1\u5e03|\u65b0\u5efa|\u767b\u5f55).*");
    }

    private String deriveConcept(String content) {
        String cleaned = content.replaceAll("^\\s*#+\\s*", "").trim();
        int boundary = firstBoundary(cleaned, List.of(" \u2014 ", " - ", "\uff1a", ":", "\n"));
        if (boundary > 4) {
            return limit(cleaned.substring(0, boundary), 60);
        }
        return limit(cleaned, 60);
    }

    private int firstBoundary(String value, List<String> markers) {
        int result = -1;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index > 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private String structureSummary(String concept, List<StructureStep> steps) {
        String subject = concept == null || concept.isBlank()
            ? "\u5f53\u524d\u6587\u6863"
            : concept;
        if (subject.toLowerCase().contains("livedata")) {
            return subject + "\u662f\u4e00\u4efd\u56f4\u7ed5 livedata \u6570\u636e\u7f16\u7ec7\u5b8c\u6210\u62a5\u8868\u5f00\u53d1\u7684\u8bf4\u660e\u3002";
        }
        List<String> titles = steps.stream()
            .map(StructureStep::title)
            .limit(4)
            .toList();
        return subject + "\u7684\u6838\u5fc3\u5185\u5bb9\u8986\u76d6" + String.join("\u3001", titles) + "\u7b49\u6587\u6863\u8981\u70b9\u3002";
    }

    private List<Map<String, Object>> evidenceClaims(String concept, String sourceRef, List<StructureStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        if (concept != null && concept.toLowerCase().contains("livedata")) {
            values.add(evidenceClaim(
                "\u6587\u6863\u4e3b\u9898\u6307\u5411 livedata \u6570\u636e\u7f16\u7ec7\u62a5\u8868\u5f00\u53d1",
                sourceRef,
                concept
            ));
            values.add(evidenceClaim(
                "\u6587\u6863\u5185\u5bb9\u53ef\u652f\u6491\u201c\u62a5\u8868\u5f00\u53d1\u57fa\u4e8e\u6570\u636e\u6e90\u7ef4\u62a4\u201d\u7684\u5224\u65ad",
                sourceRef,
                firstMatchingStep(steps, "\u6570\u636e\u6e90")
            ));
            values.add(evidenceClaim(
                "\u6587\u6863\u5185\u5bb9\u53ef\u652f\u6491\u201c\u901a\u8fc7\u6570\u636e\u89c6\u7a97\u8fdb\u884c\u62a5\u8868 SQL \u5f00\u53d1\u201d\u7684\u5224\u65ad",
                sourceRef,
                firstMatchingStep(steps, "SQL")
            ));
            values.add(evidenceClaim(
                "\u6587\u6863\u5185\u5bb9\u53ef\u652f\u6491\u201c\u540e\u7eed\u5305\u542b\u6570\u636e\u96c6\u914d\u7f6e\u548c\u62a5\u8868\u9884\u89c8\u53d1\u5e03\u201d\u7684\u5224\u65ad",
                sourceRef,
                firstMatchingStep(steps, "\u9884\u89c8")
            ));
            return List.copyOf(values);
        }
        steps.stream()
            .map(StructureStep::title)
            .limit(4)
            .forEach(title -> values.add(evidenceClaim("\u6587\u6863\u5185\u5bb9\u652f\u6491\uff1a" + title, sourceRef, title)));
        return List.copyOf(values);
    }

    private List<String> supportingEvidence(List<Map<String, Object>> evidenceClaims) {
        if (evidenceClaims == null || evidenceClaims.isEmpty()) {
            return List.of();
        }
        return evidenceClaims.stream()
            .map(item -> String.valueOf(item.getOrDefault("claim", "")).trim())
            .filter(value -> !value.isBlank())
            .toList();
    }

    private String firstMatchingStep(List<StructureStep> steps, String keyword) {
        if (steps == null || keyword == null || keyword.isBlank()) {
            return "";
        }
        return steps.stream()
            .map(StructureStep::title)
            .filter(title -> title.contains(keyword))
            .findFirst()
            .orElse(steps.isEmpty() ? "" : steps.get(0).title());
    }

    private Map<String, Object> evidenceClaim(String claim, String sourceRef, String supportText) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("claim", claim == null ? "" : claim);
        value.put("support", List.of(evidenceSupport(sourceRef, supportText)));
        return value;
    }

    private Map<String, Object> evidenceSupport(String sourceRef, String supportText) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceRef", sourceRef == null ? "" : sourceRef);
        value.put("text", limit(supportText, 120));
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> claimSteps(Map<String, Object> claim) {
        Object steps = claim == null ? null : claim.get("steps");
        if (steps instanceof List<?> list) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    values.add((Map<String, Object>) map);
                }
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> claimSupportingEvidence(Map<String, Object> claim) {
        Object supporting = claim == null ? null : claim.get("supportingEvidence");
        if (supporting instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                String value = String.valueOf(item).trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> claimEvidenceClaims(Map<String, Object> claim) {
        Object evidenceClaims = claim == null ? null : claim.get("evidenceClaims");
        if (evidenceClaims instanceof List<?> list) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    values.add((Map<String, Object>) map);
                }
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    private String supportingEvidenceText(Map<String, Object> claim) {
        List<String> values = claimSupportingEvidence(claim);
        if (values.isEmpty()) {
            return "";
        }
        return String.join("\uff1b", values);
    }

    private record EvidenceStructure(String concept,
                                     String summary,
                                     List<String> supportingEvidence,
                                     List<Map<String, Object>> evidenceClaims,
                                     List<StructureStep> steps) {
        private static EvidenceStructure empty() {
            return new EvidenceStructure("", "", List.of(), List.of(), List.of());
        }

        private boolean hasSteps() {
            return steps != null && steps.size() >= 2;
        }

        private List<Map<String, Object>> stepMaps() {
            return steps == null ? List.of() : steps.stream().map(StructureStep::toMap).toList();
        }
    }

    private record StructureStep(int step, String title, List<String> sourceRefs) {
        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("step", step);
            value.put("title", title);
            value.put("sourceRefs", sourceRefs);
            return value;
        }
    }

    private String claimText(List<Map<String, Object>> claims) {
        if (claims == null || claims.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> claim : claims) {
            String text = String.valueOf(claim.getOrDefault("text", "")).trim();
            if (text.isBlank()) {
                continue;
            }
            values.add("【来源" + index++ + "】" + text);
        }
        return String.join("；", values);
    }

    private double resultConfidence(EvidenceExecutionContract contract) {
        List<String> selectedPath = contract.evidencePath();
        List<EvidenceExecutionContract.DagNode> pathNodes = selectedPath.stream()
            .map(nodeId -> findNode(contract.executionDag().nodes(), nodeId))
            .filter(node -> node != null)
            .toList();
        List<EvidenceExecutionContract.DagEdge> pathEdges = pathEdges(contract.executionDag().edges(), selectedPath);
        double weakestNode = pathNodes.stream()
            .mapToDouble(EvidenceExecutionContract.DagNode::confidence)
            .min()
            .orElse(0.0);
        double weakestEdge = pathEdges.stream()
            .mapToDouble(EvidenceExecutionContract.DagEdge::confidence)
            .min()
            .orElse(pathEdges.isEmpty() ? weakestNode : 0.0);
        double weakestLink = pathEdges.isEmpty()
            ? weakestNode
            : Math.min(weakestNode, weakestEdge);
        double averageEdge = pathEdges.stream()
            .mapToDouble(EvidenceExecutionContract.DagEdge::confidence)
            .average()
            .orElse(0.0);
        return round(clamp(weakestLink * 0.58 + averageEdge * 0.42));
    }

    private String unavailableOrDegradedAnswer(EvidenceExecutionContract contract) {
        if (resultExists(contract)) {
            return resultAnswer(contract);
        }
        if (contract.pathState() == EvidencePathState.WEAK_PATH) {
            return "\u6839\u636e Evidence Execution Contract\uff0c\u5df2\u8bc6\u522b\u5019\u9009\u8bc1\u636e\u8def\u5f84\uff0c\u4f46\u8def\u5f84\u7a33\u5b9a\u6027\u672a\u8fbe\u5230\u751f\u6210\u786e\u5b9a\u7ed3\u8bba\u7684\u9608\u503c\uff0c\u4e0d\u80fd\u4f7f\u7528\u901a\u7528\u77e5\u8bc6\u8865\u5199\u7b54\u6848\u3002"
                + "\n\u7f3a\u53e3\uff1a\u9700\u8981\u66f4\u9ad8\u7a33\u5b9a\u6027\u7684\u8bc1\u636e\u8def\u5f84\u6216\u66f4\u5145\u5206\u7684\u6587\u6863\u4ea4\u53c9\u5370\u8bc1\u3002";
        }
        if (contract.pathState() == EvidencePathState.CONFLICTED_PATH) {
            return "\u6839\u636e Evidence Execution Contract\uff0c\u5df2\u8bc6\u522b\u5019\u9009\u8bc1\u636e\u8def\u5f84\uff0c\u4f46\u8bc1\u636e\u4e4b\u95f4\u5b58\u5728\u51b2\u7a81\uff0c\u5f53\u524d\u4e0d\u80fd\u751f\u6210\u786e\u5b9a\u7ed3\u8bba\u3002"
                + "\n\u7f3a\u53e3\uff1a\u9700\u8981\u89e3\u51b3\u51b2\u7a81\u8bc1\u636e\u6216\u5f15\u5165\u66f4\u9ad8\u4f18\u5148\u7ea7\u7684\u6743\u5a01\u6765\u6e90\u3002";
        }
        return "\u6839\u636e Evidence Execution Contract\uff0c\u5f53\u524d\u672a\u627e\u5230\u8db3\u591f\u8bc1\u636e\u6765\u6e90\uff0c\u4e0d\u80fd\u4f7f\u7528\u901a\u7528\u77e5\u8bc6\u8865\u5199\u7b54\u6848\u3002"
            + "\n\u7f3a\u53e3\uff1a\u9700\u8981\u8865\u5145\u53ef\u89c1\u6587\u6863\u8bc1\u636e\u6216\u91cd\u65b0\u6269\u5927\u88ab\u5141\u8bb8\u7684\u6587\u6863\u96c6\u5408\u3002";
    }

    private String pathDecisionText(EvidencePathState pathState) {
        return switch (pathState == null ? EvidencePathState.NO_PATH : pathState) {
            case STRONG_PATH -> "selected executable evidence path";
            case WEAK_PATH -> "candidate evidence path identified but below decision threshold";
            case CONFLICTED_PATH -> "candidate evidence path identified but conflict resolution is required";
            case NO_PATH -> "no candidate evidence path";
        };
    }

    private EvidenceExecutionContract.DagNode findNode(List<EvidenceExecutionContract.DagNode> nodes, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        for (EvidenceExecutionContract.DagNode node : nodes == null ? List.<EvidenceExecutionContract.DagNode>of() : nodes) {
            if (nodeId.equals(node.id())) {
                return node;
            }
        }
        return null;
    }

    private List<EvidenceExecutionContract.DagEdge> pathEdges(List<EvidenceExecutionContract.DagEdge> edges,
                                                              List<String> selectedPath) {
        if (edges == null || selectedPath == null || selectedPath.size() < 2) {
            return List.of();
        }
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < selectedPath.size() - 1; i++) {
            pairs.add(selectedPath.get(i) + "->" + selectedPath.get(i + 1));
        }
        return edges.stream()
            .filter(edge -> pairs.contains(edge.from() + "->" + edge.to()))
            .filter(edge -> !"CONTRADICTS".equalsIgnoreCase(edge.type()))
            .toList();
    }

    private List<Map<String, Object>> conflictResolutions(List<EvidenceExecutionContract.DagEdge> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return List.of();
        }
        return conflicts.stream()
            .map(edge -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("edge", edge.from() + " -> " + edge.to());
                item.put("type", edge.type());
                item.put("confidence", round(edge.confidence()));
                item.put("decision", "prefer higher-ranked evidence and exclude conflict edge from selected path");
                item.put("reason", edge.reasoning());
                return item;
            })
            .toList();
    }

    private List<String> explanation(EvidenceExecutionContract contract,
                                     double pathCoherence,
                                     double weakestLink,
                                     List<EvidenceExecutionContract.DagEdge> conflicts) {
        List<String> values = new ArrayList<>();
        if (contract.pathState() == EvidencePathState.STRONG_PATH) {
            values.add("Selected path is executable under Evidence Execution Contract.");
        } else if (contract.pathState() == EvidencePathState.WEAK_PATH) {
            values.add("Candidate evidence path exists but did not satisfy the decision threshold.");
        } else if (contract.pathState() == EvidencePathState.CONFLICTED_PATH) {
            values.add("Candidate evidence path exists but contains unresolved contradictory evidence.");
        } else {
            values.add("No candidate evidence path was available under Evidence Execution Contract.");
        }
        values.add("Path coherence=" + round(pathCoherence) + " with weakestLink=" + round(weakestLink) + ".");
        if (conflicts != null && !conflicts.isEmpty()) {
            values.add("Detected " + conflicts.size() + " conflicting edge(s); conflict evidence is explained separately and excluded from the selected path.");
        } else {
            values.add("No contradiction edge participated in the selected path.");
        }
        return List.copyOf(values);
    }

    private String limit(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
