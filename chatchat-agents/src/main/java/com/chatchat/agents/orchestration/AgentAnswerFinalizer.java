package com.chatchat.agents.orchestration;

import com.chatchat.agents.evidence.AnswerAssemblyEngine;
import com.chatchat.agents.evidence.AnswerAssemblyPolicy;
import com.chatchat.agents.evidence.DeterministicAnswerCompiler;
import com.chatchat.agents.evidence.EvidenceAnswerGroundingGuard;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds final agent answers and preserves the legacy execution result contract.
 */
@Slf4j
class AgentAnswerFinalizer {

    private static final String DOCUMENT_EVIDENCE_CONTRACT = "document_evidence_v1";
    private static final String UNIFIED_EVIDENCE_CONTRACT = "evidence_v1";
    private static final String EVIDENCE_ANSWER_CONTRACT = "evidence_answer_v1";
    private static final String EXECUTION_CONTRACT = "evidence_execution_contract_v2_2";
    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "根据当前文档证据不足，无法确认。";
    private static final Pattern DOCUMENT_REF_PATTERN =
        Pattern.compile("doc://([^\\s\"',;\\]\\)}]+)#chunk=([^\\s\"',;\\]\\)}]+)");
    private static final Pattern WEB_REF_PATTERN =
        Pattern.compile("web://([^\\s\"',;\\]\\)}]+)#result=([^\\s\"',;\\]\\)}]+)");

    private final AgentAnswerReviewer answerReviewer;
    private final AgentRuntimeGuard runtimeGuard;
    private final EvidenceAnswerGroundingGuard groundingGuard = new EvidenceAnswerGroundingGuard();
    private final AnswerAssemblyEngine answerAssemblyEngine = new AnswerAssemblyEngine();
    private final AnswerDecisionEngine answerDecisionEngine = new AnswerDecisionEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerQualityEvaluator answerQualityEvaluator = new AnswerQualityEvaluator(objectMapper);

    AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer, AgentRuntimeGuard runtimeGuard) {
        this.answerReviewer = answerReviewer;
        this.runtimeGuard = runtimeGuard;
    }

    AgentOrchestrator.AgentExecutionResult finishExecution(String answer,
                                                           List<InteractionToolTrace> traces,
                                                           Map<String, Object> metadata,
                                                           List<String> observations) {
        return finishWithDecision(answer, null, null, null, traces, metadata, observations);
    }

    private AgentOrchestrator.AgentExecutionResult finishWithDecision(String candidateAnswer,
                                                                      AgentAnswerReview review,
                                                                      AnswerDecisionEngine.EvidenceSignal evidenceSignal,
                                                                      AnswerQualityEvaluator.QualityReport qualityReport,
                                                                      List<InteractionToolTrace> traces,
                                                                      Map<String, Object> metadata,
                                                                      List<String> observations) {
        Map<String, Object> values = metadata == null ? new LinkedHashMap<>() : metadata;
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal == null
            ? evidenceSignal(candidateAnswer, observations, values)
            : evidenceSignal;
        AnswerDecisionEngine.AnswerDecision decision = answerDecisionEngine.decide(
            new AnswerDecisionEngine.AnswerDecisionRequest(
                candidateAnswer,
                review,
                signal,
                qualityReport,
                values
            )
        );
        values.putAll(decision.metadata());
        String answerBeforeSqlMetadataMerge = decision.finalAnswer();
        String mergedAnswer = mergeStructuredSqlMetadataAnswer(
            structuredSqlMetadataMarkdown(values),
            answerBeforeSqlMetadataMerge
        );
        if (!mergedAnswer.equals(answerBeforeSqlMetadataMerge == null ? "" : answerBeforeSqlMetadataMerge)) {
            values.put("structuredSqlMetadataMergedInFinalizer", true);
            values.put("structuredSqlMetadataMergeReason", "semantic_gate_passed_preserve_column_metadata");
            values.put("finalAnswerPreview", shortText(mergedAnswer, 1000));
        }
        String finalAnswer = sanitizeFinalMarkdown(mergedAnswer);
        if (!finalAnswer.equals(mergedAnswer)) {
            values.put("finalAnswerSanitized", true);
            values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        }
        logAnswerDecision(decision, values);
        values.put("runtimeContractVersion", "agent_runtime_v1");
        values.put("observations", observations == null ? List.of() : List.copyOf(observations));
        values.put("toolTraceCount", traces == null ? 0 : traces.size());
        attachAnswerAssemblyPolicy(values, observations);
        attachEvidenceAnswerContract(finalAnswer, values, observations);
        return new AgentOrchestrator.AgentExecutionResult(
            finalAnswer,
            traces == null ? List.of() : List.copyOf(traces),
            values
        );
    }

    boolean markToolBudgetExceeded(String requestedToolName,
                                   int maxToolCalls,
                                   List<InteractionToolTrace> traces,
                                   Map<String, Object> metadata,
                                   List<String> observations) {
        if (maxToolCalls == Integer.MAX_VALUE || traces == null || traces.size() < maxToolCalls) {
            return false;
        }
        metadata.put("stopReason", "tool_budget_exceeded");
        metadata.put("toolBudgetExceeded", true);
        metadata.put("maxToolCalls", maxToolCalls);
        metadata.put("requestedToolAfterBudget", requestedToolName);
        observations.add("Agent run stopped before executing " + requestedToolName
            + " because the max tool calls budget was reached.");
        return true;
    }

    AgentOrchestrator.AgentExecutionResult finishBudgetedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck) {
        runtimeGuard.checkCancelled(cancellationCheck);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", "tool_budget_exceeded");
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, observations, metadata);
        AnswerQualityEvaluator.QualityReport quality = evaluateAnswerQuality(
            activeChatModel,
            query,
            systemPrompt,
            observations,
            finalAnswer,
            review,
            signal
        );
        return finishWithDecision(finalAnswer, review, signal, quality, traces, metadata, observations);
    }

    AgentOrchestrator.AgentExecutionResult finishReviewedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck,
                                                                 String stopReason) {
        runtimeGuard.checkCancelled(cancellationCheck);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, observations, metadata);
        AnswerQualityEvaluator.QualityReport quality = evaluateAnswerQuality(
            activeChatModel,
            query,
            systemPrompt,
            observations,
            finalAnswer,
            review,
            signal
        );
        return finishWithDecision(finalAnswer, review, signal, quality, traces, metadata, observations);
    }

    AgentOrchestrator.AgentExecutionResult finishReviewedAnswer(ChatModel activeChatModel,
                                                                String query,
                                                                String systemPrompt,
                                                                List<InteractionToolTrace> traces,
                                                                Map<String, Object> metadata,
                                                                List<String> observations,
                                                                String answer,
                                                                BooleanSupplier cancellationCheck,
                                                                String stopReason) {
        String finalAnswer = safeAnswer(activeChatModel, answer, query, observations, systemPrompt, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, observations, metadata);
        AnswerQualityEvaluator.QualityReport quality = evaluateAnswerQuality(
            activeChatModel,
            query,
            systemPrompt,
            observations,
            finalAnswer,
            review,
            signal
        );
        return finishWithDecision(finalAnswer, review, signal, quality, traces, metadata, observations);
    }

    private AnswerQualityEvaluator.QualityReport evaluateAnswerQuality(ChatModel activeChatModel,
                                                                       String query,
                                                                       String systemPrompt,
                                                                       List<String> observations,
                                                                       String candidateAnswer,
                                                                       AgentAnswerReview review,
                                                                       AnswerDecisionEngine.EvidenceSignal signal) {
        List<AnswerQualityEvaluator.AnswerCandidate> candidates = answerCandidates(candidateAnswer, review, signal);
        if (candidates.size() <= 1) {
            return null;
        }
        return answerQualityEvaluator.evaluate(
            activeChatModel,
            new AnswerQualityEvaluator.QualityRequest(
                query,
                systemPrompt,
                observations == null ? List.of() : List.copyOf(observations),
                candidates
            )
        );
    }

    private List<AnswerQualityEvaluator.AnswerCandidate> answerCandidates(String candidateAnswer,
                                                                          AgentAnswerReview review,
                                                                          AnswerDecisionEngine.EvidenceSignal signal) {
        List<AnswerQualityEvaluator.AnswerCandidate> candidates = new ArrayList<>();
        if (candidateAnswer != null && !candidateAnswer.isBlank()) {
            candidates.add(new AnswerQualityEvaluator.AnswerCandidate(
                AnswerQualityEvaluator.CANDIDATE,
                AnswerQualityEvaluator.CANDIDATE,
                candidateAnswer
            ));
        }
        if (review != null
            && AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null
            && !review.answer().isBlank()) {
            candidates.add(new AnswerQualityEvaluator.AnswerCandidate(
                AnswerQualityEvaluator.REVIEWER_SUGGESTION,
                AnswerQualityEvaluator.REVIEWER_SUGGESTION,
                review.answer()
            ));
        }
        if (signal != null && signal.shouldReplaceWithGroundedEvidence()) {
            if (signal.lockedAnswer() != null
                && signal.lockedAnswer().answer() != null
                && !signal.lockedAnswer().answer().isBlank()) {
                candidates.add(new AnswerQualityEvaluator.AnswerCandidate(
                    AnswerQualityEvaluator.DETERMINISTIC_EVIDENCE,
                    AnswerQualityEvaluator.DETERMINISTIC_EVIDENCE,
                    signal.lockedAnswer().answer()
                ));
            }
            if (signal.groundedDocumentAnswer() != null && !signal.groundedDocumentAnswer().isBlank()) {
                candidates.add(new AnswerQualityEvaluator.AnswerCandidate(
                    AnswerQualityEvaluator.DOCUMENT_EVIDENCE,
                    AnswerQualityEvaluator.DOCUMENT_EVIDENCE,
                    signal.groundedDocumentAnswer()
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private String safeAnswer(ChatModel activeChatModel,
                              String answer,
                              String query,
                              List<String> observations,
                              String systemPrompt,
                              Map<String, Object> metadata) {
        if (answer != null && !answer.isBlank()) {
            return sanitizeFinalMarkdown(answer);
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
    }

    private String summarizeWithObservations(ChatModel activeChatModel,
                                             String query,
                                             String systemPrompt,
                                             List<String> observations,
                                             Map<String, Object> metadata) {
        if (activeChatModel == null) {
            if (metadata != null) {
                metadata.put("summarySkipped", "chat_model_unavailable");
            }
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("Use the observations below and answer the user question in Chinese, like a high-quality ChatGPT answer.\n");
        prompt.append("Return a polished Markdown document, not a single plain paragraph. Use concise headings and lists when they improve readability.\n");
        prompt.append("Do not wrap the Markdown in code fences and do not output JSON.\n");
        prompt.append("First understand the user's intent, then synthesize the evidence into a clear explanation instead of copying tool output or internal execution reports.\n");
        prompt.append("For document QA, use natural Chinese section titles such as phenomenon summary, key evidence, troubleshooting steps, fix suggestions, and risk notes when they are relevant.\n");
        prompt.append("Use SQL snippets and document citations as support, but do not let raw SQL or chunk titles replace the actual explanation.\n");
        prompt.append("If any tool observation reports failure, explicitly state that this source was unavailable and do not treat it as evidence.\n");
        prompt.append("If observations include evidence_v1 Unified evidence context, use only those EvidenceChunk entries as grounded evidence and keep the matching citation near every claim that relies on that evidence.\n");
        prompt.append("When both internal document and web search observations are available, separate internal document evidence from web verification evidence and explain conflicts instead of merging them silently.\n");
        prompt.append("If observations include document_evidence_v1, document evidence context, or document citations, keep the matching document citation near every claim that relies on that evidence.\n");
        prompt.append("If observations include evidence_v1 or document_evidence_v1, use the evidence to write Markdown; do not emit an EvidenceAnswer object.\n");
        prompt.append("If observations include evidence_execution_contract_v2_2 Deterministic answer lock, treat lockedAnswer and reasoningPayload as grounded evidence constraints, not as text to copy verbatim.\n");
        prompt.append("If observations include web citation labels, append the matching label immediately after every sentence that relies on that web source.\n");
        prompt.append("Do not invent citations or cite URLs that are not listed in the observations.\n");
        prompt.append("If an Evidence trust policy asks for more evidence, avoid strong claims and say that trusted evidence is insufficient.\n");
        String structuredSqlMetadata = structuredSqlMetadataMarkdown(metadata);
        if (!structuredSqlMetadata.isBlank()) {
            prompt.append("Authoritative SQL metadata evidence is available below. Preserve the field list/types/comments in the answer and do not claim table columns or structure are missing.\n")
                .append(structuredSqlMetadata)
                .append("\n\n");
        }
        if (containsEvidence(observations == null ? List.of() : observations)) {
            AnswerAssemblyPolicy assemblyPolicy = answerAssemblyEngine.plan(observations);
            prompt.append(answerAssemblyEngine.promptInstructions(assemblyPolicy)).append("\n");
        }
        if (observations == null || observations.isEmpty()) {
            prompt.append("No external tool observation is available.\n");
        } else {
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
        }
        prompt.append("\nUser question: ").append(query);
        String promptText = prompt.toString();
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=summary runId={} modelClass={} promptChars={} observationCount={}",
            firstNonBlank(runId, ""),
            activeChatModel == null ? null : activeChatModel.getClass().getName(),
            promptText.length(),
            observations == null ? 0 : observations.size());
        String answer = activeChatModel.chat(promptText);
        log.info("agentModelResponse phase=summary runId={} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        log.info("agentModelOutput phase=summary runId={} answer=\n{}",
            firstNonBlank(runId, ""),
            ModelProtocolJson.prettyJsonForLog(answer));
        return sanitizeFinalMarkdown(answer);
    }

    private String sanitizeFinalMarkdown(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String text = answer.trim();
        String locked = extractBetween(
            text,
            DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER,
            DeterministicAnswerCompiler.END_LOCKED_ANSWER
        );
        if (locked != null && !locked.isBlank()) {
            text = locked.trim();
        }
        text = stripOuterFenceIfPresent(text, "json");
        String jsonAnswer = extractUserAnswerFromJson(text);
        if (jsonAnswer != null && !jsonAnswer.isBlank()) {
            text = jsonAnswer.trim();
        } else if (looksLikeJson(text)) {
            text = "已完成分析，但模型返回的是内部调试 JSON，已隐藏原始结构化内容。";
        }
        text = text.replaceAll("(?is)reasoningPayload:\\s*```json\\s*.*?\\s*```", "").trim();
        text = text.replaceAll("(?is)```json\\s*.*?\\s*```", "").trim();
        text = text.replaceAll("(?im)^\\s*(reasoningPayload|executionDag|reasoningTrace|trustedSql|deterministicFacts)\\s*:\\s*$", "").trim();
        text = text.replace(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER, "")
            .replace(DeterministicAnswerCompiler.END_LOCKED_ANSWER, "")
            .trim();
        if (text.startsWith("```markdown")) {
            text = stripOuterFence(text, "```markdown");
        } else if (text.startsWith("```md")) {
            text = stripOuterFence(text, "```md");
        }
        return text.trim();
    }

    @SuppressWarnings("unchecked")
    private String extractUserAnswerFromJson(String text) {
        if (!looksLikeJson(text)) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(text, new TypeReference<>() {
            });
            Object uiResponse = payload.get("uiResponse");
            if (uiResponse instanceof Map<?, ?> uiMap) {
                String answer = stringValue(((Map<String, Object>) uiMap).get("answer"));
                String citations = citationsText(((Map<String, Object>) uiMap).get("citations"));
                if (answer != null && !answer.isBlank()) {
                    return appendCitations(answer, citations);
                }
            }
            String answer = stringValue(payload.get("answer"));
            String citations = citationsText(payload.get("citations"));
            if (answer != null && !answer.isBlank()) {
                return appendCitations(answer, citations);
            }
            String summary = stringValue(payload.get("evidenceSummary"));
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String appendCitations(String answer, String citations) {
        if (citations == null || citations.isBlank()) {
            return answer;
        }
        if (answer.contains(citations)) {
            return answer;
        }
        return answer.trim() + "\n\n引用：" + citations;
    }

    private String citationsText(Object value) {
        if (!(value instanceof List<?> citations) || citations.isEmpty()) {
            return "";
        }
        List<String> refs = new ArrayList<>();
        for (Object item : citations) {
            if (item instanceof Map<?, ?> map) {
                String ref = firstNonBlank(
                    stringValue(map.get("sourceRef")),
                    firstNonBlank(stringValue(map.get("refId")), stringValue(map.get("citation")))
                );
                if (ref != null && !ref.isBlank()) {
                    refs.add(ref);
                }
            } else if (item != null) {
                refs.add(String.valueOf(item));
            }
        }
        return refs.stream()
            .filter(ref -> ref != null && !ref.isBlank())
            .distinct()
            .toList()
            .stream()
            .reduce((left, right) -> left + "；" + right)
            .orElse("");
    }

    private boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private String stripOuterFenceIfPresent(String text, String language) {
        if (text == null || language == null) {
            return text;
        }
        String trimmed = text.trim();
        String openingFence = "```" + language;
        if (trimmed.regionMatches(true, 0, openingFence, 0, openingFence.length())) {
            return stripOuterFence(trimmed, trimmed.substring(0, openingFence.length()));
        }
        return text;
    }

    private String stripOuterFence(String text, String openingFence) {
        int start = openingFence.length();
        int end = text.lastIndexOf("```");
        if (end <= start) {
            return text;
        }
        return text.substring(start, end).trim();
    }

    private String extractBetween(String text, String beginMarker, String endMarker) {
        if (text == null || beginMarker == null || endMarker == null) {
            return null;
        }
        int begin = text.indexOf(beginMarker);
        int end = text.indexOf(endMarker);
        if (begin < 0 || end <= begin) {
            return null;
        }
        return text.substring(begin + beginMarker.length(), end);
    }

    private AgentAnswerReview reviewAnswer(ChatModel activeChatModel,
                                           String query,
                                           String systemPrompt,
                                           List<String> observations,
                                           String finalAnswer,
                                           Map<String, Object> metadata) {
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        if (structuredSqlMetadataSemanticGatePassed(metadata)) {
            if (metadata != null) {
                metadata.put("answerReviewSkipped", true);
                metadata.put("answerReviewSkippedReason", "sql_metadata_and_execution_graph_semantic_gates_passed");
            }
            log.info("agentModelSkipped phase=review runId={} reason=sql_metadata_and_execution_graph_semantic_gates_passed answerChars={} observationCount={}",
                firstNonBlank(runId, ""),
                finalAnswer == null ? 0 : finalAnswer.length(),
                observations == null ? 0 : observations.size());
            return new AgentAnswerReview(
                AgentAnswerReview.ACCEPTED,
                finalAnswer == null ? "" : finalAnswer,
                "SQL metadata and execution graph semantic gates passed; reviewer rewrite skipped."
            );
        }
        if (finalAnswer == null || finalAnswer.isBlank() || activeChatModel == null) {
            log.info("agentModelSkipped phase=review runId={} reason={} answerChars={} observationCount={}",
                firstNonBlank(runId, ""),
                activeChatModel == null ? "chat_model_unavailable" : "empty_answer",
                finalAnswer == null ? 0 : finalAnswer.length(),
                observations == null ? 0 : observations.size());
            return answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        }
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=review runId={} modelClass={} answerChars={} observationCount={}",
            firstNonBlank(runId, ""),
            activeChatModel == null ? null : activeChatModel.getClass().getName(),
            finalAnswer == null ? 0 : finalAnswer.length(),
            observations == null ? 0 : observations.size());
        AgentAnswerReview review = answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        log.info("agentModelResponse phase=review runId={} durationMs={} status={} answerChars={}",
            firstNonBlank(runId, ""),
            System.currentTimeMillis() - startedAt,
            review == null ? null : review.status(),
            review == null || review.answer() == null ? 0 : review.answer().length());
        log.info("agentModelOutput phase=review runId={} status={} answer=\n{}",
            firstNonBlank(runId, ""),
            review == null ? null : review.status(),
            ModelProtocolJson.prettyJsonForLog(review == null ? null : review.answer()));
        if (review != null && AgentAnswerReview.REJECTED.equals(review.status())) {
            log.warn("agentModelReviewRejected runId={} feedback={}",
                firstNonBlank(runId, ""),
                review.feedback());
        }
        return review;
    }

    private boolean structuredSqlMetadataSemanticGatePassed(Map<String, Object> metadata) {
        return Boolean.TRUE.equals(metadata == null ? null : metadata.get("sqlMetadataSemanticGatePassed"))
            && Boolean.TRUE.equals(metadata == null ? null : metadata.get("executionGraphSemanticPassed"));
    }

    private String structuredSqlMetadataMarkdown(Map<String, Object> metadata) {
        if (metadata == null || !structuredSqlMetadataSemanticGatePassed(metadata)) {
            return "";
        }
        Object value = metadata.get("structuredSqlMetadataMarkdown");
        if (value == null) {
            return "";
        }
        String markdown = String.valueOf(value).trim();
        return markdown.isBlank() ? "" : markdown;
    }

    private String mergeStructuredSqlMetadataAnswer(String structuredSqlMetadata, String modelAnswer) {
        String answer = modelAnswer == null ? "" : modelAnswer.trim();
        if (structuredSqlMetadata == null || structuredSqlMetadata.isBlank()) {
            return answer;
        }
        if (containsStructuredSqlMetadataAnswer(answer)) {
            return answer;
        }
        if (answer.isBlank()) {
            return structuredSqlMetadata.trim();
        }
        return structuredSqlMetadata.trim() + "\n\n## \u5206\u6790\u7ed3\u8bba\n\n" + answer;
    }

    private boolean containsStructuredSqlMetadataAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return answer.contains("## \u5143\u6570\u636e\u4f9d\u636e")
            && answer.contains("## \u5b57\u6bb5\u7ed3\u6784")
            || (answer.contains("| # |") && answer.contains("|---:|") && answer.contains("`"));
    }

    private void recordAnswerReview(Map<String, Object> metadata, AgentAnswerReview review) {
        if (metadata == null || review == null) {
            return;
        }
        metadata.put("answerReviewStatus", review.status());
        if (review.feedback() != null && !review.feedback().isBlank()) {
            metadata.put("answerReviewFeedback", review.feedback());
        }
        if (AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null
            && !review.answer().isBlank()) {
            metadata.put("answerReviewRewriteSuggested", true);
            metadata.put("answerReviewSuggestedAnswerPreview", shortText(review.answer(), 1000));
        }
    }

    private void logAnswerDecision(AnswerDecisionEngine.AnswerDecision decision, Map<String, Object> metadata) {
        if (decision == null || AnswerDecisionEngine.NO_REWRITE.equals(decision.action())) {
            return;
        }
        log.warn("agentAnswerDecision action={} source={} reason={} finalAnswerPreview={}",
            decision.action(),
            decision.rewriteSource(),
            decision.reason(),
            metadata == null ? null : metadata.get("finalAnswerPreview"));
    }

    private void attachEvidenceAnswerContract(String answer,
                                              Map<String, Object> metadata,
                                              List<String> observations) {
        List<String> safeObservations = observations == null ? List.of() : observations;
        if (!groundingGuard.containsEvidence(safeObservations)) {
            return;
        }
        EvidenceAnswerGroundingGuard.GroundingResult result = groundingGuard.guard(answer, safeObservations);
        metadata.put("answerContractVersion", result.contractVersion());
        metadata.put("evidenceAnswer", result.evidenceAnswer().toMap());
        metadata.put("availableEvidenceCitations", result.availableCitations());
        metadata.put("groundingStatus", result.groundingStatus());
    }

    private void attachAnswerAssemblyPolicy(Map<String, Object> metadata, List<String> observations) {
        if (metadata == null) {
            return;
        }
        if (!containsEvidence(observations == null ? List.of() : observations)) {
            return;
        }
        AnswerAssemblyPolicy policy = answerAssemblyEngine.plan(observations);
        metadata.put("answerAssemblyPolicy", policy.toMap());
        metadata.put("answerAssemblyMode", policy.mode().name());
        if (!policy.missingInfo().isEmpty()) {
            metadata.put("answerAssemblyMissingInfo", policy.missingInfo());
        }
    }

    private boolean containsEvidence(List<String> observations) {
        return observations.stream()
            .filter(value -> value != null)
            .anyMatch(value -> value.contains(UNIFIED_EVIDENCE_CONTRACT)
                || value.contains(DOCUMENT_EVIDENCE_CONTRACT)
                || value.contains(EXECUTION_CONTRACT)
                || value.contains("doc://")
                || value.contains("web://"));
    }

    private AnswerDecisionEngine.EvidenceSignal evidenceSignal(String answer,
                                                               List<String> observations,
                                                               Map<String, Object> metadata) {
        if (Boolean.TRUE.equals(metadata == null ? null : metadata.get("confirmationRequired"))) {
            return new AnswerDecisionEngine.EvidenceSignal(
                true,
                null,
                null,
                List.of(),
                null,
                false,
                null
            );
        }
        AnswerDecisionEngine.DeterministicLockedAnswer lockedAnswer = extractDeterministicLockedAnswer(observations);
        if (lockedAnswer != null && lockedAnswer.answer() != null && !lockedAnswer.answer().isBlank()) {
            return new AnswerDecisionEngine.EvidenceSignal(
                false,
                EXECUTION_CONTRACT,
                lockedAnswer,
                List.of(),
                null,
                shouldReplaceWithGroundedEvidence(answer),
                null
            );
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> documentEvidence = extractGroundedDocumentEvidence(observations);
        if (documentEvidence.isEmpty()) {
            return AnswerDecisionEngine.EvidenceSignal.empty();
        }
        boolean shouldReplace = shouldReplaceWithGroundedEvidence(answer);
        return new AnswerDecisionEngine.EvidenceSignal(
            false,
            null,
            null,
            documentEvidence,
            groundedEvidenceAnswer(documentEvidence),
            shouldReplace,
            shouldReplace
                ? (answer == null || answer.isBlank() ? "empty_answer_with_document_evidence" : "no_match_fallback_with_document_evidence")
                : null
        );
    }

    private AnswerDecisionEngine.DeterministicLockedAnswer extractDeterministicLockedAnswer(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        for (String observation : observations) {
            AnswerDecisionEngine.DeterministicLockedAnswer lockedAnswer = extractDeterministicLockedAnswer(observation);
            if (lockedAnswer != null) {
                return lockedAnswer;
            }
        }
        return null;
    }

    private AnswerDecisionEngine.DeterministicLockedAnswer extractDeterministicLockedAnswer(String observation) {
        if (observation == null || observation.isBlank() || !observation.contains(EXECUTION_CONTRACT)) {
            return null;
        }
        int begin = observation.indexOf(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER);
        int end = observation.indexOf(DeterministicAnswerCompiler.END_LOCKED_ANSWER);
        if (begin < 0 || end <= begin) {
            return null;
        }
        int answerStart = begin + DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER.length();
        String lockedAnswer = observation.substring(answerStart, end).trim();
        if (lockedAnswer.isBlank()) {
            return null;
        }
        return new AnswerDecisionEngine.DeterministicLockedAnswer(
            lockedAnswer,
            extractLineValue(observation, "contractHash:"),
            extractLineValue(observation, "graphViewHash:")
        );
    }

    private String extractLineValue(String text, String prefix) {
        if (text == null || prefix == null) {
            return null;
        }
        for (String rawLine : text.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private boolean shouldReplaceWithGroundedEvidence(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        if (answer.contains("doc://") || answer.contains("web://")) {
            return false;
        }
        String normalized = answer.toLowerCase();
        return normalized.contains("\u672a\u80fd")
            || normalized.contains("\u672a\u627e\u5230")
            || normalized.contains("\u6ca1\u6709\u627e\u5230")
            || normalized.contains("\u672a\u68c0\u7d22\u5230")
            || normalized.contains("\u672a\u5339\u914d")
            || normalized.contains("\u65e0\u76f8\u5173")
            || normalized.contains("\u4fe1\u606f\u7f3a\u5931")
            || normalized.contains("\u8bc1\u636e\u4e0d\u8db3")
            || normalized.contains("\u65e0\u6cd5\u786e\u8ba4")
            || normalized.contains("not found")
            || normalized.contains("no relevant")
            || normalized.contains("no evidence")
            || normalized.contains("unable to find")
            || normalized.contains("insufficient evidence");
    }

    private String groundedEvidenceAnswer(List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence) {
        StringBuilder answer = new StringBuilder();
        answer.append("\u6839\u636e\u5df2\u68c0\u7d22\u5230\u7684\u6587\u6863\u8bc1\u636e\uff0c\u5f53\u524d\u95ee\u9898\u4e0d\u5e94\u5224\u5b9a\u4e3a\u672a\u547d\u4e2d\u3002\u53ef\u7528\u8bc1\u636e\u5982\u4e0b\uff1a\n\n");
        int limit = Math.min(5, evidence.size());
        for (int i = 0; i < limit; i++) {
            AnswerDecisionEngine.GroundedDocumentEvidence item = evidence.get(i);
            answer.append(i + 1).append(". ");
            if (item.source() != null && !item.source().isBlank()) {
                answer.append("\u6765\u6e90\uff1a").append(item.source()).append("\u3002");
            }
            if (item.section() != null && !item.section().isBlank()) {
                answer.append("\u7ae0\u8282\uff1a").append(item.section()).append("\u3002");
            }
            answer.append(item.content());
            if (item.citation() != null && !item.citation().isBlank()) {
                answer.append(" ").append(item.citation());
            }
            answer.append("\n");
        }
        answer.append("\n\u7ed3\u8bba\uff1a\u8bf7\u57fa\u4e8e\u4e0a\u8ff0\u8bc1\u636e\u56de\u7b54\uff1b\u5982\u679c\u9700\u8981\u8865\u5145\u201c\u65b9\u6848\u76ee\u6807\u3001\u5b8c\u6574\u6d41\u7a0b\u3001\u5f02\u5e38\u5904\u7406\u201d\u7b49\u7ec6\u8282\uff0c\u53ea\u80fd\u9488\u5bf9\u8bc1\u636e\u672a\u8986\u76d6\u7684\u90e8\u5206\u6807\u6ce8\u7f3a\u53e3\u3002");
        return answer.toString();
    }

    private List<AnswerDecisionEngine.GroundedDocumentEvidence> extractGroundedDocumentEvidence(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        for (String observation : observations) {
            evidence.addAll(extractGroundedDocumentEvidence(observation));
        }
        return evidence.stream()
            .filter(item -> item.content() != null && !item.content().isBlank())
            .limit(8)
            .toList();
    }

    private List<AnswerDecisionEngine.GroundedDocumentEvidence> extractGroundedDocumentEvidence(String observation) {
        if (observation == null || observation.isBlank()
            || (!observation.contains("doc://") && !observation.contains(DOCUMENT_EVIDENCE_CONTRACT)
            && !observation.contains(UNIFIED_EVIDENCE_CONTRACT))) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        GroundedDocumentEvidenceBuilder current = null;
        boolean capturingContent = false;
        for (String rawLine : observation.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("[Evidence ")) {
                addGroundedEvidence(evidence, current);
                current = new GroundedDocumentEvidenceBuilder();
                capturingContent = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("type:")) {
                current.type = line.substring("type:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("citation:")) {
                current.citation = line.substring("citation:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("source:")) {
                current.source = line.substring("source:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("section:")) {
                current.section = line.substring("section:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("content:")) {
                capturingContent = true;
            } else if (capturingContent) {
                if (isEvidenceContextBoundary(line)) {
                    capturingContent = false;
                } else if (!line.isBlank()) {
                    if (!current.content.isEmpty()) {
                        current.content.append(' ');
                    }
                    current.content.append(shortText(line, 420));
                }
            }
        }
        addGroundedEvidence(evidence, current);
        return evidence;
    }

    private void addGroundedEvidence(List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence,
                                     GroundedDocumentEvidenceBuilder current) {
        if (current == null) {
            return;
        }
        String type = current.type == null ? "" : current.type.trim();
        String citation = current.citation == null ? "" : current.citation.trim();
        if (!"DOCUMENT".equalsIgnoreCase(type) && !citation.startsWith("doc://")) {
            return;
        }
        String content = current.content.toString().trim();
        if (content.isBlank()) {
            return;
        }
        evidence.add(new AnswerDecisionEngine.GroundedDocumentEvidence(
            blankToNull(citation),
            blankToNull(current.source),
            blankToNull(current.section),
            content
        ));
    }

    private boolean isEvidenceContextBoundary(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.startsWith("[Evidence ")
            || line.startsWith("Evidence audit:")
            || line.startsWith("Document search summary:")
            || line.startsWith("Document evidence snippets:")
            || line.startsWith("Citation rule:");
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<Map<String, Object>> extractCitationMaps(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = DOCUMENT_REF_PATTERN.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        while (matcher.find()) {
            String fileId = matcher.group(1);
            String chunkValue = matcher.group(2);
            String refId = "doc://" + fileId + "#chunk=" + chunkValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "DOCUMENT");
            citation.put("fileId", fileId);
            citation.put("fileName", null);
            citation.put("section", null);
            citation.put("chunkIndex", parseChunkIndex(chunkValue));
            citations.add(citation);
        }
        Matcher webMatcher = WEB_REF_PATTERN.matcher(text);
        while (webMatcher.find()) {
            String source = webMatcher.group(1);
            String resultValue = webMatcher.group(2);
            String refId = "web://" + source + "#result=" + resultValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "WEB");
            citation.put("source", source);
            citation.put("resultIndex", parseChunkIndex(resultValue));
            citations.add(citation);
        }
        return List.copyOf(citations);
    }

    private boolean hasUnknownCitation(List<Map<String, Object>> answerCitations,
                                       List<Map<String, Object>> availableCitations) {
        if (answerCitations.isEmpty() || availableCitations.isEmpty()) {
            return false;
        }
        Set<String> availableRefIds = new LinkedHashSet<>();
        for (Map<String, Object> citation : availableCitations) {
            Object refId = citation.get("refId");
            if (refId != null) {
                availableRefIds.add(String.valueOf(refId));
            }
        }
        return answerCitations.stream()
            .map(citation -> citation.get("refId"))
            .filter(value -> value != null)
            .map(String::valueOf)
            .anyMatch(refId -> !availableRefIds.contains(refId));
    }

    private Integer parseChunkIndex(String chunkValue) {
        if (chunkValue == null || chunkValue.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(chunkValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String finalAnswerText(String answer, List<String> missingInfo) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        if (missingInfo == null || missingInfo.isEmpty()) {
            return "";
        }
        return INSUFFICIENT_EVIDENCE_ANSWER;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static class GroundedDocumentEvidenceBuilder {
        private String type;
        private String citation;
        private String source;
        private String section;
        private final StringBuilder content = new StringBuilder();
    }
}
