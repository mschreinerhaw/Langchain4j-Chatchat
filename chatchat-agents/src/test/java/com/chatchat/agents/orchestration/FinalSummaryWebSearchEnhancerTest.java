package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinalSummaryWebSearchEnhancerTest {

    @Test
    void skipsRetrievalWhenModelSaysExistingAnswerIsSufficient() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_news_web_search"));
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":false,\"keywords\":[],\"reason\":\"Evidence is complete\"}");

        var result = enhancer(registry, runtime).enhance(
            model, "解释已有结果", "", "已有证据足够。", List.of("完整内部证据"),
            List.of(), new LinkedHashMap<>());

        assertThat(result.attempted()).isFalse();
        assertThat(result.used()).isFalse();
        assertThat(result.enhancedAnswer()).isNull();
        verify(runtime, never()).execute(any());
    }

    @Test
    void agentSettingForcesFinancialRetrievalWhenModelIntentSaysNo() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_news_web_search"));
        when(registry.getToolMetadata("mcp_news_web_search")).thenReturn(ToolMetadata.builder()
            .id("mcp_news_web_search")
            .parameters(List.of(ToolParameter.builder().name("financial_data_required").type("boolean").build()))
            .build());
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":false,\"financialDataRequired\":false,\"keywords\":[],\"reason\":\"model skipped\"}");
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.failure("provider unavailable"), null, null, "failed", Map.of()));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "forced-financial-run",
            "forceStructuredFinancialData", true
        ));

        var result = enhancer(registry, runtime).enhance(
            model, "analyze today's market", "", "candidate", List.of(), List.of(), metadata);

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(runtime).execute(captor.capture());
        assertThat(result.attempted()).isTrue();
        assertThat(captor.getValue().getToolInput().getParameters())
            .containsEntry("financial_data_required", true);
        assertThat(metadata)
            .containsEntry("finalSummaryFinancialDataModelRequired", false)
            .containsEntry("finalSummaryFinancialDataForced", true)
            .containsEntry("finalSummaryFinancialDataRequired", true)
            .containsEntry("finalSummaryFinancialDataDecisionSource", "AGENT_SETTING");
    }

    @Test
    void retrievesTencentEvidenceAndCreatesASecondSummaryCandidate() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_news_web_search"));
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":true,\"keywords\":[\"杭州西湖 今日动态\"],\"reason\":\"需要当前地点信息\"}",
            "结合最新公开信息，西湖景区今日开放情况如下：[来源](https://example.com/west-lake)");
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_news_web_search").success(true).output("wsa evidence").build();
        ToolOutput output = ToolOutput.success(Map.of(
            "mode", "hybrid_news_and_web",
            "externalProvider", "tencent-wsa",
            "results", List.of(Map.of(
                "title", "西湖景区动态",
                "url", "https://example.com/west-lake",
                "snippet", "景区今日正常开放",
                "retrievalSource", "tencent_wsa"))));
        when(runtime.execute(any())).thenReturn(
            new ToolRuntimeExecution(output, null, trace, "success", Map.of()));
        Map<String, Object> metadata = new LinkedHashMap<>();

        var result = enhancer(registry, runtime).enhance(
            model, "西湖今天开放吗", "", "暂无法确认。", List.of("内部资料未包含今日开放信息"),
            new ArrayList<>(), metadata);

        assertThat(result.attempted()).isTrue();
        assertThat(result.used()).isTrue();
        assertThat(result.enhancedAnswer()).contains("西湖景区今日开放", "https://example.com/west-lake");
        assertThat(result.observations()).anyMatch(value ->
            value.contains("Tencent WSA") && value.contains("景区今日正常开放"));
        assertThat(result.traces()).containsExactly(trace);
        assertThat(metadata).containsEntry("finalSummaryWebSearchUsed", true)
            .containsEntry("finalSummaryWebSearchEvidenceCount", 1);
    }

    @Test
    void doesNotCreateEnhancedCandidateWhenOnlyLocalNewsWasReturned() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("web_search"));
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":true,\"keywords\":[\"当前热点\"],\"reason\":\"需要时效信息\"}");
        ToolOutput output = ToolOutput.success(Map.of(
            "mode", "news_index",
            "results", List.of(Map.of("title", "本地新闻", "retrievalSource", "news_index"))));
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(
            output, null, InteractionToolTrace.builder().toolName("web_search").success(true).build(),
            "success", Map.of()));

        var result = enhancer(registry, runtime).enhance(
            model, "当前热点", "", "原答案", List.of(), List.of(), new LinkedHashMap<>());

        assertThat(result.attempted()).isTrue();
        assertThat(result.used()).isFalse();
        assertThat(result.enhancedAnswer()).isNull();
        verify(model).chat(any(String.class));
    }

    @Test
    void propagatesCallerAuthorizationContextToInternalWebSearch() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_vendor_web_search"));
        when(registry.getToolMetadata("mcp_vendor_web_search")).thenReturn(ToolMetadata.builder()
            .id("mcp_vendor_web_search")
            .parameters(List.of(ToolParameter.builder().name("financial_data_required").type("boolean").build()))
            .build());
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":true,\"financialDataRequired\":true,\"keywords\":[\"latest market\"],\"reason\":\"current data\"}");
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.failure("provider unavailable"), null, null, "failed", Map.of()));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "run-ctx",
            "requestId", "request-ctx",
            "conversationId", "conversation-ctx",
            "tenantId", "tenant-ctx",
            "userId", "user-ctx"
        ));

        enhancer(registry, runtime).enhance(
            model, "analyze latest market", "", "internal data is incomplete",
            List.of(), List.of(), metadata);

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(runtime).execute(captor.capture());
        ToolRuntimeRequest request = captor.getValue();
        assertThat(request.getRequestId()).isEqualTo("request-ctx");
        assertThat(request.getConversationId()).isEqualTo("conversation-ctx");
        assertThat(request.getTenantId()).isEqualTo("tenant-ctx");
        assertThat(request.getUserId()).isEqualTo("user-ctx");
        assertThat(request.getToolInput().getContext())
            .containsEntry("tenantId", "tenant-ctx")
            .containsEntry("userId", "user-ctx");
        assertThat(request.getToolInput().getParameters())
            .containsEntry("financial_data_required", true)
            .containsEntry("financial_dataset_limit", 2)
            .containsEntry("financial_row_limit", 20);
        assertThat(metadata).containsEntry("finalSummaryFinancialDataRequired", true);
    }

    @Test
    void financialEvidenceGapStillUsesExternalWebWhenWebToolHasNoStructuredParameter() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_runtime_web_search"));
        when(registry.getToolMetadata("mcp_runtime_web_search")).thenReturn(ToolMetadata.builder()
            .id("mcp_runtime_web_search").parameters(List.of()).build());
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":true,\"financialDataRequired\":true,\"keywords\":[\"missing index close\"],"
                + "\"reason\":\"one market observation is missing\"}",
            "The missing observation was supplemented from the external source. "
                + "[source](https://example.test/index-close)");
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_runtime_web_search").success(true).output("external evidence").build();
        ToolOutput output = ToolOutput.success(Map.of(
            "mode", "hybrid_news_and_web",
            "results", List.of(Map.of(
                "title", "Runtime index close",
                "url", "https://example.test/index-close",
                "snippet", "The requested close was published by the external source.",
                "retrievalSource", "tencent_wsa"))));
        when(runtime.execute(any())).thenReturn(
            new ToolRuntimeExecution(output, null, trace, "success", Map.of()));
        Map<String, Object> metadata = new LinkedHashMap<>();

        var result = enhancer(registry, runtime).enhance(
            model, "complete the index table", "", "one row is missing",
            List.of("local structured data returned the other rows"), List.of(), metadata);

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(runtime).execute(captor.capture());
        assertThat(captor.getValue().getToolInput().getParameters())
            .containsEntry("financial_data_required", false);
        assertThat(result.attempted()).isTrue();
        assertThat(result.used()).isTrue();
        assertThat(result.enhancedAnswer()).contains("https://example.test/index-close");
        assertThat(metadata)
            .containsEntry("finalSummaryFinancialDataRequired", true)
            .containsEntry("finalSummaryWebFinancialDataRequiredEffective", false)
            .containsEntry("finalSummaryWebSearchCapabilityFallback",
                "external_web_only_structured_financial_parameter_unavailable")
            .containsEntry("finalSummaryWebSearchUsed", true);
    }

    @Test
    void finalizerQualityLayerCanPreferTheWebEnhancedCandidate() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("mcp_news_web_search"));
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":true,\"keywords\":[\"北京今日天气预警\"],\"reason\":\"原答案缺少实时信息\"}",
            "北京今日存在高温预警，建议减少午后户外活动。[来源](https://example.com/weather)",
            """
                {"preferredId":"final_summary_web_enhancement_1","reason":"联网证据补足了实时信息",
                 "synthesizedAnswer":"",
                 "candidates":[
                   {"id":"candidate","score":0.3,"accuracy":0.4,"grounding":0.4,"completeness":0.3,"citation":0.2,"usefulness":0.3},
                   {"id":"final_summary_web_enhancement_1","score":0.98,"accuracy":0.98,"grounding":0.98,
                    "completeness":0.98,"citation":1.0,"usefulness":0.98}
                 ]}
                """);
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_news_web_search").success(true)
            .output("{\"retrievalSource\":\"tencent_wsa\",\"url\":\"https://example.com/weather\"}")
            .build();
        ToolOutput output = ToolOutput.success(Map.of(
            "mode", "hybrid_news_and_web",
            "results", List.of(Map.of(
                "title", "北京高温预警",
                "url", "https://example.com/weather",
                "snippet", "气象台发布高温预警",
                "retrievalSource", "tencent_wsa"))));
        when(runtime.execute(any())).thenReturn(
            new ToolRuntimeExecution(output, null, trace, "success", Map.of()));
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            (chatModel, query, systemPrompt, observations, answer) ->
                new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok"),
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt"),
            null, registry, runtime, new ObjectMapper(), properties);

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            model,
            "北京今天出行需要注意什么",
            "",
            List.of(),
            new LinkedHashMap<>(),
            List.of("内部资料没有今天的天气预警"),
            "当前证据只能提供一般出行建议，无法确认今天的实时预警。",
            () -> false,
            "completed");

        assertThat(result.answer()).contains("高温预警", "https://example.com/weather");
        assertThat(result.metadata()).containsEntry("finalSummaryWebSearchUsed", true)
            .containsEntry("answerQualitySelectedId", "final_summary_web_enhancement_1");
        assertThat(result.toolTraces()).contains(trace);
    }

    private FinalSummaryWebSearchEnhancer enhancer(ToolRegistry registry, ToolRuntimeService runtime) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setFinalSummaryWebSearchEnabled(true);
        return new FinalSummaryWebSearchEnhancer(registry, runtime, new ObjectMapper(), properties);
    }
}
