package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.orchestration.evidence.EvidenceSufficiencyGate;
import com.chatchat.agents.protocol.AnswerContract;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/** Owns answer candidate quality evaluation, critic repair and safety admission. */
@Slf4j
final class AnswerQualityCoordinator {

    private final AnswerQualityEvaluator evaluator;
    private final AnswerCandidateCollector candidateCollector;
    private final AnswerContractCompiler contractCompiler;
    private final EvidenceSufficiencyGate sufficiencyGate;
    private final AnswerCriticRepairer criticRepairer;
    private final AnswerEvidenceLedgerCompiler evidenceLedgerCompiler;
    private final AgentRuntimeProperties runtimeProperties;
    private final long modelRequestTimeoutMs;

    AnswerQualityCoordinator(ObjectMapper objectMapper,
                             AgentRuntimeProperties runtimeProperties,
                             AnswerEvidenceLedgerCompiler evidenceLedgerCompiler,
                             long modelRequestTimeoutMs) {
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.evaluator = new AnswerQualityEvaluator(mapper);
        this.candidateCollector = new AnswerCandidateCollector();
        this.contractCompiler = new AnswerContractCompiler();
        this.sufficiencyGate = new EvidenceSufficiencyGate();
        this.criticRepairer = new AnswerCriticRepairer(mapper);
        this.evidenceLedgerCompiler = evidenceLedgerCompiler;
        this.runtimeProperties = runtimeProperties == null ? new AgentRuntimeProperties() : runtimeProperties;
        this.modelRequestTimeoutMs = modelRequestTimeoutMs;
    }

    void registerCandidate(Map<String, Object> metadata,
                           String stage,
                           String answer,
                           List<String> evidenceIds,
                           Map<String, Object> candidateMetadata) {
        candidateCollector.register(metadata, stage, answer, evidenceIds, candidateMetadata);
    }

