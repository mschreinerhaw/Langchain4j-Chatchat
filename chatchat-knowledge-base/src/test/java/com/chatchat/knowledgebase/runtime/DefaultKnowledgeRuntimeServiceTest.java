package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.spi.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.spi.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.skill.KnowledgeSkillResult;
import com.chatchat.common.knowledge.spi.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;
import com.chatchat.common.knowledge.model.KnowledgeType;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.knowledgebase.runtime.workflow.KnowledgeEvidenceExpansionWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class DefaultKnowledgeRuntimeServiceTest {

    @Test
    void preservesPartialEvidenceRecoveryStateInKnowledgeContext() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort executor = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "procedure", KnowledgeSkillType.PROCEDURE_LOOKUP, "ops", "lookup procedure",
            List.of(), 1, 1_500, Map.of());
        when(planner.synthesize(any())).thenReturn(
            new KnowledgeSkillPlan("v", "PROCEDURE", List.of(skill), 1_500));
        when(executor.supports(KnowledgeSkillType.PROCEDURE_LOOKUP)).thenReturn(true);
        KnowledgeIR unit = new KnowledgeIR(
            "partial-unit", "ops", KnowledgeType.PROCEDURE, "Installation", "supported steps",
            List.of(), List.of(), List.of(), List.of(), "supported steps",
            new KnowledgeSourceReference("src", "doc", "chunk", "install.md", "Install", null, null), 0.9D);
        when(executor.execute(any())).thenReturn(new KnowledgeSkillResult(
            skill.instanceId(), skill.skillType(), List.of(unit), "evidence_recovery_partial", Map.of()));
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(executor), new BudgetedKnowledgeContextCompiler());

        KnowledgeContext result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "installation procedure", "PROCEDURE", 1_500,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of()),
            null, Map.of()));

        assertThat(result.used()).isTrue();
        assertThat(result.status()).isEqualTo("evidence_recovery_partial");
        assertThat(result.toRuntimeProjection())
            .containsEntry("completionState", "PARTIAL")
            .containsEntry("continuationRequired", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) result.toRuntimeProjection().get("usageContract");
        assertThat(usage).containsEntry("partialAnswerRequired", true);
    }

    @Test
    void treatsTruncationAsAControlSignalAndExpandsEvidenceBeforeReturning() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort executor = mock(KnowledgeSkillExecutorPort.class);
        KnowledgeContextCompilerPort compiler = mock(KnowledgeContextCompilerPort.class);
        KnowledgeEvidenceExpansionWorkflow expansionWorkflow = mock(KnowledgeEvidenceExpansionWorkflow.class);
        when(planner.synthesize(any())).thenAnswer(invocation -> {
            KnowledgeRequest request = invocation.getArgument(0);
            KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
                "procedure", KnowledgeSkillType.PROCEDURE_LOOKUP, "ops", "lookup procedure",
                List.of(), 1, request.maxTokens(), Map.of());
            return new KnowledgeSkillPlan("v", "PROCEDURE", List.of(skill), request.maxTokens());
        });
        when(executor.supports(KnowledgeSkillType.PROCEDURE_LOOKUP)).thenReturn(true);
        when(executor.execute(any())).thenAnswer(invocation -> {
            KnowledgeSkillExecutionContext execution = invocation.getArgument(0);
            KnowledgeIR unit = new KnowledgeIR(
                "procedure-unit", "ops", KnowledgeType.PROCEDURE, "Installation", "procedure evidence",
                List.of(), List.of(), List.of(), List.of(), "procedure evidence", null, 0.9D);
            return new KnowledgeSkillResult(execution.skill().instanceId(), execution.skill().skillType(),
                List.of(unit), "used", Map.of());
        });
        when(compiler.compile(any(), any(), any())).thenAnswer(invocation -> {
            KnowledgeRequest request = invocation.getArgument(0);
            KnowledgeSkillPlan plan = invocation.getArgument(1);
            boolean truncated = request.maxTokens() < KnowledgeRequest.HARD_MAX_TOKENS;
            return new KnowledgeContext(KnowledgeContext.SCHEMA_VERSION, plan, List.of(),
                "procedure evidence", List.of(), 10, request.maxTokens(), truncated, "used");
        });
        when(expansionWorkflow.expand(any(), any())).thenReturn(
            new KnowledgeEvidenceExpansionWorkflow.ExpansionResult(
                true, true, List.of(new KnowledgeIR(
                    "expanded-procedure", "ops", KnowledgeType.PROCEDURE, "Installation",
                    "complete procedure evidence", List.of(), List.of(), List.of(), List.of(),
                    "complete procedure evidence", null, 0.9D)), "COMPLETE"));
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(executor), compiler, expansionWorkflow);

        KnowledgeContext result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "installation procedure", "PROCEDURE", 1500,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of()),
            null, Map.of()));

        ArgumentCaptor<KnowledgeRequest> requests = ArgumentCaptor.forClass(KnowledgeRequest.class);
        verify(compiler, org.mockito.Mockito.times(2)).compile(requests.capture(), any(), any());
        assertThat(requests.getAllValues()).extracting(KnowledgeRequest::maxTokens)
            .containsExactly(1500, KnowledgeRequest.HARD_MAX_TOKENS);
        assertThat(requests.getAllValues().get(1).attributes())
            .containsEntry("knowledgeEvidenceExpansion", true)
            .containsEntry("knowledgeEvidenceExpansionTrigger", "CONTEXT_TRUNCATED")
            .containsEntry("knowledgeEvidenceExpansionWorkflow", "DOCUMENT_SECTION_EXPANSION")
            .containsEntry("knowledgeInitialTokenBudget", 1500);
        verify(planner).synthesize(any());
        verify(executor).execute(any());
        verify(expansionWorkflow).expand(any(), any());
        assertThat(result.truncated()).isFalse();
        assertThat(result.maxTokens()).isEqualTo(KnowledgeRequest.HARD_MAX_TOKENS);
    }

    @Test
    void rechecksSkillDocumentScopeBeforeCompilingEvidence() {
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillExecutorPort executor = mock(KnowledgeSkillExecutorPort.class);
        SkillExecutionScopePort authorization = mock(SkillExecutionScopePort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "rule", KnowledgeSkillType.RULE_LOOKUP, "risk", "lookup", List.of(), 1, 200, Map.of());
        when(planner.synthesize(any())).thenReturn(new KnowledgeSkillPlan("v", "RISK", List.of(skill), 200));
        when(executor.supports(KnowledgeSkillType.RULE_LOOKUP)).thenReturn(true);
        KnowledgeIR unit = new KnowledgeIR("unit", "risk", KnowledgeType.RULE, "Rule", "Rule text",
            List.of(), List.of(), List.of(), List.of(), "Rule text",
            new KnowledgeSourceReference("src", "doc-revoked", "chunk", "doc", null, null, null), 0.8);
        when(executor.execute(any())).thenReturn(new KnowledgeSkillResult(
            skill.instanceId(), skill.skillType(), List.of(unit), "used", Map.of()));
        when(authorization.resolve("tenant", "user", "agent", List.of("doc-revoked"), List.of()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(
                List.of("doc-allowed"), List.of(), List.of(), true, true));
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(executor), new BudgetedKnowledgeContextCompiler());
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "skillExecutionScope", authorization);

        var result = runtime.retrieveKnowledge(new KnowledgeRequest(
            "v", "lookup", "RISK", 200,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc-revoked"), List.of(), List.of()),
            null, Map.of("skillScopeManaged", true)));

        assertThat(result.knowledgeUnits()).isEmpty();
    }

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
