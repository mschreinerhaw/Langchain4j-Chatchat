package com.chatchat.agents.orchestration;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses the active model to score final-answer candidates before the write decision is made.
 */
@Slf4j
class AnswerQualityEvaluator {

    static final String CONTRACT_VERSION = "answer_quality_evaluation_v1";
    static final String CANDIDATE = "candidate";
    static final String REVIEWER_SUGGESTION = "reviewer_suggestion";
    static final String DETERMINISTIC_EVIDENCE = "deterministic_evidence";
    static final String DOCUMENT_EVIDENCE = "document_evidence";
    static final String SUMMARY_STAGE = "summary_stage";
    static final String QUALITY_SYNTHESIS = "quality_synthesis";

    private final ObjectMapper objectMapper;

    AnswerQualityEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    QualityReport evaluate(ChatModel chatModel, QualityRequest request) {
        List<AnswerCandidate> candidates = uniqueCandidates(request == null ? List.of() : request.candidates());
        if (chatModel == null || candidates.isEmpty()) {
            return QualityReport.unavailable("quality_model_unavailable", candidates);
        }
        if (candidates.size() == 1) {
            return QualityReport.singleCandidate(candidates.get(0));
        }
        String prompt = buildPrompt(request, candidates);
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=answer_quality modelClass={} candidateCount={} promptChars={}",
            chatModel.getClass().getName(),
            candidates.size(),
            prompt.length());
        String raw = chatModel.chat(prompt);
        log.info("agentModelResponse phase=answer_quality durationMs={} responseChars={}",
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=answer_quality raw=\n{}", ModelProtocolJson.prettyJsonForLog(raw));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            String llmSelectedId = stringValue(firstObject(payload, "selectedId", "selected_id", "bestCandidateId", "best_candidate_id", "preferredId", "preferred_id"));
            String reason = firstNonBlank(
                stringValue(firstObject(payload, "reason", "rationale", "selectionReason", "selection_reason")),
                "Model scored answer candidates."
            );
            List<AnswerCandidate> evaluatedCandidates = new ArrayList<>(candidates);
            String synthesizedAnswer = stringValue(firstObject(
                payload, "synthesizedAnswer", "synthesized_answer", "recommendedAnswer", "recommended_answer"
            ));
            if (synthesizedAnswer != null && !synthesizedAnswer.isBlank()
                && evaluatedCandidates.stream().noneMatch(candidate ->
                normalize(candidate.answer()).equals(normalize(synthesizedAnswer)))) {
                evaluatedCandidates.add(new AnswerCandidate(
                    QUALITY_SYNTHESIS,
                    QUALITY_SYNTHESIS,
                    synthesizedAnswer.trim()
                ));
            }
            List<CandidateScore> scores = parseScores(
                firstObject(payload, "candidates", "scores", "candidateScores"),
                evaluatedCandidates
            );
            return new QualityReport(
                true,
                CONTRACT_VERSION,
                llmSelectedId,
                List.copyOf(evaluatedCandidates),
                scores,
                reason,
                preview(raw, 1200)
            );
        } catch (Exception ex) {
            log.debug("Failed to parse answer quality evaluation: {}", raw, ex);
            return QualityReport.unavailable("quality_model_parse_failed", candidates);
        }
    }

    private String buildPrompt(QualityRequest request, List<AnswerCandidate> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the final answer quality evaluator for an enterprise AI assistant.\n");
        prompt.append("Evaluate answer candidates against the user request and available observations.\n");
        prompt.append("Do not decide the final answer. Java code will apply hard filters and deterministic weighted aggregation.\n");
        prompt.append("Score every candidate independently. Prefer answers that are correct, directly useful, complete, and grounded in observations. Reward user-visible citations only when the user explicitly requested sources or the supplied contract requires them.\n");
        prompt.append("Do not reward unsupported extra facts. Flag answers that contradict observations, omit explicitly required citations, use failed-tool evidence, violate the response schema, or are unsafe.\n");
        prompt.append("For analysis requests, strongly prefer candidates that analyze concrete returned values and explain their meaning. Treat answers dominated by evidence chains, source lists, API paths, tool-call chronology, verification commands, coverage bookkeeping, or repeated limitations as incomplete and not useful unless the user explicitly requested those details. Internal citation markers do not improve usefulness.\n");
        prompt.append("Penalize candidates that impose analytical dimensions, comparisons, or a report template not established by the user's request, returned schema and values, or supplied analysisContext. Prefer analysis derived directly from the actual returned business data.\n");
        prompt.append("Penalize candidates that make source, citation, trust, tool, or execution metadata the analysis subject when the user requested analysis of returned data.\n");
        prompt.append("Penalize candidates that infer normal completion, health, causality, or absence of failure from counter equality, execution success, or status coexistence without explicit supporting returned fields.\n");
        prompt.append("When candidates have complementary strengths, produce synthesizedAnswer as the best user-facing Markdown answer. "
            + "It must retain the strongest supported analysis, correct contradictions, incorporate useful reviewer feedback, and use no fact absent from observations. "
            + "If synthesis cannot improve the candidates safely, return an empty synthesizedAnswer.\n");
        prompt.append("Return strict JSON only, no Markdown.\n\n");
        if (request != null && request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            prompt.append("System instruction:\n").append(request.systemPrompt()).append("\n\n");
        }
        prompt.append("User request:\n").append(request == null ? "" : firstNonBlank(request.query(), "")).append("\n\n");
        prompt.append("Available observations:\n");
        List<String> observations = request == null || request.observations() == null ? List.of() : request.observations();
        if (observations.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            observations.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> prompt.append("- ").append(value).append("\n"));
        }
        prompt.append("\nAnswer candidates:\n");
        for (AnswerCandidate candidate : candidates) {
            prompt.append("[Candidate ").append(candidate.id()).append("]\n");
            prompt.append("source: ").append(candidate.source()).append("\n");
            prompt.append("answer:\n").append(candidate.answer()).append("\n\n");
        }
        if (request != null && request.reviewFeedback() != null && !request.reviewFeedback().isBlank()) {
            prompt.append("Reviewer feedback on the current candidate (diagnostic, not evidence):\n")
                .append(request.reviewFeedback())
                .append("\n\n");
        }
        prompt.append("Scoring rubric, each score from 0.0 to 1.0:\n");
        prompt.append("- accuracy: factual consistency with observations and no unsupported claims.\n");
        prompt.append("- grounding: uses only available evidence when evidence is required.\n");
        prompt.append("- completeness: directly answers all important parts of the user request with actual data findings; operational metadata alone is incomplete for an analysis request.\n");
        prompt.append("- citation: preserves citations near claims only when explicitly required; otherwise score clean, internally grounded presentation fully.\n");
        prompt.append("- usefulness: clear, actionable, analysis-first user-facing quality; do not reward internal evidence or execution narration.\n\n");
        prompt.append("Hard flags are booleans. Set them true only when the problem is present:\n");
        prompt.append("- contradictsObservation: candidate conflicts with available observations.\n");
        prompt.append("- usesFailedToolEvidence: candidate relies on a failed or unavailable tool result as fact.\n");
        prompt.append("- missingRequiredCitation: the user or contract explicitly requires citations and they are omitted near factual claims. Mere citation availability does not make them user-visible requirements.\n");
        prompt.append("- schemaViolation: candidate violates explicit output/schema requirements.\n");
        prompt.append("- unsafe: candidate is unsafe or policy-inappropriate.\n\n");
        prompt.append("JSON schema:\n");
        prompt.append("{\"preferredId\":\"optional-audit-only-id\",\"reason\":\"brief scoring rationale\","
            + "\"synthesizedAnswer\":\"best evidence-grounded Markdown answer or empty string\","
            + "\"candidates\":[{\"id\":\"candidate-id or quality_synthesis\",\"score\":0.0,\"accuracy\":0.0,"
            + "\"grounding\":0.0,\"completeness\":0.0,\"citation\":0.0,\"usefulness\":0.0,"
            + "\"contradictsObservation\":false,\"usesFailedToolEvidence\":false,"
            + "\"missingRequiredCitation\":false,\"schemaViolation\":false,\"unsafe\":false,"
            + "\"issues\":[\"brief issue\"]}]}\n");
        return prompt.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private List<AnswerCandidate> uniqueCandidates(List<AnswerCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AnswerCandidate> unique = new ArrayList<>();
        Set<String> seenAnswers = new LinkedHashSet<>();
        for (AnswerCandidate candidate : candidates) {
            if (candidate == null || candidate.answer() == null || candidate.answer().isBlank()) {
                continue;
            }
            String normalized = candidate.answer().replaceAll("\\s+", " ").trim();
            if (seenAnswers.add(normalized)) {
                unique.add(candidate);
            }
        }
        return List.copyOf(unique);
    }

    @SuppressWarnings("unchecked")
    private List<CandidateScore> parseScores(Object value, List<AnswerCandidate> candidates) {
        if (!(value instanceof List<?> list)) {
            return defaultScores(candidates);
        }
        List<CandidateScore> scores = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> payload = (Map<String, Object>) map;
            String id = stringValue(firstObject(payload, "id", "candidateId", "candidate_id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            scores.add(new CandidateScore(
                id,
                bounded(firstNumber(payload, "score", "overall", "overallScore")),
                bounded(firstNumber(payload, "accuracy", "accuracyScore")),
                bounded(firstNumber(payload, "grounding", "groundingScore")),
                bounded(firstNumber(payload, "completeness", "completenessScore")),
                bounded(firstNumber(payload, "citation", "citationScore")),
                bounded(firstNumber(payload, "usefulness", "usefulnessScore")),
                bool(firstObject(payload, "contradictsObservation", "contradicts_observation", "contradiction")),
                bool(firstObject(payload, "usesFailedToolEvidence", "uses_failed_tool_evidence", "failedToolEvidence")),
                bool(firstObject(payload, "missingRequiredCitation", "missing_required_citation", "missingCitation")),
                bool(firstObject(payload, "schemaViolation", "schema_violation")),
                bool(firstObject(payload, "unsafe", "safetyViolation", "safety_violation")),
                stringList(firstObject(payload, "issues", "problems", "risks"))
            ));
        }
        return scores.isEmpty() ? defaultScores(candidates) : List.copyOf(scores);
    }

    private List<CandidateScore> defaultScores(List<AnswerCandidate> candidates) {
        return candidates.stream()
            .map(candidate -> new CandidateScore(
                candidate.id(),
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                false,
                false,
                false,
                false,
                false,
                List.of()
            ))
            .toList();
    }

    private Object firstObject(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Double firstNumber(Map<String, Object> values, String... keys) {
        Object value = firstObject(values, keys);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double bounded(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String text = raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String preview(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    record QualityRequest(
        String query,
        String systemPrompt,
        List<String> observations,
        String reviewFeedback,
        List<AnswerCandidate> candidates
    ) {
    }

    record AnswerCandidate(
        String id,
        String source,
        String answer
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("source", source);
            String normalized = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
            values.put("answerPreview",
                normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000));
            return values;
        }
    }

    record CandidateScore(
        String id,
        double score,
        double accuracy,
        double grounding,
        double completeness,
        double citation,
        double usefulness,
        boolean contradictsObservation,
        boolean usesFailedToolEvidence,
        boolean missingRequiredCitation,
        boolean schemaViolation,
        boolean unsafe,
        List<String> issues
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("score", score);
            values.put("accuracy", accuracy);
            values.put("grounding", grounding);
            values.put("completeness", completeness);
            values.put("citation", citation);
            values.put("usefulness", usefulness);
            values.put("contradictsObservation", contradictsObservation);
            values.put("usesFailedToolEvidence", usesFailedToolEvidence);
            values.put("missingRequiredCitation", missingRequiredCitation);
            values.put("schemaViolation", schemaViolation);
            values.put("unsafe", unsafe);
            values.put("issues", issues == null ? List.of() : issues);
            return values;
        }
    }

    record QualityReport(
        boolean available,
        String contractVersion,
        String llmSelectedId,
        List<AnswerCandidate> candidates,
        List<CandidateScore> scores,
        String reason,
        String rawPreview
    ) {
        static QualityReport unavailable(String reason, List<AnswerCandidate> candidates) {
            return new QualityReport(
                false,
                CONTRACT_VERSION,
                null,
                candidates == null ? List.of() : List.copyOf(candidates),
                List.of(),
                reason,
                null
            );
        }

        static QualityReport singleCandidate(AnswerCandidate candidate) {
            CandidateScore score = new CandidateScore(
                candidate.id(),
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                false,
                false,
                false,
                false,
                false,
                List.of()
            );
            return new QualityReport(
                true,
                CONTRACT_VERSION,
                candidate.id(),
                List.of(candidate),
                List.of(score),
                "Only one answer candidate was available.",
                null
            );
        }

        List<Map<String, Object>> scoreMaps() {
            return scores == null ? List.of() : scores.stream().map(CandidateScore::toMap).toList();
        }

        List<Map<String, Object>> candidateMaps() {
            return candidates == null ? List.of() : candidates.stream().map(AnswerCandidate::toMap).toList();
        }

        AnswerCandidate candidateById(String id) {
            if (id == null || id.isBlank() || candidates == null) {
                return null;
            }
            return candidates.stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElse(null);
        }
    }
}