    AnswerQualityEvaluator.QualityReport evaluate(ChatModel activeChatModel,
                                                  String query,
                                                  String systemPrompt,
                                                  List<String> observations,
                                                  String candidateAnswer,
                                                  AgentAnswerReview review,
                                                  AnswerDecisionEngine.EvidenceSignal signal,
                                                  Map<String, Object> metadata) {
        if (activeChatModel == null || reviewerAlreadyRepaired(review, metadata)) {
            return null;
        }
        List<AnswerQualityEvaluator.AnswerCandidate> candidates =
            answerCandidates(candidateAnswer, review, signal, metadata);
        if (candidates.size() <= 1) {
            return null;
        }
        long timeoutMs = configuredTimeoutMs(
            "chatchat.agent.answer.quality.timeout.ms", modelRequestTimeoutMs);
        try {
            return runWithTimeout("quality", timeoutMs, () -> evaluator.evaluate(
                activeChatModel,
                new AnswerQualityEvaluator.QualityRequest(
                    query, systemPrompt,
                    observations == null ? List.of() : List.copyOf(observations),
                    review == null ? null : review.feedback(), candidates)));
        } catch (TimeoutException ex) {
            log.warn("agentModelTimeout phase=answer_quality timeoutMs={} candidateCount={}",
                timeoutMs, candidates.size());
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_timeout", candidates);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_interrupted", candidates);
        } catch (Exception ex) {
            log.warn("agentModelFailed phase=answer_quality candidateCount={} error={}",
                candidates.size(), ex.getMessage());
            return AnswerQualityEvaluator.QualityReport.unavailable("quality_model_failed", candidates);
        }
    }

    String applyTargetedRepair(ChatModel activeChatModel,
                               String query,
                               String systemPrompt,
                               String selectedAnswer,
                               Map<String, Object> metadata,
                               List<String> observations,
                               UnaryOperator<String> sanitizer) {
        if (!runtimeProperties.isAnswerQualityPipelineEnabled()) {
            return selectedAnswer;
        }
        QualityContext context = prepareContext(query, systemPrompt, observations, metadata);
        if (!runtimeProperties.isAnswerCriticEnabled() || activeChatModel == null
            || selectedAnswer == null || selectedAnswer.isBlank()) {
            put(metadata, "answerCriticSkippedReason",
                !runtimeProperties.isAnswerCriticEnabled() ? "critic_disabled"
                    : activeChatModel == null ? "chat_model_unavailable" : "empty_answer");
            return selectedAnswer;
        }
        AnswerCriticRepairer.Result result;
        try {
            result = runWithTimeout("critic", runtimeProperties.answerCriticTimeoutMs(),
                () -> criticRepairer.review(activeChatModel, context.contract(), context.gate(),
                    selectedAnswer, observations == null ? List.of() : List.copyOf(observations)));
        } catch (TimeoutException ex) {
            put(metadata, "answerCriticTimedOut", true);
            return selectedAnswer;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            put(metadata, "answerCriticInterrupted", true);
            return selectedAnswer;
        } catch (Exception ex) {
            put(metadata, "answerCriticFailed", true);
            put(metadata, "answerCriticFailure", firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
            return selectedAnswer;
        }
        put(metadata, "answerCritic", result.toMap());
        if (!result.available() || result.passed() || !runtimeProperties.isAnswerRepairEnabled()
            || result.repairedAnswer() == null || result.repairedAnswer().isBlank()) {
            return selectedAnswer;
        }
        String repaired = sanitizer == null ? result.repairedAnswer() : sanitizer.apply(result.repairedAnswer());
        if (repaired == null || repaired.isBlank()) {
            put(metadata, "answerTargetedRepairRejectedReason", "invalid_or_internal_protocol");
            return selectedAnswer;
        }
        AnswerEvidenceLedgerCompiler.Result originalLedger = evidenceLedgerCompiler.compile(
            selectedAnswer, metadata, observations, List.of());
        AnswerEvidenceLedgerCompiler.Result repairedLedger = evidenceLedgerCompiler.compile(
            repaired, metadata, observations, List.of());
        boolean safe = evidenceRank(repairedLedger.status()) >= evidenceRank(originalLedger.status())
            && repairedLedger.criticalUnboundClaims() <= originalLedger.criticalUnboundClaims()
            && repairedLedger.unknownReferences() <= originalLedger.unknownReferences();
        if (!safe) {
            put(metadata, "answerTargetedRepairRejectedReason", "evidence_quality_regression");
            put(metadata, "answerTargetedRepairOriginalStatus", originalLedger.status());
            put(metadata, "answerTargetedRepairCandidateStatus", repairedLedger.status());
            return selectedAnswer;
        }
        put(metadata, "answerTargetedRepairApplied", true);
        put(metadata, "answerTargetedRepairContractVersion", AnswerCriticRepairer.VERSION);
        put(metadata, "answerTargetedRepairIssueCodes", result.issues().stream()
            .map(AnswerCriticRepairer.Issue::code).filter(value -> value != null && !value.isBlank()).toList());
        put(metadata, "answerTargetedRepairOriginalPreview", shortText(selectedAnswer, 1000));
        put(metadata, "answerTargetedRepairPreview", shortText(repaired, 1000));
        return repaired;
    }

    QualityContext prepareContext(String query, String systemPrompt,
                                  List<String> observations, Map<String, Object> metadata) {
        AnswerContract contract = contractCompiler.compile(query, systemPrompt, metadata);
        EvidenceSufficiencyGate.Decision gate = sufficiencyGate.evaluate(contract, observations);
        if (metadata != null && runtimeProperties.isAnswerQualityPipelineEnabled()) {
            metadata.put("answerContract", contract.toMap());
            metadata.put("answerContractVersion", AnswerContract.VERSION);
            metadata.put("evidenceSufficiencyGate", gate.toMap());
            metadata.put("evidenceSufficiencyStatus", gate.status());
            metadata.put("evidenceRetrievalRecommended", gate.retrieveMoreRecommended());
            metadata.put("businessHardcodingPolicy", "runtime_contract_only");
        }
        return new QualityContext(contract, gate);
    }

    private List<AnswerQualityEvaluator.AnswerCandidate> answerCandidates(
        String candidateAnswer, AgentAnswerReview review,
        AnswerDecisionEngine.EvidenceSignal signal, Map<String, Object> metadata
    ) {
        List<AnswerQualityEvaluator.AnswerCandidate> candidates = new ArrayList<>();
        add(candidates, AnswerQualityEvaluator.CANDIDATE, AnswerQualityEvaluator.CANDIDATE, candidateAnswer);
        if (signal != null && signal.shouldReplaceWithGroundedEvidence()) {
            add(candidates, AnswerQualityEvaluator.DOCUMENT_EVIDENCE,
                AnswerQualityEvaluator.DOCUMENT_EVIDENCE, signal.groundedDocumentAnswer());
        }
        if (reviewerAlreadyRepaired(review, metadata)) {
            add(candidates, AnswerQualityEvaluator.REVIEWER_SUGGESTION,
                AnswerQualityEvaluator.REVIEWER_SUGGESTION, review.answer());
        }
        List<AnswerCandidateCollector.Candidate> collected = candidateCollector.drain(metadata);
        if (metadata != null && !collected.isEmpty()) {
            metadata.put("answerCandidateCollectorContractVersion", AnswerCandidateCollector.CONTRACT_VERSION);
            metadata.put("answerCandidateCollectedCount", collected.size());
            metadata.put("answerCandidateCollectedStages", collected.stream()
                .map(AnswerCandidateCollector.Candidate::stage).distinct().toList());
        }
        for (AnswerCandidateCollector.Candidate candidate : collected) {
            add(candidates, firstNonBlank(candidate.id(), "runtime_stage_" + (candidates.size() + 1)),
                AnswerQualityEvaluator.SUMMARY_STAGE, candidate.answer());
        }
        return List.copyOf(candidates);
    }

    private boolean reviewerAlreadyRepaired(AgentAnswerReview review, Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get("modelEvidenceReviewRewriteAllowed"))
            && review != null && AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null && !review.answer().isBlank();
    }

    private void add(List<AnswerQualityEvaluator.AnswerCandidate> target,
                     String id, String source, String answer) {
        if (answer != null && !answer.isBlank()) {
            target.add(new AnswerQualityEvaluator.AnswerCandidate(id, source, answer));
        }
    }

    private int evidenceRank(String status) {
        if ("PASS".equals(status) || "NOT_APPLICABLE".equals(status)) return 3;
        if ("PARTIAL".equals(status)) return 2;
        if ("FAIL".equals(status)) return 1;
        return 0;
    }

    private <T> T runWithTimeout(String phase, long timeoutMs, Callable<T> task) throws Exception {
        if (timeoutMs <= 0) return task.call();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-answer-" + phase);
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
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private long configuredTimeoutMs(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void put(Map<String, Object> metadata, String key, Object value) {
        if (metadata != null) metadata.put(key, value);
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "...";
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    record QualityContext(AnswerContract contract, EvidenceSufficiencyGate.Decision gate) {
    }
}
