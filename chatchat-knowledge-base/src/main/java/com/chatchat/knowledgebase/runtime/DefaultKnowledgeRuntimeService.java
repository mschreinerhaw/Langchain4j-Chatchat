package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.chatchat.common.knowledge.spi.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.spi.KnowledgeRuntimePort;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.spi.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.skill.KnowledgeSkillResult;
import com.chatchat.common.knowledge.spi.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.knowledgebase.runtime.workflow.KnowledgeEvidenceExpansionWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

/** Planner/executor/compiler orchestration behind the stable Knowledge Runtime port. */
@Service
@Slf4j
public class DefaultKnowledgeRuntimeService implements KnowledgeRuntimePort {

    private final KnowledgeSkillSynthesizerPort skillSynthesizer;
    private final List<KnowledgeSkillExecutorPort> skillExecutors;
    private final KnowledgeContextCompilerPort contextCompiler;
    private final KnowledgeEvidenceExpansionWorkflow evidenceExpansionWorkflow;
    private final ExecutorService knowledgeExecutor;
    @Autowired(required = false)
    private SkillExecutionScopePort skillExecutionScope;

    public DefaultKnowledgeRuntimeService(KnowledgeSkillSynthesizerPort skillSynthesizer,
                                          List<KnowledgeSkillExecutorPort> skillExecutors,
                                          KnowledgeContextCompilerPort contextCompiler) {
        this(skillSynthesizer, skillExecutors, contextCompiler, null, ForkJoinPool.commonPool());
    }

    DefaultKnowledgeRuntimeService(KnowledgeSkillSynthesizerPort skillSynthesizer,
                                   List<KnowledgeSkillExecutorPort> skillExecutors,
                                   KnowledgeContextCompilerPort contextCompiler,
                                   KnowledgeEvidenceExpansionWorkflow evidenceExpansionWorkflow) {
        this(skillSynthesizer, skillExecutors, contextCompiler,
            evidenceExpansionWorkflow, ForkJoinPool.commonPool());
    }

    @Autowired
    public DefaultKnowledgeRuntimeService(KnowledgeSkillSynthesizerPort skillSynthesizer,
                                          List<KnowledgeSkillExecutorPort> skillExecutors,
                                          KnowledgeContextCompilerPort contextCompiler,
                                          KnowledgeEvidenceExpansionWorkflow evidenceExpansionWorkflow,
                                          @Qualifier("knowledgeRuntimeExecutor") ExecutorService knowledgeExecutor) {
        this.skillSynthesizer = skillSynthesizer;
        this.skillExecutors = skillExecutors;
        this.contextCompiler = contextCompiler;
        this.evidenceExpansionWorkflow = evidenceExpansionWorkflow;
        this.knowledgeExecutor = knowledgeExecutor;
    }

    @Value("${chatchat.knowledge.runtime.skill-timeout-ms:15000}")
    private long skillTimeoutMs = 15_000L;

    @Value("${chatchat.knowledge.runtime.total-timeout-ms:18000}")
    private long totalTimeoutMs = 18_000L;

