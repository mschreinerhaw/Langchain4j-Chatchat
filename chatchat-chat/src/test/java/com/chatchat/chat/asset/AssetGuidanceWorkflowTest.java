package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.asset.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetGuidanceWorkflowTest {
    final AssetGuidanceSource source = mock(AssetGuidanceSource.class);
    final AssetGuidanceEnhancer enhancer = mock(AssetGuidanceEnhancer.class);
    final SkillExecutionScopePort scopes = mock(SkillExecutionScopePort.class);
    final ObjectProvider<AgentRunEventPublisher> events = mock(ObjectProvider.class);
    AssetGuidanceWorkflow workflow() {
        when(events.orderedStream()).thenAnswer(invocation -> Stream.empty());
        when(scopes.resolve("t", "u", "agent", List.of(), List.of())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of("trusted-role"), false, true));
        return new AssetGuidanceWorkflow(source, enhancer, scopes, events, new ObjectMapper());
    }
    AnalysisContext context(String query) {
        return new AnalysisContext(query, new KernelDataScope("t", "u", "r", null, "run", null, Map.of()),
            "agent", List.of(), List.of(), List.of("untrusted-role"), null, Map.of());
    }
    AssetContext asset(String id) {
        return new AssetContext(id, "api_service", id, "客户持仓模板", Map.of("parameterSchema", Map.of()), Map.of(), Map.of(), "discovery", Map.of());
    }
    @Test void fixedTemplateIncludesFactsMissingEvidenceAndSkillSuggestions() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("position")), List.of(), false));
        when(enhancer.recommend(any(), any())).thenReturn(new AssetGuidanceEnhancer.Recommendation("建议用于持仓分析，需确认输出契约。", List.of("skill"), "APPLIED"));
        var result = workflow.execute(context("position API 怎么用"));
        assertThat(result.synthesis()).contains("## 1.", "## 2.", "## 3.", "## 4.", "## 5.", "暂无可验证", "建议用于持仓分析");
        assertThat(result.metadata()).containsEntry("templateExecutionExecuted", false).containsEntry("status", "PARTIAL");
        verify(source).retrieve(argThat(ctx -> ctx.roles().equals(List.of("trusted-role"))));
    }
    @Test void ambiguousCandidatesAskForSelectionWithoutInvokingSkills() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("a"), asset("b")), List.of(), false));
        assertThat(workflow.execute(context("这个接口怎么用")).metadata()).containsEntry("status", "NEEDS_SELECTION");
        verifyNoInteractions(enhancer);
    }
    @Test void unknownExplicitTemplateMustNotFallBackToDifferentAsset() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("a")), List.of(), false));
        assertThat(workflow.execute(context("接口怎么用").withAttribute("assetTemplateId", "missing")).metadata())
            .containsEntry("status", "NEEDS_SELECTION");
        verifyNoInteractions(enhancer);
    }
    @Test void noMetadataReturnsLimitationNotFabricatedFacts() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(), List.of("未授权模板"), false));
        var result = workflow.execute(context("这个 API 怎么用"));
        assertThat(result.verification().accepted()).isFalse();
        assertThat(result.synthesis()).contains("无法确认", "未授权模板");
        verifyNoInteractions(enhancer);
    }
    @Test void unauthorizedAgentNeverQueriesMetadata() {
        var workflow = workflow();
        when(scopes.resolve(any(), any(), any(), any(), any())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), false, false));
        assertThatThrownBy(() -> workflow.execute(context("API 怎么用"))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(source, enhancer);
    }
}
