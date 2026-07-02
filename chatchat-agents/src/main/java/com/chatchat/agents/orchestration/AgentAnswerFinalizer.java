package com.chatchat.agents.orchestration;

import com.chatchat.agents.evidence.AnswerAssemblyEngine;
import com.chatchat.agents.evidence.AnswerAssemblyPolicy;
import com.chatchat.agents.evidence.DeterministicAnswerCompiler;
import com.chatchat.agents.evidence.EvidenceAnswerGroundingGuard;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.config.ModelsConfig;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
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
    private static final String ANSWER_EVIDENCE_DISCLOSURE_CONTRACT = "answer_evidence_disclosure_v1";
    private static final String ANSWER_EVIDENCE_GROUNDED = "GROUNDED_ANALYSIS";
    private static final String ANSWER_EVIDENCE_INSUFFICIENT = "EVIDENCE_INSUFFICIENT";
    private static final String ANSWER_EVIDENCE_BLOCKED = "EXECUTION_BLOCKED";
    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "根据当前文档证据不足，无法确认。";
    private static final int TOOL_DATA_MARKDOWN_ROW_LIMIT = 20;
    private static final int TOOL_DATA_VISUALIZATION_ROW_LIMIT = 200;
    private static final int TOOL_EVIDENCE_PREVIEW_LIMIT = 1600;
    private static final Pattern DOCUMENT_REF_PATTERN =
        Pattern.compile("doc://([^\\s\"',;\\]\\)}]+)#chunk=([^\\s\"',;\\]\\)}]+)");
    private static final Pattern WEB_REF_PATTERN =
        Pattern.compile("web://([^\\s\"',;\\]\\)}]+)#result=([^\\s\"',;\\]\\)}]+)");

    private final AgentAnswerReviewer answerReviewer;
    private final AgentRuntimeGuard runtimeGuard;
    private final long modelRequestTimeoutMs;
    private final EvidenceAnswerGroundingGuard groundingGuard = new EvidenceAnswerGroundingGuard();
    private final AnswerAssemblyEngine answerAssemblyEngine = new AnswerAssemblyEngine();
    private final AnswerDecisionEngine answerDecisionEngine = new AnswerDecisionEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnswerQualityEvaluator answerQualityEvaluator = new AnswerQualityEvaluator(objectMapper);

    AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer, AgentRuntimeGuard runtimeGuard) {
        this(answerReviewer, runtimeGuard, null);
    }

    AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer,
                         AgentRuntimeGuard runtimeGuard,
                         ModelsConfig modelsConfig) {
        this.answerReviewer = answerReviewer;
        this.runtimeGuard = runtimeGuard;
        this.modelRequestTimeoutMs = modelRequestTimeoutMs(modelsConfig);
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
        Map<String, Object> visualizationSpec = toolResultVisualizationSpec(traces);
        if (!visualizationSpec.isEmpty()) {
            values.putIfAbsent("visualizationSpec", visualizationSpec);
            values.putIfAbsent("dataVisualization", visualizationSpec);
            values.put("toolResultDataDisplayed", true);
            values.put("toolResultDataDisplaySource", visualizationSpec.get("sourceTool"));
            String answerWithTable = appendToolResultTable(finalAnswer, visualizationSpec);
            if (!answerWithTable.equals(finalAnswer)) {
                finalAnswer = answerWithTable;
                values.put("toolResultDataMarkdownAppended", true);
                values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
            }
        }
        List<Map<String, Object>> toolEvidence = toolResultEvidence(traces);
        if (!toolEvidence.isEmpty()) {
            values.put("toolResultEvidence", toolEvidence);
            values.put("toolResultEvidenceCount", toolEvidence.size());
            String answerWithEvidence = appendToolEvidence(finalAnswer, toolEvidence);
            if (!answerWithEvidence.equals(finalAnswer)) {
                finalAnswer = answerWithEvidence;
                values.put("toolResultEvidenceMarkdownAppended", true);
                values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
            }
        }
        if (!finalAnswer.equals(mergedAnswer)) {
            values.put("finalAnswerSanitized", true);
            values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        }
        AnswerEvidenceDisclosure disclosure = answerEvidenceDisclosure(values, observations, toolEvidence, traces);
        String answerWithEvidenceDisclosure = prependAnswerEvidenceDisclosure(finalAnswer, disclosure);
        recordAnswerEvidenceDisclosure(values, disclosure, !answerWithEvidenceDisclosure.equals(finalAnswer));
        finalAnswer = answerWithEvidenceDisclosure;
        values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
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
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_summary");
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
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
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_summary");
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
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
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_answer");
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
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

    private void recordCancellationAfterAnswer(BooleanSupplier cancellationCheck,
                                               Map<String, Object> metadata,
                                               String phase) {
        if (cancellationCheck == null) {
            return;
        }
        try {
            if (cancellationCheck.getAsBoolean()) {
                recordAnswerCompletedAfterCancellation(metadata, phase, "Agent cancellation requested after answer was produced");
            }
        } catch (CancellationException ex) {
            recordAnswerCompletedAfterCancellation(metadata, phase, firstNonBlank(ex.getMessage(), "Agent cancellation requested after answer was produced"));
        }
    }

    private void recordAnswerCompletedAfterCancellation(Map<String, Object> metadata,
                                                        String phase,
                                                        String reason) {
        if (metadata == null) {
            return;
        }
        metadata.put("answerCompletedAfterCancellation", true);
        metadata.put("answerCancellationPhase", phase);
        metadata.put("answerCancellationReason", reason);
        metadata.putIfAbsent("stopReason", "answer_completed_after_cancellation");
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
        long timeoutMs = configuredTimeoutMs("chatchat.agent.answer.quality.timeout.ms", modelRequestTimeoutMs);
        try {
            return runWithTimeout(
                "answer_quality",
                "",
                timeoutMs,
                () -> answerQualityEvaluator.evaluate(
                    activeChatModel,
                    new AnswerQualityEvaluator.QualityRequest(
                        query,
                        systemPrompt,
                        observations == null ? List.of() : List.copyOf(observations),
                        candidates
                    )
                )
            );
        } catch (TimeoutException ex) {
            log.warn("agentModelTimeout phase=answer_quality timeoutMs={} candidateCount={}", timeoutMs, candidates.size());
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_timeout", candidates);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("agentModelInterrupted phase=answer_quality candidateCount={}", candidates.size());
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_interrupted", candidates);
        } catch (Exception ex) {
            log.warn("agentModelFailed phase=answer_quality candidateCount={} error={}", candidates.size(), ex.getMessage());
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_failed", candidates);
        }
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

    private Map<String, Object> toolResultVisualizationSpec(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return Map.of();
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || !trace.isSuccess()) {
                continue;
            }
            Map<String, Object> output = parseObject(trace.getOutput());
            if (output.isEmpty()) {
                continue;
            }
            Map<String, Object> data = firstTabularData(output);
            if (data.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> rows = rowMaps(data.get("rows"), data.get("columns"));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = columns(data.get("columns"), rows);
            if (columns.isEmpty()) {
                continue;
            }
            int rowCount = firstInt(data.get("rowCount"), data.get("total"), data.get("count"), rows.size());
            String title = firstNonBlank(stringValue(data.get("title")), "查询结果明细");
            Map<String, Object> tableSpec = tableVisualizationSpec(title, columns, rows, rowCount, trace);
            Map<String, Object> chartSpec = chartVisualizationSpec(title, columns, rows);
            return chartSpec.isEmpty() ? tableSpec : panelVisualizationSpec(title, chartSpec, tableSpec, trace);
        }
        return Map.of();
    }

    private Map<String, Object> tableVisualizationSpec(String title,
                                                       List<String> columns,
                                                       List<Map<String, Object>> rows,
                                                       int rowCount,
                                                       InteractionToolTrace trace) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("columns", columns);
        dataset.put("rows", rows.stream().limit(TOOL_DATA_VISUALIZATION_ROW_LIMIT).toList());
        dataset.put("rowCount", rowCount);
        dataset.put("displayedRowCount", Math.min(rows.size(), TOOL_DATA_VISUALIZATION_ROW_LIMIT));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v1");
        spec.put("type", "table");
        spec.put("title", title);
        spec.put("analysisType", "tool_result_rows");
        spec.put("dataset", dataset);
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "table"));
        spec.put("sourceTool", firstNonBlank(trace.getToolName(), ""));
        spec.put("sourceDisplayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        return spec;
    }

    private Map<String, Object> panelVisualizationSpec(String title,
                                                       Map<String, Object> chartSpec,
                                                       Map<String, Object> tableSpec,
                                                       InteractionToolTrace trace) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v2");
        spec.put("type", "panel");
        spec.put("title", title);
        spec.put("analysisType", "tool_result_visualization");
        spec.put("layout", "stack");
        spec.put("dataset", tableSpec.get("dataset"));
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "panel"));
        spec.put("sourceTool", firstNonBlank(trace.getToolName(), ""));
        spec.put("sourceDisplayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        spec.put("blocks", List.of(
            Map.of("id", "chart", "type", "chart", "title", chartSpec.get("title"), "spec", chartSpec),
            Map.of("id", "table", "type", "table", "title", tableSpec.get("title"), "spec", tableSpec)
        ));
        return spec;
    }

    private Map<String, Object> chartVisualizationSpec(String title,
                                                       List<String> columns,
                                                       List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2 || columns == null || columns.isEmpty()) {
            return Map.of();
        }
        String timeKey = columns.stream()
            .filter(column -> isTimeColumn(column, rows))
            .findFirst()
            .orElse(null);
        List<String> numericColumns = columns.stream()
            .filter(column -> !column.equals(timeKey))
            .filter(column -> !isIdentifierColumn(column))
            .filter(column -> rows.stream().anyMatch(row -> numberValue(row.get(column)) != null))
            .limit(4)
            .toList();
        if (!numericColumns.isEmpty()) {
            String xKey = firstNonBlank(timeKey, firstNonBlank(firstCategoricalColumn(columns, rows), columns.get(0)));
            return chartVisualizationSpec(
                title,
                timeKey == null ? "bar" : "line",
                timeKey == null ? "comparison" : "trend",
                xKey,
                numericColumns.stream().map(column -> Map.of("name", column, "yKey", column)).toList(),
                rows.stream().limit(TOOL_DATA_VISUALIZATION_ROW_LIMIT).toList()
            );
        }

        String categoryKey = firstCategoricalColumn(columns, rows);
        if (categoryKey == null) {
            return Map.of();
        }
        List<Map<String, Object>> countedRows = categoryCountRows(rows, categoryKey);
        if (countedRows.size() < 2) {
            return Map.of();
        }
        return chartVisualizationSpec(
            title + "分布",
            countedRows.size() <= 8 ? "pie" : "bar",
            "distribution",
            categoryKey,
            List.of(Map.of("name", "数量", "yKey", "count")),
            countedRows
        );
    }

    private Map<String, Object> chartVisualizationSpec(String title,
                                                       String chartType,
                                                       String analysisType,
                                                       String xKey,
                                                       List<Map<String, String>> series,
                                                       List<Map<String, Object>> rows) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("xKey", xKey);
        dataset.put("series", series);
        dataset.put("rows", rows);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v1");
        spec.put("type", "chart");
        spec.put("chartType", chartType);
        spec.put("title", title);
        spec.put("analysisType", analysisType);
        spec.put("dataset", dataset);
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "chart"));
        spec.put("insight", Map.of("summary", "已根据结构化工具结果自动生成图形表达。"));
        return spec;
    }

    private String firstCategoricalColumn(List<String> columns, List<Map<String, Object>> rows) {
        for (String column : columns) {
            if (isIdentifierColumn(column) || isTimeColumn(column, rows)) {
                continue;
            }
            if (rows.stream().anyMatch(row -> numberValue(row.get(column)) == null && nonBlankString(row.get(column)))) {
                return column;
            }
        }
        return null;
    }

    private List<Map<String, Object>> categoryCountRows(List<Map<String, Object>> rows, String categoryKey) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = stringValue(row.get(categoryKey));
            if (key == null || key.isBlank()) {
                key = "未分类";
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .limit(TOOL_DATA_VISUALIZATION_ROW_LIMIT)
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(categoryKey, entry.getKey());
                row.put("count", entry.getValue());
                return row;
            })
            .toList();
    }

    private boolean isTimeColumn(String column, List<Map<String, Object>> rows) {
        String normalized = column == null ? "" : column.toLowerCase();
        if (normalized.matches(".*(date|time|month|year|day|week|quarter).*")
            || normalized.contains("日期")
            || normalized.contains("时间")) {
            return true;
        }
        return rows.stream()
            .map(row -> stringValue(row.get(column)))
            .filter(value -> value != null && !value.isBlank())
            .limit(5)
            .anyMatch(value -> value.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")
                || value.matches("\\d{8}")
                || value.matches("\\d{2}:\\d{2}(:\\d{2})?.*"));
    }

    private boolean isIdentifierColumn(String column) {
        String normalized = column == null ? "" : column.toLowerCase();
        return normalized.matches(".*(id|code|no|uuid|serial).*")
            || normalized.contains("代码")
            || normalized.contains("编号")
            || normalized.contains("标识");
    }

    private Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replace(",", "").replace("%", "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> toolResultEvidence(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InteractionToolTrace trace : traces) {
            if (trace == null) {
                continue;
            }
            Map<String, Object> item = toolEvidence(trace);
            if (!item.isEmpty()) {
                evidence.add(item);
            }
        }
        return List.copyOf(evidence);
    }

    private Map<String, Object> toolEvidence(InteractionToolTrace trace) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolName", firstNonBlank(trace.getToolName(), ""));
        item.put("displayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        item.put("success", trace.isSuccess());
        item.put("durationMs", trace.getDurationMs());
        if (trace.getErrorMessage() != null && !trace.getErrorMessage().isBlank()) {
            item.put("errorMessage", trace.getErrorMessage());
        }

        Map<String, Object> output = parseObject(trace.getOutput());
        if (output.isEmpty()) {
            String preview = previewText(trace.getOutput());
            if (preview.isBlank()) {
                return item;
            }
            item.put("evidenceType", "text");
            item.put("outputPreview", preview);
            return item;
        }

        Map<String, Object> table = firstTabularData(output);
        if (!table.isEmpty()) {
            List<Map<String, Object>> rows = rowMaps(table.get("rows"), table.get("columns"));
            List<String> columns = columns(table.get("columns"), rows);
            item.put("evidenceType", "tabular");
            item.put("columns", columns);
            item.put("rowCount", firstInt(table.get("rowCount"), table.get("total"), table.get("count"), rows.size()));
            item.put("returnedRowCount", rows.size());
            item.put("sampleRows", rows.stream().limit(5).toList());
            return item;
        }

        Map<String, Object> data = primaryData(output);
        if (isLinuxEvidence(data)) {
            item.put("evidenceType", "linux_command");
            item.put("exitCode", firstPresent(data.get("exitCode"), output.get("exitCode")));
            item.put("commandSuccess", firstPresent(data.get("commandSuccess"), output.get("commandSuccess")));
            item.put("transportSuccess", firstPresent(data.get("transportSuccess"), output.get("transportSuccess")));
            item.put("failedStepIndex", firstPresent(data.get("failedStepIndex"), output.get("failedStepIndex")));
            item.put("stdoutPreview", previewText(firstNonBlank(stringValue(data.get("stdout")), stringValue(output.get("stdout")))));
            item.put("stderrPreview", previewText(firstNonBlank(stringValue(data.get("stderr")), stringValue(output.get("stderr")))));
            item.put("stepCount", listSize(data.get("steps")));
            return compactEvidence(item);
        }

        if (isHttpEvidence(data)) {
            item.put("evidenceType", "http_response");
            item.put("statusCode", firstPresent(data.get("statusCode"), output.get("statusCode")));
            item.put("bodyPreview", previewStructured(firstPresent(data.get("body"), output.get("body"))));
            item.put("rawBodyPreview", previewText(firstNonBlank(stringValue(data.get("rawBody")), stringValue(output.get("rawBody")))));
            return compactEvidence(item);
        }

        item.put("evidenceType", "json");
        item.put("schemaVersion", firstPresent(output.get("schemaVersion"), output.get("dataSchema")));
        item.put("payloadType", output.get("payloadType"));
        item.put("keys", new ArrayList<>(output.keySet()));
        item.put("outputPreview", previewStructured(output));
        return compactEvidence(item);
    }

    private Map<String, Object> primaryData(Map<String, Object> output) {
        for (String key : List.of("data", "result", "payload", "structuredContent")) {
            Object value = output.get(key);
            if (value instanceof Map<?, ?> map) {
                return copyMap(map);
            }
        }
        return output == null ? Map.of() : output;
    }

    private boolean isLinuxEvidence(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey("exitCode")
            || data.containsKey("stdout")
            || data.containsKey("stderr")
            || data.containsKey("steps")
            || data.containsKey("commandSuccess");
    }

    private boolean isHttpEvidence(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey("statusCode")
            || data.containsKey("body")
            || data.containsKey("rawBody");
    }

    private Map<String, Object> compactEvidence(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        item.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                return;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                return;
            }
            result.put(key, value);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(text, Object.class);
            return value instanceof Map<?, ?> map ? copyMap((Map<?, ?>) map) : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> firstTabularData(Map<String, Object> output) {
        Map<String, Object> direct = tabularData(output);
        if (!direct.isEmpty()) {
            return direct;
        }
        for (String key : List.of("result", "data", "dataset", "payload", "structuredContent")) {
            Object value = output.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = firstTabularData(copyMap(map));
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> tabularData(Map<String, Object> value) {
        Object rows = firstPresent(value.get("rows"), firstPresent(value.get("records"), value.get("items")));
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("columns", firstPresent(value.get("columns"), value.get("fields")));
        data.put("rowCount", firstPresent(value.get("rowCount"), firstPresent(value.get("total"), value.get("count"))));
        data.put("title", firstPresent(value.get("title"), firstPresent(value.get("name"), value.get("templateId"))));
        return data;
    }

    private List<Map<String, Object>> rowMaps(Object rowsValue, Object columnsValue) {
        if (!(rowsValue instanceof List<?> values)) {
            return List.of();
        }
        List<String> columns = columnsValue instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                rows.add(copyMap(map));
            } else if (value instanceof List<?> rowValues && !columns.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size() && i < rowValues.size(); i++) {
                    row.put(columns.get(i), rowValues.get(i));
                }
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private List<String> columns(Object columnsValue, List<Map<String, Object>> rows) {
        if (columnsValue instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!columns.contains(key)) {
                    columns.add(key);
                }
            }
        }
        return List.copyOf(columns);
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String appendToolResultTable(String answer, Map<String, Object> visualizationSpec) {
        if (visualizationSpec == null || visualizationSpec.isEmpty()) {
            return answer == null ? "" : answer;
        }
        String base = answer == null ? "" : answer.trim();
        if (base.contains("## 查询结果明细") || base.contains("## 数据明细")) {
            return base;
        }
        Map<String, Object> dataset = visualizationSpec.get("dataset") instanceof Map<?, ?> map
            ? copyMap(map)
            : Map.of();
        List<String> columns = dataset.get("columns") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        List<Map<String, Object>> rows = rowMaps(dataset.get("rows"), columns);
        if (columns.isEmpty() || rows.isEmpty()) {
            return base;
        }
        int rowCount = firstInt(dataset.get("rowCount"), rows.size());
        int displayCount = Math.min(rows.size(), TOOL_DATA_MARKDOWN_ROW_LIMIT);
        StringBuilder table = new StringBuilder();
        table.append("## 查询结果明细\n\n");
        table.append("已找到 ").append(rowCount).append(" 行数据，下面展示前 ")
            .append(displayCount).append(" 行；完整结构化数据已随结果返回用于表格展示。\n\n");
        table.append("| ");
        for (String column : columns) {
            table.append(escapeTableCell(column)).append(" | ");
        }
        table.append("\n| ");
        for (int i = 0; i < columns.size(); i++) {
            table.append("--- | ");
        }
        table.append("\n");
        for (Map<String, Object> row : rows.stream().limit(TOOL_DATA_MARKDOWN_ROW_LIMIT).toList()) {
            table.append("| ");
            for (String column : columns) {
                table.append(escapeTableCell(row.get(column))).append(" | ");
            }
            table.append("\n");
        }
        return base.isBlank() ? table.toString().trim() : base + "\n\n" + table.toString().trim();
    }

    private String appendToolEvidence(String answer, List<Map<String, Object>> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return answer == null ? "" : answer;
        }
        String base = answer == null ? "" : answer.trim();
        if (base.contains("## 工具执行证据")) {
            return base;
        }
        StringBuilder section = new StringBuilder();
        section.append("## 工具执行证据\n\n");
        section.append("以下为本次工具调用证明，仅展示必要摘要；完整结构化结果保留在运行元数据中。\n\n");
        int index = 1;
        for (Map<String, Object> item : evidence) {
            section.append(index++).append(". `")
                .append(escapeInline(firstNonBlank(stringValue(item.get("toolName")), "unknown_tool")))
                .append("`");
            String displayName = stringValue(item.get("displayName"));
            if (displayName != null && !displayName.isBlank() && !displayName.equals(item.get("toolName"))) {
                section.append("（").append(escapeInline(displayName)).append("）");
            }
            section.append("：").append(Boolean.TRUE.equals(item.get("success")) ? "成功" : "失败")
                .append("，证据类型 `").append(escapeInline(firstNonBlank(stringValue(item.get("evidenceType")), "unknown"))).append("`");
            appendEvidenceField(section, "行数", item.get("rowCount"));
            appendEvidenceField(section, "返回行数", item.get("returnedRowCount"));
            appendEvidenceField(section, "退出码", item.get("exitCode"));
            appendEvidenceField(section, "HTTP 状态", item.get("statusCode"));
            appendEvidenceField(section, "耗时 ms", item.get("durationMs"));
            appendEvidenceField(section, "字段", compactListText(item.get("columns"), 8));
            appendEvidenceField(section, "输出键", compactListText(item.get("keys"), 8));
            appendEvidenceField(section, "摘要", evidenceSummary(item));
            section.append("。\n");
        }
        return base.isBlank() ? section.toString().trim() : base + "\n\n" + section.toString().trim();
    }

    private void appendEvidenceField(StringBuilder section, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            section.append("，").append(label).append("=").append(escapeInline(String.valueOf(value)));
        }
    }

    private void appendEvidenceBlock(StringBuilder section, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        section.append("   - ").append(label).append("：").append(escapeInline(value)).append("\n");
    }

    private String listText(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    private String compactListText(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> values = list.stream()
            .limit(Math.max(1, limit))
            .map(String::valueOf)
            .toList();
        String suffix = list.size() > values.size() ? " 等" + list.size() + "项" : "";
        return String.join(", ", values) + suffix;
    }

    private String evidenceSummary(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        if (item.get("errorMessage") != null) {
            return shortEvidenceText(stringValue(item.get("errorMessage")), 160);
        }
        String type = stringValue(item.get("evidenceType"));
        if ("tabular".equals(type)) {
            Object rowCount = firstPresent(item.get("rowCount"), item.get("returnedRowCount"));
            return rowCount == null ? "已返回表格数据" : "已返回 " + rowCount + " 行表格数据";
        }
        if ("linux_command".equals(type)) {
            Object stepCount = item.get("stepCount");
            return stepCount == null ? "命令执行完成" : "命令执行完成，步骤数 " + stepCount;
        }
        if ("http_response".equals(type)) {
            return "HTTP 调用完成";
        }
        if ("json".equals(type)) {
            return "已返回结构化 JSON";
        }
        if ("text".equals(type)) {
            return shortEvidenceText(stringValue(item.get("outputPreview")), 160);
        }
        return "";
    }

    private String shortEvidenceText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        int max = Math.max(20, limit);
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String escapeTableCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private String escapeInline(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    private String previewText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= TOOL_EVIDENCE_PREVIEW_LIMIT ? text : text.substring(0, TOOL_EVIDENCE_PREVIEW_LIMIT);
    }

    private String previewStructured(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return previewText(text);
        }
        try {
            return previewText(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
            return previewText(String.valueOf(value));
        }
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int firstInt(Object first, Object second) {
        Integer firstValue = intValue(first);
        if (firstValue != null) {
            return firstValue;
        }
        Integer secondValue = intValue(second);
        return secondValue == null ? 0 : secondValue;
    }

    private int firstInt(Object first, Object second, Object third, int fallback) {
        for (Object value : new Object[]{first, second, third}) {
            Integer parsed = intValue(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        long timeoutMs = configuredTimeoutMs("chatchat.agent.answer.review.timeout.ms", modelRequestTimeoutMs);
        AgentAnswerReview review;
        try {
            review = runWithTimeout(
                "review",
                runId,
                timeoutMs,
                () -> answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer)
            );
        } catch (TimeoutException ex) {
            if (metadata != null) {
                metadata.put("answerReviewTimedOut", true);
                metadata.put("answerReviewTimeoutMs", timeoutMs);
                metadata.put("answerReviewFallback", "accepted_current_answer");
            }
            log.warn("agentModelTimeout phase=review runId={} timeoutMs={} answerChars={} observationCount={}",
                firstNonBlank(runId, ""),
                timeoutMs,
                finalAnswer == null ? 0 : finalAnswer.length(),
                observations == null ? 0 : observations.size());
            return acceptWithoutReviewer(finalAnswer,
                "Answer reviewer timed out after " + timeoutMs + "ms; accepted current evidence-grounded answer.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (metadata != null) {
                metadata.put("answerReviewInterrupted", true);
                metadata.put("answerReviewFallback", "accepted_current_answer");
            }
            log.warn("agentModelInterrupted phase=review runId={} answerChars={}",
                firstNonBlank(runId, ""),
                finalAnswer == null ? 0 : finalAnswer.length());
            return acceptWithoutReviewer(finalAnswer,
                "Answer reviewer was interrupted; accepted current evidence-grounded answer.");
        } catch (Exception ex) {
            if (metadata != null) {
                metadata.put("answerReviewFailed", true);
                metadata.put("answerReviewError", ex.getMessage());
                metadata.put("answerReviewFallback", "accepted_current_answer");
            }
            log.warn("agentModelFailed phase=review runId={} error={}",
                firstNonBlank(runId, ""),
                ex.getMessage());
            return acceptWithoutReviewer(finalAnswer,
                "Answer reviewer failed; accepted current evidence-grounded answer.");
        }
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

    private AgentAnswerReview acceptWithoutReviewer(String answer, String feedback) {
        return new AgentAnswerReview(
            AgentAnswerReview.ACCEPTED,
            answer == null ? "" : answer,
            feedback
        );
    }

    private <T> T runWithTimeout(String phase,
                                 String runId,
                                 long timeoutMs,
                                 Callable<T> task) throws Exception {
        if (timeoutMs <= 0) {
            return task.call();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                "agent-" + firstNonBlank(phase, "model") + "-" + firstNonBlank(runId, "unknown"));
            thread.setDaemon(true);
            return thread;
        });
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private long configuredTimeoutMs(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long modelRequestTimeoutMs(ModelsConfig modelsConfig) {
        if (modelsConfig == null || modelsConfig.getOpenai() == null) {
            return 0L;
        }
        int timeoutSeconds = modelsConfig.getOpenai().getTimeout();
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return TimeUnit.SECONDS.toMillis(timeoutSeconds);
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

    private AnswerEvidenceDisclosure answerEvidenceDisclosure(Map<String, Object> metadata,
                                                              List<String> observations,
                                                              List<Map<String, Object>> toolEvidence,
                                                              List<InteractionToolTrace> traces) {
        List<String> reasons = new ArrayList<>();
        boolean successfulToolEvidence = toolEvidence != null && toolEvidence.stream()
            .anyMatch(item -> Boolean.TRUE.equals(item.get("success")) && nonBlankString(item.get("evidenceType")));
        if (successfulToolEvidence) {
            reasons.add("\u5df2\u5b8c\u6210\u5de5\u5177\u8fd4\u56de\u7684\u7ed3\u6784\u5316\u7ed3\u679c");
        }
        boolean structuredSqlMetadata = Boolean.TRUE.equals(metadata == null ? null : metadata.get("structuredSqlMetadataRendered"))
            && Boolean.TRUE.equals(metadata == null ? null : metadata.get("sqlMetadataSemanticGatePassed"));
        if (structuredSqlMetadata) {
            reasons.add("\u5df2\u901a\u8fc7\u8bed\u4e49\u95e8\u7981\u7684\u7ed3\u6784\u5316 SQL \u5143\u6570\u636e");
        }
        if (containsEvidence(observations == null ? List.of() : observations)) {
            reasons.add("\u5e26\u6765\u6e90\u6807\u8bc6\u7684\u6587\u6863/\u77e5\u8bc6\u5e93/\u6267\u884c\u8bc1\u636e");
        }
        if (!reasons.isEmpty()) {
            return new AnswerEvidenceDisclosure(
                ANSWER_EVIDENCE_GROUNDED,
                "\u6709\u4e8b\u5b9e\u4f9d\u636e\u7684\u5206\u6790",
                "\u8bc1\u636e\u72b6\u6001\uff1a\u6709\u4e8b\u5b9e\u4f9d\u636e\u7684\u5206\u6790\u3002\u4f9d\u636e\uff1a" + String.join("\uff1b", reasons) + "\u3002",
                reasons
            );
        }

        boolean failedTerminalTool = toolEvidence != null && toolEvidence.stream()
            .anyMatch(item -> !Boolean.TRUE.equals(item.get("success"))
                && (nonBlankString(item.get("errorMessage")) || nonBlankString(item.get("evidenceType"))));
        boolean failedTrace = traces != null && traces.stream().anyMatch(trace -> trace != null && !trace.isSuccess());
        boolean blockedByRuntime = Boolean.TRUE.equals(metadata == null ? null : metadata.get("fatalExecutionBlocked"))
            || Boolean.TRUE.equals(metadata == null ? null : metadata.get("mandatoryWorkflowBlocked"))
            || observationContains(observations, "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED")
            || observationContains(observations, "mandatory workflow tools are still incomplete")
            || observationContains(observations, "failed after rewrite budget");
        if (failedTerminalTool || failedTrace || blockedByRuntime) {
            List<String> blockedReasons = new ArrayList<>();
            if (failedTerminalTool || failedTrace) {
                blockedReasons.add("\u5de5\u5177\u6267\u884c\u5df2\u5f62\u6210\u5931\u8d25\u6216\u6743\u9650\u89c2\u5bdf");
            }
            if (blockedByRuntime) {
                blockedReasons.add("Runtime \u963b\u65ad\u4e86\u672a\u5b8c\u6210\u7684\u5f3a\u5236\u5de5\u5177\u6d41\u7a0b");
            }
            if (blockedReasons.isEmpty()) {
                blockedReasons.add("\u672a\u5f62\u6210\u53ef\u7528\u7684\u4e1a\u52a1\u6570\u636e\u8bc1\u636e");
            }
            return new AnswerEvidenceDisclosure(
                ANSWER_EVIDENCE_BLOCKED,
                "\u6267\u884c\u963b\u65ad/\u8bc1\u636e\u4e0d\u8db3",
                "\u8bc1\u636e\u72b6\u6001\uff1a\u6267\u884c\u963b\u65ad/\u8bc1\u636e\u4e0d\u8db3\u3002\u4ee5\u4e0b\u53ef\u4f5c\u4e3a\u5931\u8d25\u4e8b\u5b9e\u3001\u6d41\u7a0b\u72b6\u6001\u548c\u6392\u67e5\u53c2\u8003\uff0c\u4e0d\u80fd\u4f5c\u4e3a\u786e\u5b9a\u6027\u4e1a\u52a1\u7ed3\u8bba\u3002\u4f9d\u636e\uff1a"
                    + String.join("\uff1b", blockedReasons) + "\u3002",
                blockedReasons
            );
        }

        List<String> gaps = List.of("\u672a\u53d1\u73b0\u5df2\u5b8c\u6210\u5de5\u5177\u7684\u7ed3\u6784\u5316\u7ed3\u679c",
            "\u672a\u53d1\u73b0\u5e26\u6765\u6e90\u6807\u8bc6\u7684\u6587\u6863\u6216\u77e5\u8bc6\u5e93\u8bc1\u636e");
        return new AnswerEvidenceDisclosure(
            ANSWER_EVIDENCE_INSUFFICIENT,
            "\u8bc1\u636e\u4e0d\u8db3/\u63a8\u6d4b",
            "\u8bc1\u636e\u72b6\u6001\uff1a\u8bc1\u636e\u4e0d\u8db3/\u53c2\u8003\u6027\u5206\u6790\u3002\u4ee5\u4e0b\u53ef\u4f5c\u4e3a\u5f85\u9a8c\u8bc1\u7684\u63a8\u6d4b\u3001\u5206\u6790\u601d\u8def\u6216\u4e0b\u4e00\u6b65\u5efa\u8bae\uff0c\u4e0d\u80fd\u4f5c\u4e3a\u786e\u5b9a\u6027\u4e1a\u52a1\u7ed3\u8bba\u3002\u4f9d\u636e\u7f3a\u53e3\uff1a"
                + String.join("\uff1b", gaps) + "\u3002",
            gaps
        );
    }

    private String prependAnswerEvidenceDisclosure(String answer, AnswerEvidenceDisclosure disclosure) {
        String safeAnswer = answer == null ? "" : answer.trim();
        if (safeAnswer.contains("\u8bc1\u636e\u72b6\u6001\uff1a")) {
            return safeAnswer;
        }
        return "> " + disclosure.message() + (safeAnswer.isBlank() ? "" : "\n\n" + safeAnswer);
    }

    private void recordAnswerEvidenceDisclosure(Map<String, Object> metadata,
                                                AnswerEvidenceDisclosure disclosure,
                                                boolean rendered) {
        metadata.put("answerEvidenceDisclosureVersion", ANSWER_EVIDENCE_DISCLOSURE_CONTRACT);
        metadata.put("answerRequiresEvidenceDisclosure", true);
        metadata.put("answerEvidenceDisclosureRendered", rendered);
        metadata.put("answerEvidenceStatus", disclosure.status());
        metadata.put("answerEvidenceLabel", disclosure.label());
        metadata.put("answerEvidenceReasons", disclosure.reasons());
        metadata.put("answerEvidenceDisclosure", disclosure.message());
    }

    private boolean observationContains(List<String> observations, String needle) {
        if (observations == null || observations.isEmpty() || needle == null || needle.isBlank()) {
            return false;
        }
        return observations.stream()
            .filter(value -> value != null)
            .anyMatch(value -> value.contains(needle));
    }

    private boolean nonBlankString(Object value) {
        return value != null && !stringValue(value).isBlank();
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

    private record AnswerEvidenceDisclosure(String status,
                                            String label,
                                            String message,
                                            List<String> reasons) {
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

    private Object firstPresent(Object first, Object second) {
        return first == null ? second : first;
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