    @Override
    public KnowledgeContext retrieveKnowledge(KnowledgeRequest request) {
        RetrievalSnapshot initial = retrieveOnce(request);
        if (!initial.context().truncated()) {
            return initial.context();
        }
        if (evidenceExpansionWorkflow == null) {
            log.warn("knowledgeEvidenceExpansionCompleted status=UNAVAILABLE reason=WORKFLOW_NOT_CONFIGURED "
                + "initialEvidenceRetained={}", initial.context().used());
            return expansionFallback(initial.context());
        }
        log.info("knowledgeEvidenceExpansionTriggered trigger=CONTEXT_TRUNCATED initialMaxTokens={} sources={}",
            request.maxTokens(), initial.context().sources().size());
        KnowledgeEvidenceExpansionWorkflow.ExpansionResult expansion;
        try {
            expansion = evidenceExpansionWorkflow.expand(request, initial.units());
        } catch (RuntimeException ex) {
            log.error("knowledgeEvidenceExpansionSystemError errorType={} error={}",
                ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
        if (!expansion.applicable()) {
            log.warn("knowledgeEvidenceExpansionCompleted status=UNAVAILABLE reason={} initialEvidenceRetained={}",
                expansion.status(), initial.context().used());
            return expansionFallback(initial.context());
        }
        KnowledgeRequest expandedRequest = expandedRequest(request);
        // Expansion is an additive recovery path. All outcomes converge here so the
        // initially retrieved evidence can never be discarded by a later expansion.
        List<KnowledgeIR> finalUnits = mergeEvidence(initial.units(), expansion.units());
        if (finalUnits.isEmpty()) {
            log.warn("knowledgeEvidenceExpansionCompleted status={} successfulSources={} failedSources={} "
                    + "initialEvidenceRetained={}", expansion.status(), expansion.successfulSources(),
                expansion.failures().size(), initial.context().used());
            return expansionFallback(initial.context());
        }
        KnowledgeContext completed = contextCompiler.compile(expandedRequest, initial.plan(), finalUnits);
        if (completed.truncated()) {
            log.warn("knowledgeEvidenceExpansionCompleted status=PARTIAL reason=FINAL_BUNDLE_EXCEEDS_BUDGET "
                + "units={} maxTokens={} initialEvidenceRetained={}", finalUnits.size(),
                expandedRequest.maxTokens(), initial.context().used());
            return expansionFallback(initial.context());
        }
        String status = expansion.complete() ? completed.status() : "evidence_expansion_partial";
        log.info("knowledgeEvidenceExpansionCompleted status={} initialMaxTokens={} finalMaxTokens={} sources={} "
                + "successfulSources={} failedSources={} truncated=false",
            status, request.maxTokens(), completed.maxTokens(), completed.sources().size(),
            expansion.successfulSources(), expansion.failures().size());
        return expansion.complete() ? completed : withStatus(completed, status);
    }

    private RetrievalSnapshot retrieveOnce(KnowledgeRequest request) {
        KnowledgeSkillPlan plan = skillSynthesizer.synthesize(request);
        List<KnowledgeIR> units = new ArrayList<>();
        List<String> executionStatuses = new ArrayList<>();
        long effectiveSkillTimeoutMs = requestSkillTimeoutMs(request);
        List<Future<SkillExecutionOutcome>> executions = plan.skills().stream()
            .map(skill -> submitSkill(request, skill))
            .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, totalTimeoutMs));
        for (int index = 0; index < executions.size(); index++) {
            KnowledgeSkillInstance skill = plan.skills().get(index);
            Future<SkillExecutionOutcome> execution = executions.get(index);
            long remainingMs = Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            long waitMs = Math.min(effectiveSkillTimeoutMs, remainingMs);
            if (waitMs <= 0L) {
                execution.cancel(true);
                log.warn("knowledgeRuntimeTotalTimeout instanceId={} type={} totalTimeoutMs={}",
                    skill.instanceId(), skill.skillType(), totalTimeoutMs);
                continue;
            }
            try {
                SkillExecutionOutcome result = execution.get(waitMs, TimeUnit.MILLISECONDS);
                units.addAll(result.units());
                executionStatuses.add(result.status());
            } catch (TimeoutException ex) {
                execution.cancel(true);
                log.warn("knowledgeSkillExecutionTimedOut instanceId={} type={} timeoutMs={}",
                    skill.instanceId(), skill.skillType(), waitMs);
            } catch (InterruptedException ex) {
                executions.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                log.warn("knowledgeRuntimeInterrupted instanceId={} type={}", skill.instanceId(), skill.skillType());
                break;
            } catch (java.util.concurrent.ExecutionException ex) {
                log.warn("knowledgeSkillExecutionFailed instanceId={} type={} error={}",
                    skill.instanceId(), skill.skillType(), ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
            }
        }
        if (skillExecutionScope != null && request.scope().agentId() != null) {
            SkillExecutionScopePort.EffectiveScope fresh = skillExecutionScope.resolve(
                request.scope().tenantId(), request.scope().userId(), request.scope().agentId(),
                request.scope().documentIds(), request.scope().tags());
            if (!fresh.skillAllowed() || (Boolean.TRUE.equals(request.attributes().get("skillScopeManaged"))
                && !fresh.managed())) units.clear();
            else if (fresh.managed()) {
                java.util.Set<String> allowed = new java.util.HashSet<>(fresh.documentIds());
                units.removeIf(unit -> unit.source() == null || unit.source().documentId() == null
                    || !allowed.contains(unit.source().documentId()));
            }
        }
        KnowledgeContext compiled = contextCompiler.compile(request, plan, units);
        String terminalStatus = aggregateEvidenceStatus(executionStatuses, compiled.used());
        if (terminalStatus != null) compiled = withStatus(compiled, terminalStatus);
        return new RetrievalSnapshot(plan, List.copyOf(units), compiled);
    }

    private KnowledgeRequest expandedRequest(KnowledgeRequest source) {
        Map<String, Object> attributes = new LinkedHashMap<>(source.attributes());
        attributes.put("knowledgeEvidenceExpansion", true);
        attributes.put("knowledgeEvidenceExpansionTrigger", "CONTEXT_TRUNCATED");
        attributes.put("knowledgeEvidenceExpansionWorkflow", "DOCUMENT_SECTION_EXPANSION");
        attributes.put("knowledgeInitialTokenBudget", source.maxTokens());
        return new KnowledgeRequest(source.schemaVersion(), source.query(), source.taskType(),
            KnowledgeRequest.HARD_MAX_TOKENS,
            source.scope(), source.allowedSkillTypes(), attributes);
    }

    private KnowledgeContext expansionFallback(KnowledgeContext initial) {
        return withStatus(initial, initial.used()
            ? "evidence_expansion_partial" : "evidence_expansion_unavailable");
    }

    private List<KnowledgeIR> mergeEvidence(List<KnowledgeIR> initial, List<KnowledgeIR> expanded) {
        Map<String, KnowledgeIR> merged = new LinkedHashMap<>();
        List<KnowledgeIR> all = new ArrayList<>();
        if (initial != null) all.addAll(initial);
        if (expanded != null) all.addAll(expanded);
        for (KnowledgeIR unit : all) {
            if (unit == null) continue;
            String key = unit.knowledgeId() == null || unit.knowledgeId().isBlank()
                ? "unit-" + merged.size() : unit.knowledgeId();
            merged.putIfAbsent(key, unit);
        }
        return List.copyOf(merged.values());
    }

    private Future<SkillExecutionOutcome> submitSkill(KnowledgeRequest request, KnowledgeSkillInstance skill) {
        try {
            return knowledgeExecutor.submit(() -> executeSkill(request, skill));
        } catch (RejectedExecutionException ex) {
            log.warn("knowledgeSkillExecutionRejected instanceId={} type={}",
                skill.instanceId(), skill.skillType());
            return CompletableFuture.completedFuture(new SkillExecutionOutcome(List.of(), "rejected"));
        }
    }

    private SkillExecutionOutcome executeSkill(KnowledgeRequest request, KnowledgeSkillInstance skill) {
        for (KnowledgeSkillExecutorPort executor : findExecutors(skill)) {
            try {
                KnowledgeSkillResult result = executor.execute(new KnowledgeSkillExecutionContext(request, skill));
                if (result != null && !result.knowledgeUnits().isEmpty()) {
                    return new SkillExecutionOutcome(result.knowledgeUnits(), result.status());
                }
                if (result != null && result.status().startsWith("evidence_recovery_")) {
                    return new SkillExecutionOutcome(List.of(), result.status());
                }
            } catch (RuntimeException ex) {
                log.warn("knowledgeSkillExecutionFailed instanceId={} type={} executor={} error={}",
                    skill.instanceId(), skill.skillType(), executor.getClass().getSimpleName(), ex.getMessage());
            }
        }
        log.warn("knowledgeSkillEvidenceMissing instanceId={} type={}", skill.instanceId(), skill.skillType());
        return new SkillExecutionOutcome(List.of(), "empty");
    }

    private String aggregateEvidenceStatus(List<String> statuses, boolean evidenceUsed) {
        if (statuses == null || statuses.isEmpty()) return null;
        boolean exhausted = statuses.stream().anyMatch("evidence_recovery_exhausted"::equals);
        boolean partial = statuses.stream().anyMatch("evidence_recovery_partial"::equals);
        if (exhausted) return evidenceUsed ? "evidence_recovery_partial" : "evidence_recovery_exhausted";
        return partial ? "evidence_recovery_partial" : null;
    }

    private KnowledgeContext withStatus(KnowledgeContext context, String status) {
        return new KnowledgeContext(context.schemaVersion(), context.plan(), context.knowledgeUnits(),
            context.compiledContext(), context.sources(), context.estimatedTokens(), context.maxTokens(),
            context.truncated(), status);
    }

    private long requestSkillTimeoutMs(KnowledgeRequest request) {
        Object configured = request == null || request.attributes() == null
            ? null : request.attributes().get("knowledgeSkillTimeoutMs");
        long resolved = skillTimeoutMs;
        if (configured instanceof Number number) {
            resolved = number.longValue();
        } else if (configured != null) {
            try {
                resolved = Long.parseLong(String.valueOf(configured).trim());
            } catch (NumberFormatException ignored) {
                resolved = skillTimeoutMs;
            }
        }
        return Math.max(100L, Math.min(60_000L, resolved));
    }

    private List<KnowledgeSkillExecutorPort> findExecutors(KnowledgeSkillInstance skill) {
        return skillExecutors.stream().filter(executor -> executor.supports(skill.skillType())).toList();
    }

    private record RetrievalSnapshot(KnowledgeSkillPlan plan,
                                     List<KnowledgeIR> units,
                                     KnowledgeContext context) {
    }

    private record SkillExecutionOutcome(List<KnowledgeIR> units, String status) {
        private SkillExecutionOutcome {
            units = units == null ? List.of() : List.copyOf(units);
            status = status == null || status.isBlank() ? "empty" : status;
        }
    }
}
