package com.chatchat.agents.evidence;

import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class DeterministicAnswerCompiler {

    public static final String LOCK_HEADER = "Deterministic answer lock";
    public static final String BEGIN_LOCKED_ANSWER = "---BEGIN_LOCKED_ANSWER---";
    public static final String END_LOCKED_ANSWER = "---END_LOCKED_ANSWER---";
    public static final String REASONING_PROTOCOL_VERSION = "evidence_reasoning_protocol_v1";

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
        List<String> sourceRefs = validSourceRefs(contract.sourceRefs());
        if (!sourceRefs.isEmpty()) {
            builder.append("sourceRefs: ").append(String.join(", ", sourceRefs)).append('\n');
        }
        if (!contract.sqlLineage().isEmpty()) {
            builder.append("sqlLineage: ").append(String.join(", ", contract.sqlLineage())).append('\n');
        }
        builder.append("reasoningPayload:\n")
            .append("```json\n")
            .append(reasoningJson(contract))
            .append("\n```\n");
        builder.append("lockedAnswer:\n")
            .append(BEGIN_LOCKED_ANSWER)
            .append('\n')
            .append(presentationAnswer(contract).trim())
            .append('\n')
            .append(END_LOCKED_ANSWER);
        return builder.toString();
    }

    private String presentationAnswer(EvidenceExecutionContract contract) {
        if (contract.decision() != EvidenceExecutionDecision.ANSWER_ALLOWED || !contract.executable()) {
            return unavailableOrDegradedAnswer(contract);
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Based on the executed evidence graph path, the answer can be derived from the following traceable evidence.");
        List<String> sourceRefs = validSourceRefs(contract.sourceRefs());
        if (!sourceRefs.isEmpty()) {
            answer.append("\n\nSource references: ")
                .append(String.join("; ", sourceRefs))
                .append(".");
        }
        if (!contract.deterministicFacts().isEmpty()) {
            answer.append("\n\nEvidence facts:");
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
            answer.append("\n\nTrusted SQL / fact lineage:");
            for (int i = 0; i < contract.trustedSql().size(); i++) {
                EvidenceExecutionContract.TrustedSqlFact sql = contract.trustedSql().get(i);
                answer.append("\n").append(i + 1).append(". ");
                if (sql.sourceRef() != null && !sql.sourceRef().isBlank()) {
                    answer.append("Source ").append(sql.sourceRef()).append(": ");
                }
                answer.append("type ").append(sql.sqlType()).append("; ")
                    .append("execution verification ").append(sql.executionVerified() ? "passed" : "not passed").append("; ")
                    .append("validation score ").append(round(sql.validationScore())).append(".");
                if (!sql.tables().isEmpty()) {
                    answer.append(" Table lineage: ").append(String.join(", ", sql.tables())).append(".");
                }
                answer.append("\nSQL:\n").append(sql.normalizedSql());
            }
        }
        answer.append("\n\nExecution constraint: this answer uses only paths, sources, and trusted SQL from the Evidence Execution Contract, with no evidence introduced from outside the contract.");
        return answer.toString();
    }

    private String reasoningJson(EvidenceExecutionContract contract) {
        try {
            return ModelProtocolJson.pretty(reasoningPayload(contract));
        } catch (RuntimeException ex) {
            return ModelProtocolJson.compact(new EvidenceReasoningErrorPayload(
                "evidence_reasoning_v2",
                limit(ex.getMessage(), 160)
            ));
        }
    }

    private EvidenceReasoningPayload reasoningPayload(EvidenceExecutionContract contract) {
        EvidenceReasoningPayloadBody body = new EvidenceReasoningPayloadBody(
            "evidence_reasoning_v2",
            REASONING_PROTOCOL_VERSION,
            contract.contractVersion(),
            contract.pathState(),
            contract.contractHash(),
            contract.graphViewHash(),
            contract.decision(),
            contract.fromGraphOnly(),
            contract.executable(),
            result(contract),
            contract.executionSpec(),
            contract.evidence(),
            contract.executionDag(),
            contract.trustedSql(),
            contract.deterministicFacts(),
            reasoningTrace(contract)
        );
        return new EvidenceReasoningPayload(
            body.type(),
            body.protocolVersion(),
            body.contractVersion(),
            ModelProtocolJson.sha256Hex(body),
            body.pathState(),
            body.contractHash(),
            body.graphViewHash(),
            body.decision(),
            body.fromGraphOnly(),
            body.executable(),
            body.result(),
            body.executionSpec(),
            body.evidence(),
            body.executionDag(),
            body.trustedSql(),
            body.deterministicFacts(),
            body.reasoningTrace()
        );
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
        EvidenceConclusion evidenceConclusion = exists ? aggregateEvidenceConclusion(contract) : EvidenceConclusion.empty();
        String conclusion = exists ? firstNonBlank(evidenceConclusion.conclusion(), resultConclusion(contract, claims)) : "";
        String evidenceSummary = exists ? firstNonBlank(joinEvidence(evidenceConclusion.supportingEvidence()), resultEvidenceSummary(claims)) : "";
        String uncertainty = exists ? resultUncertainty(contract.pathState()) : "";
        Map<String, Object> structure = exists ? resultStructure(claims) : Map.of();
        List<String> supportingEvidence = exists && !evidenceConclusion.supportingEvidence().isEmpty()
            ? evidenceConclusion.supportingEvidence()
            : resultSupportingEvidence(claims);
        List<Map<String, Object>> evidenceClaims = exists && !evidenceConclusion.evidenceClaims().isEmpty()
            ? evidenceConclusion.evidenceClaims()
            : resultEvidenceClaims(claims);
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
        value.put("answer", exists ? resultAnswer(contract, claims, conclusion, evidenceSummary, supportingEvidence, uncertainty) : "");
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
        List<String> supportingEvidence = resultSupportingEvidence(claims);
        String uncertainty = resultUncertainty(contract.pathState());
        return resultAnswer(contract, claims, conclusion, evidenceSummary, supportingEvidence, uncertainty);
    }

    private String resultAnswer(EvidenceExecutionContract contract,
                                List<Map<String, Object>> claims,
                                String conclusion,
                                String evidenceSummary,
                                List<String> supportingEvidence,
                                String uncertainty) {
        String evidenceText = evidenceSummary == null || evidenceSummary.isBlank() ? claimText(claims) : evidenceSummary;
        if (conclusion != null && !conclusion.isBlank()) {
            StringBuilder builder = new StringBuilder();
            builder.append("Conclusion: ").append(conclusion);
            String supportingText = supportingEvidence != null && !supportingEvidence.isEmpty()
                ? joinEvidence(supportingEvidence)
                : supportingEvidenceText(primaryClaim(claims));
            if (!supportingText.isBlank()) {
                builder.append("\nSupporting evidence: ").append(supportingText);
            }
            if (uncertainty != null && !uncertainty.isBlank()) {
                builder.append("\nUncertainty: ").append(uncertainty);
            }
            return builder.toString();
        }
        if (contract.pathState() == EvidencePathState.STRONG_PATH) {
            return evidenceText.isBlank() ? "A traceable deterministic conclusion has been formed." : evidenceText;
        }
        if (contract.pathState() == EvidencePathState.WEAK_PATH) {
            return evidenceText.isBlank()
                ? "Preliminary judgment: the current evidence supports a candidate result, but its confidence is moderate. Add more evidence before using it for decisions."
                : "Preliminary judgment: " + evidenceText + " The result has moderate confidence and should be verified with additional evidence.";
        }
        if (contract.pathState() == EvidencePathState.CONFLICTED_PATH) {
            return evidenceText.isBlank()
                ? "A candidate result exists, but the evidence is conflicted and must be reviewed first."
                : "Candidate judgment: " + evidenceText + " However, the evidence is conflicted and must be reviewed first.";
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
            case CONFLICTED_PATH -> "Candidate judgment: " + conclusion;
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

    private EvidenceConclusion aggregateEvidenceConclusion(EvidenceExecutionContract contract) {
        if (contract == null) {
            return EvidenceConclusion.empty();
        }
        List<TopicEvidence> topics = extractTopicEvidence(contract);
        if (topics.size() < 2) {
            return EvidenceConclusion.empty();
        }
        List<Map<String, Object>> evidenceClaims = topics.stream()
            .limit(5)
            .map(topic -> evidenceClaim(
                "The document content contains material related to \"" + topic.title() + "\"",
                topic.sourceRef(),
                topic.supportText()
            ))
            .toList();
        String conclusion = "Based on the retrieved documents, the relevant content mainly covers "
            + String.join(", ", topics.stream().limit(4).map(TopicEvidence::title).toList())
            + ".";
        return new EvidenceConclusion(conclusion, supportingEvidence(evidenceClaims), List.copyOf(evidenceClaims));
    }

    private List<TopicEvidence> extractTopicEvidence(EvidenceExecutionContract contract) {
        List<TopicEvidence> topics = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (EvidenceExecutionContract.EvidenceItem item : allEvidenceItems(contract)) {
            for (String title : topicCandidates(item.text())) {
                String key = topicKey(title);
                if (key.isBlank() || keys.contains(key)) {
                    continue;
                }
                topics.add(new TopicEvidence(title, item.refId(), supportSnippet(item.text(), List.of(title))));
                keys.add(key);
                if (topics.size() >= 6) {
                    return List.copyOf(topics);
                }
            }
        }
        return List.copyOf(topics);
    }

    private List<String> topicCandidates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> topics = new ArrayList<>();
        java.util.regex.Matcher comment = java.util.regex.Pattern.compile("--\\s*([^;\\n\\r]+)").matcher(text);
        while (comment.find()) {
            addTopic(topics, cleanEvidenceTopic(comment.group(1)));
        }
        java.util.regex.Matcher numbered = java.util.regex.Pattern
            .compile("(?:^|\\s)(?:\\d{1,2}[\\x{3001}\\.)]|(?:\\x{4E00}|\\x{4E8C}|\\x{4E09}|\\x{56DB}|\\x{4E94}|\\x{516D}|\\x{4E03}|\\x{516B}|\\x{4E5D}|\\x{5341})+[\\x{3001}\\.])\\s*([^;\\x{3002}\\n\\r]{4,80})")
            .matcher(text);
        while (numbered.find()) {
            addTopic(topics, cleanEvidenceTopic(numbered.group(1)));
        }
        java.util.regex.Matcher markdown = java.util.regex.Pattern.compile("(?m)^\\s*#{1,4}\\s+([^\\n\\r]{4,80})").matcher(text);
        while (markdown.find()) {
            addTopic(topics, cleanEvidenceTopic(markdown.group(1)));
        }
        return List.copyOf(topics);
    }

    private void addTopic(List<String> topics, String candidate) {
        if (candidate.isBlank()) {
            return;
        }
        String key = topicKey(candidate);
        boolean exists = topics.stream().map(this::topicKey).anyMatch(key::equals);
        if (!exists) {
            topics.add(candidate);
        }
    }

    private String cleanEvidenceTopic(String value) {
        String topic = cleanSqlTopic(value);
        topic = topic.replaceAll("^[\\-:\\x{FF1A}\\x{3001}\\s]+", "").trim();
        topic = topic.replaceAll("\\s+", " ").trim();
        if (topic.length() < 4 || topic.length() > 60) {
            return "";
        }
        String lower = topic.toLowerCase();
        if (lower.matches("^(select|from|where|left join|inner join|order by|group by)\\b.*")) {
            return "";
        }
        if (topic.chars().filter(ch -> ch == '_').count() >= 3) {
            return "";
        }
        return topic;
    }

    private String topicKey(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", "").toLowerCase();
    }

    private EvidenceExecutionContract.EvidenceItem findEvidenceItem(EvidenceExecutionContract contract, List<String> markers) {
        for (EvidenceExecutionContract.EvidenceItem item : allEvidenceItems(contract)) {
            String lower = item.text() == null ? "" : item.text().toLowerCase();
            for (String marker : markers) {
                if (marker != null && !marker.isBlank() && lower.contains(marker.toLowerCase())) {
                    return item;
                }
            }
        }
        return null;
    }

    private List<EvidenceExecutionContract.EvidenceItem> allEvidenceItems(EvidenceExecutionContract contract) {
        List<EvidenceExecutionContract.EvidenceItem> items = new ArrayList<>();
        if (contract.evidence() != null) {
            items.addAll(contract.evidence().direct());
            items.addAll(contract.evidence().supporting());
            items.addAll(contract.evidence().context());
        }
        return List.copyOf(items);
    }

    private String supportSnippet(String text, List<String> markers) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase();
        for (String marker : markers) {
            if (marker == null || marker.isBlank()) {
                continue;
            }
            int index = lower.indexOf(marker.toLowerCase());
            if (index >= 0) {
                int start = Math.max(0, index - 35);
                int end = Math.min(normalized.length(), index + marker.length() + 85);
                return limit(normalized.substring(start, end), 140);
            }
        }
        return limit(normalized, 140);
    }

    private List<String> resultSupportingEvidence(List<Map<String, Object>> claims) {
        Map<String, Object> primary = primaryClaim(claims);
        List<String> values = claimSupportingEvidence(primary);
        if (!values.isEmpty()) {
            return values;
        }
        return List.of();
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
            case WEAK_PATH -> "The evidence path has moderate stability, so the result should be treated as preliminary and verified with additional evidence.";
            case CONFLICTED_PATH -> "A candidate result was identified, but the evidence is conflicted and must be reviewed first.";
            case NO_PATH -> "No minimal evidence set was found to support a result.";
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
            String summary = trustedSqlSummary(sql);
            List<Map<String, Object>> evidenceClaims = trustedSqlEvidenceClaims(sql, summary);
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("id", "claim-" + index++);
            claim.put("nodeId", sql.nodeId());
            claim.put("sourceRef", sql.sourceRef());
            claim.put("lockRef", "evidence_execution_lock_v1");
            claim.put("locks", List.of("L1"));
            claim.put("supportWeight", round(sql.validationScore()));
            claim.put("type", "trusted_sql_evidence_bundle");
            claim.put("summary", summary);
            claim.put("supportingEvidence", supportingEvidence(evidenceClaims));
            claim.put("evidenceClaims", evidenceClaims);
            claim.put("text", summary);
            claim.put("confidence", round(sql.validationScore()));
            values.add(claim);
            if (values.size() >= 3) {
                return List.copyOf(values);
            }
        }
        return List.copyOf(values);
    }

    private String trustedSqlSummary(EvidenceExecutionContract.TrustedSqlFact sql) {
        List<String> topics = sqlTopics(sql.normalizedSql());
        String tableText = sql.tables().isEmpty()
            ? ""
            : ", involving tables such as " + String.join(", ", sql.tables().stream().limit(3).toList());
        if (!topics.isEmpty()) {
            return "This content is " + readableSqlType(sql.sqlType()) + " SQL related to "
                + String.join(", ", topics.stream().limit(3).toList()) + tableText + ".";
        }
        if (!sql.tables().isEmpty()) {
            return "This content is " + readableSqlType(sql.sqlType()) + " SQL"
                + tableText + ", used to support data queries or validation notes.";
        }
        return "This content is " + readableSqlType(sql.sqlType()) + " SQL that has entered the trusted validation chain.";
    }

    private List<Map<String, Object>> trustedSqlEvidenceClaims(EvidenceExecutionContract.TrustedSqlFact sql, String summary) {
        List<Map<String, Object>> values = new ArrayList<>();
        List<String> topics = sqlTopics(sql.normalizedSql());
        if (!summary.isBlank()) {
            values.add(evidenceClaim(summary, sql.sourceRef(), sqlEvidenceSnippet(sql, topics)));
        }
        if (!topics.isEmpty()) {
            values.add(evidenceClaim(
                "SQL comments or titles in the document fragment support the business topic of this content",
                sql.sourceRef(),
                String.join(", ", topics.stream().limit(4).toList())
            ));
        }
        if (!sql.tables().isEmpty()) {
            values.add(evidenceClaim(
                "Table lineage shows that this SQL depends on data tables such as ADS/DWD for query or validation",
                sql.sourceRef(),
                String.join(", ", sql.tables().stream().limit(5).toList())
            ));
        }
        values.add(evidenceClaim(
            "The validation chain supports using this SQL as evidence for the conclusion",
            sql.sourceRef(),
            "type " + readableSqlType(sql.sqlType()) + "; execution verification "
                + (sql.executionVerified() ? "passed" : "not passed")
                + "; validation score " + round(sql.validationScore())
        ));
        return List.copyOf(values);
    }

    private String sqlEvidenceSnippet(EvidenceExecutionContract.TrustedSqlFact sql, List<String> topics) {
        if (topics != null && !topics.isEmpty()) {
            return String.join(", ", topics.stream().limit(4).toList());
        }
        if (!sql.tables().isEmpty()) {
            return String.join(", ", sql.tables().stream().limit(4).toList());
        }
        return limit(sql.normalizedSql(), 120);
    }

    private List<String> sqlTopics(String normalizedSql) {
        if (normalizedSql == null || normalizedSql.isBlank()) {
            return List.of();
        }
        List<String> topics = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("--\\s*([^;\\n\\r]+)").matcher(normalizedSql);
        while (matcher.find()) {
            String topic = cleanSqlTopic(matcher.group(1));
            if (!topic.isBlank()) {
                topics.add(limit(topic, 36));
            }
            if (topics.size() >= 5) {
                break;
            }
        }
        return dedupeText(topics);
    }

    private String cleanSqlTopic(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String topic = value.replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher sqlStart = java.util.regex.Pattern
            .compile("(?i)\\s+(select|from|where|left\\s+join|inner\\s+join|order\\s+by|group\\s+by)\\b")
            .matcher(topic);
        if (sqlStart.find()) {
            topic = topic.substring(0, sqlStart.start()).trim();
        }
        topic = topic.replaceAll("(?i)^(select|from|where)\\b.*", "").trim();
        topic = topic.replaceAll("^[\\-:\\x{FF1A}\\x{3001}\\s]+", "").trim();
        return topic;
    }

    private List<String> dedupeText(List<String> values) {
        List<String> deduped = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            String cleaned = value == null ? "" : value.trim();
            if (cleaned.isBlank() || deduped.contains(cleaned)) {
                continue;
            }
            deduped.add(cleaned);
        }
        return List.copyOf(deduped);
    }

    private List<String> validSourceRefs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .filter(value -> value.matches(".*[\\p{L}\\p{N}].*"))
            .distinct()
            .toList();
    }

    private String readableSqlType(String sqlType) {
        String value = sqlType == null || sqlType.isBlank() ? "SQL" : sqlType.toUpperCase();
        return switch (value) {
            case "SELECT" -> "query";
            case "INSERT" -> "insert";
            case "UPDATE" -> "update";
            case "DELETE" -> "delete";
            default -> value;
        };
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
        String marked = content.replaceAll("(?=\\s*(?:\\d{1,2}[\\.\\x{3001})]|(?:\\x{4E00}|\\x{4E8C}|\\x{4E09}|\\x{56DB}|\\x{4E94}|\\x{516D}|\\x{4E03}|\\x{516B}|\\x{4E5D}|\\x{5341})[\\x{3001}\\.]))", "\n");
        String[] fragments = marked.split("[\\n\\x{3002}\\x{FF1B};]");
        for (String fragment : fragments) {
            String step = cleanStepText(fragment);
            if (looksLikeProcessStep(step)) {
                values.add(step);
            }
        }
        return dedupeSteps(values);
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
            .replaceAll("^\\s*(?:\\d{1,2}[\\.\\x{3001})]|(?:\\x{4E00}|\\x{4E8C}|\\x{4E09}|\\x{56DB}|\\x{4E94}|\\x{516D}|\\x{4E03}|\\x{516B}|\\x{4E5D}|\\x{5341})[\\x{3001}\\.])\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
        cleaned = cleaned.replaceAll("^[-:\\x{FF1A}\\x{3001}\\s]+", "").trim();
        if (cleaned.length() < 4) {
            return "";
        }
        return limit(cleaned, 80);
    }

    private boolean looksLikeProcessStep(String value) {
        if (value == null || value.length() < 6 || value.length() > 120) {
            return false;
        }
        return value.matches("(?i).*(enter|maintain|select|add|develop|save|configure|test|connect|preview|publish|create|login|validate|query|review).*")
            || value.matches(".*\\p{IsHan}{2,}.*");
    }

    private String deriveConcept(String content) {
        String cleaned = content.replaceAll("^\\s*#+\\s*", "").trim();
        int boundary = firstBoundary(cleaned, List.of(" -- ", " - ", ":", "\n"));
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
            ? "current document"
            : concept;
        List<String> titles = steps.stream()
            .map(StructureStep::title)
            .limit(4)
            .toList();
        return "The core content of " + subject + " covers document points such as " + String.join(", ", titles) + ".";
    }

    private List<Map<String, Object>> evidenceClaims(String concept, String sourceRef, List<StructureStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        steps.stream()
            .map(StructureStep::title)
            .limit(4)
            .forEach(title -> values.add(evidenceClaim("Document content supports: " + title, sourceRef, title)));
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
        return String.join("; ", values);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private String joinEvidence(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("; ", values.stream()
            .filter(value -> value != null && !value.isBlank())
            .toList());
    }

    private record EvidenceReasoningPayload(
        String type,
        String protocolVersion,
        String contractVersion,
        String protocolHash,
        EvidencePathState pathState,
        String contractHash,
        String graphViewHash,
        EvidenceExecutionDecision decision,
        boolean fromGraphOnly,
        boolean executable,
        Map<String, Object> result,
        EvidenceExecutionContract.ExecutionSpec executionSpec,
        EvidenceExecutionContract.EvidenceTiers evidence,
        EvidenceExecutionContract.ExecutionDag executionDag,
        List<EvidenceExecutionContract.TrustedSqlFact> trustedSql,
        List<EvidenceExecutionContract.DeterministicFact> deterministicFacts,
        Map<String, Object> reasoningTrace
    ) {
    }

    private record EvidenceReasoningPayloadBody(
        String type,
        String protocolVersion,
        String contractVersion,
        EvidencePathState pathState,
        String contractHash,
        String graphViewHash,
        EvidenceExecutionDecision decision,
        boolean fromGraphOnly,
        boolean executable,
        Map<String, Object> result,
        EvidenceExecutionContract.ExecutionSpec executionSpec,
        EvidenceExecutionContract.EvidenceTiers evidence,
        EvidenceExecutionContract.ExecutionDag executionDag,
        List<EvidenceExecutionContract.TrustedSqlFact> trustedSql,
        List<EvidenceExecutionContract.DeterministicFact> deterministicFacts,
        Map<String, Object> reasoningTrace
    ) {
    }

    private record EvidenceReasoningErrorPayload(String type, String serializationError) {
    }

    private record EvidenceConclusion(String conclusion,
                                      List<String> supportingEvidence,
                                      List<Map<String, Object>> evidenceClaims) {
        private static EvidenceConclusion empty() {
            return new EvidenceConclusion("", List.of(), List.of());
        }
    }

    private record TopicEvidence(String title, String sourceRef, String supportText) {
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
            values.add("[Source " + index++ + "] " + text);
        }
        return String.join("; ", values);
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
            return "According to the Evidence Execution Contract, a candidate evidence path was identified, but its stability is below the threshold for a deterministic conclusion. General knowledge must not be used to fill the answer."
                + "\nGap: a more stable evidence path or stronger cross-document corroboration is required.";
        }
        if (contract.pathState() == EvidencePathState.CONFLICTED_PATH) {
            return "According to the Evidence Execution Contract, a candidate evidence path was identified, but the evidence is conflicted and no deterministic conclusion can be generated yet."
                + "\nGap: conflicting evidence must be resolved or a higher-priority authoritative source must be introduced.";
        }
        return "According to the Evidence Execution Contract, there are not enough evidence sources to answer, and general knowledge must not be used to fill the answer."
            + "\nGap: visible document evidence must be added or the allowed document set must be expanded.";
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
