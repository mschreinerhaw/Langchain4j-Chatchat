package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeContext;
import com.chatchat.common.knowledge.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeRuntimePort;
import com.chatchat.common.knowledge.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillResult;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final ExecutorService knowledgeExecutor;

    public DefaultKnowledgeRuntimeService(KnowledgeSkillSynthesizerPort skillSynthesizer,
                                          List<KnowledgeSkillExecutorPort> skillExecutors,
                                          KnowledgeContextCompilerPort contextCompiler) {
        this(skillSynthesizer, skillExecutors, contextCompiler, ForkJoinPool.commonPool());
    }

    @Autowired
    public DefaultKnowledgeRuntimeService(KnowledgeSkillSynthesizerPort skillSynthesizer,
                                          List<KnowledgeSkillExecutorPort> skillExecutors,
                                          KnowledgeContextCompilerPort contextCompiler,
                                          @Qualifier("knowledgeRuntimeExecutor") ExecutorService knowledgeExecutor) {
        this.skillSynthesizer = skillSynthesizer;
        this.skillExecutors = skillExecutors;
        this.contextCompiler = contextCompiler;
        this.knowledgeExecutor = knowledgeExecutor;
    }

    @Value("${chatchat.knowledge.runtime.skill-timeout-ms:15000}")
    private long skillTimeoutMs = 15_000L;

    @Value("${chatchat.knowledge.runtime.total-timeout-ms:18000}")
    private long totalTimeoutMs = 18_000L;

    @Override
    public KnowledgeContext retrieveKnowledge(KnowledgeRequest request) {
        KnowledgeSkillPlan plan = skillSynthesizer.synthesize(request);
        List<KnowledgeIR> units = new ArrayList<>();
        long effectiveSkillTimeoutMs = requestSkillTimeoutMs(request);
        List<Future<List<KnowledgeIR>>> executions = plan.skills().stream()
            .map(skill -> submitSkill(request, skill))
            .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, totalTimeoutMs));
        for (int index = 0; index < executions.size(); index++) {
            KnowledgeSkillInstance skill = plan.skills().get(index);
            Future<List<KnowledgeIR>> execution = executions.get(index);
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
                units.addAll(execution.get(waitMs, TimeUnit.MILLISECONDS));
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
        return contextCompiler.compile(request, plan, units);
    }

    private Future<List<KnowledgeIR>> submitSkill(KnowledgeRequest request, KnowledgeSkillInstance skill) {
        try {
            return knowledgeExecutor.submit(() -> executeSkill(request, skill));
        } catch (RejectedExecutionException ex) {
            log.warn("knowledgeSkillExecutionRejected instanceId={} type={}",
                skill.instanceId(), skill.skillType());
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private List<KnowledgeIR> executeSkill(KnowledgeRequest request, KnowledgeSkillInstance skill) {
        for (KnowledgeSkillExecutorPort executor : findExecutors(skill)) {
            try {
                KnowledgeSkillResult result = executor.execute(new KnowledgeSkillExecutionContext(request, skill));
                if (result != null && !result.knowledgeUnits().isEmpty()) {
                    return result.knowledgeUnits();
                }
            } catch (RuntimeException ex) {
                log.warn("knowledgeSkillExecutionFailed instanceId={} type={} executor={} error={}",
                    skill.instanceId(), skill.skillType(), executor.getClass().getSimpleName(), ex.getMessage());
            }
        }
        log.warn("knowledgeSkillEvidenceMissing instanceId={} type={}", skill.instanceId(), skill.skillType());
        return List.of();
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
}
