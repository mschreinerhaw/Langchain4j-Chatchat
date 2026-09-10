package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillResult;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.common.knowledge.KnowledgeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultKnowledgeRuntimeServiceTest {

    @Test
    void timesOutOneSlowSkillWithoutBlockingTheKnowledgeResponse() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort slowExecutor = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "slow", KnowledgeSkillType.RULE_LOOKUP, "risk", "slow lookup", List.of(), 1, 200, Map.of());
        when(planner.synthesize(any())).thenReturn(new KnowledgeSkillPlan("v", "RISK", List.of(skill), 200));
        when(slowExecutor.supports(KnowledgeSkillType.RULE_LOOKUP)).thenReturn(true);
        when(slowExecutor.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(500L);
            return KnowledgeSkillResult.empty(skill, "late");
        });
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(slowExecutor), new BudgetedKnowledgeContextCompiler());
        long startedAt = System.nanoTime();

        var result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "analyze risk", "RISK", 200,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of()),
            null, Map.of("knowledgeSkillTimeoutMs", 100)));

        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(elapsedMs).isLessThan(450L);
        assertThat(result.used()).isFalse();
    }

    @Test
    void prefersNativeIrAndDoesNotQueryLegacyDocumentsWhenKnowledgeExists() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort nativeIr = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillExecutorPort legacy = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "rule", KnowledgeSkillType.RULE_LOOKUP, "risk", "lookup rules", List.of(), 1, 200, Map.of());
        when(planner.synthesize(any())).thenReturn(new KnowledgeSkillPlan("v", "RISK", List.of(skill), 200));
        when(nativeIr.supports(KnowledgeSkillType.RULE_LOOKUP)).thenReturn(true);
        KnowledgeIR nativeUnit = new KnowledgeIR(
            "native", "risk", KnowledgeType.RULE, "Risk rule", "Risk decision rule",
            List.of(), List.of(), List.of(), List.of(), "Risk decision rule", null, 0.8);
        when(nativeIr.execute(any())).thenReturn(new KnowledgeSkillResult(
            skill.instanceId(), skill.skillType(), List.of(nativeUnit), "used", Map.of()));
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(nativeIr, legacy), new BudgetedKnowledgeContextCompiler());

        var result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "analyze risk", "RISK", 200,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of()),
            null, Map.of()));

        verify(nativeIr).execute(any());
        verify(legacy, never()).execute(any());
        assertThat(result.knowledgeUnits()).extracting(KnowledgeIR::knowledgeId).containsExactly("native");
    }

    @Test
    void fallsBackToLegacyExecutorOnlyWhenNativeIrHasNoKnowledge() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort nativeIr = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillExecutorPort legacy = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "rule", KnowledgeSkillType.RULE_LOOKUP, "risk", "获取风险规则", List.of(), 1, 200, Map.of());
        when(planner.synthesize(any())).thenReturn(new KnowledgeSkillPlan("v", "RISK", List.of(skill), 200));
        when(nativeIr.supports(KnowledgeSkillType.RULE_LOOKUP)).thenReturn(true);
        when(legacy.supports(KnowledgeSkillType.RULE_LOOKUP)).thenReturn(true);
        when(nativeIr.execute(any())).thenReturn(KnowledgeSkillResult.empty(skill, "empty"));
        KnowledgeIR fallbackUnit = new KnowledgeIR(
            "legacy", "risk", KnowledgeType.RULE, "风险规则", "风险判断规则",
            List.of(), List.of(), List.of(), List.of(), "风险判断规则", null, 0.5);
        when(legacy.execute(any())).thenReturn(new KnowledgeSkillResult(
            skill.instanceId(), skill.skillType(), List.of(fallbackUnit), "used", Map.of()));
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(nativeIr, legacy), new BudgetedKnowledgeContextCompiler());

        var result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "分析风险", "RISK", 200,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of()),
            null, Map.of()));

        verify(nativeIr).execute(any());
        verify(legacy).execute(any());
        assertThat(result.knowledgeUnits()).extracting(KnowledgeIR::knowledgeId).containsExactly("legacy");
    }
}
