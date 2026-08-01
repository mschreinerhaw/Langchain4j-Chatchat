package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import com.chatchat.runtime.news.tool.WebSearchToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in production integration test proving that Tencent WSA evidence crosses
 * the complete retrieval -> governed tool -> Agent observation/synthesis path.
 * Credentials are loaded from runtime-news application.yml and never printed.
 */
@EnabledIfSystemProperty(named = "chatchat.e2e.tencent-wsa.live", matches = "true")
class TencentWsaInferenceE2E {

    @Test
    void realTencentEvidenceIsActuallyUsedByAgentReasoning() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NewsRuntimeProperties news = loadNewsProperties();
        assertThat(news.getWebSearch().isEnabled()).as("Tencent WSA must be enabled").isTrue();
        assertThat(news.getWebSearch().getSecretId()).as("Tencent SecretId must be configured").isNotBlank();
        assertThat(news.getWebSearch().getSecretKey()).as("Tencent SecretKey must be configured").isNotBlank();

        // A release verification must prove a provider call, not a cache hit or local-news fallback.
        news.getOpenSearch().setEnabled(false);
        news.getWebSearch().getCache().setEnabled(false);
        news.getWebSearch().getCache().setForceExternal(true);
        TencentWebSearchClient client = new TencentWebSearchClient(mapper, news);
        WebSearchToolExecutor webSearch = new WebSearchToolExecutor(
            mock(NewsDocumentStore.class), news, client);

        String query = System.getProperty(
            "chatchat.e2e.tencent-wsa.query",
            "2026年8月最新人工智能行业动态与官方发布");
        ToolOutput liveOutput = webSearch.execute(ToolInput.builder()
            .requestId("wsa-live-e2e")
            .conversationId("wsa-live-e2e-conversation")
            .userId("wsa-live-e2e-user")
            .context(Map.of("tenantId", "wsa-live-e2e-tenant"))
            .parameters(Map.of("query", query, "num_results", 5))
            .build());

        assertThat(liveOutput.isSuccess())
            .as("real Tencent WSA call must succeed: %s", liveOutput.getErrorMessage()).isTrue();
        assertThat(liveOutput.getData()).isInstanceOf(Map.class);
        Map<String, Object> liveData = data(liveOutput);
        assertThat(liveData)
            .containsEntry("mode", "external_web_search")
            .containsEntry("externalProvider", "tencent-wsa")
            .containsEntry("webSearchCacheHit", false);
        assertThat(String.valueOf(liveData.get("externalRequestId"))).isNotBlank();
        assertThat(number(liveData.get("externalWebCount"))).isGreaterThan(0);
        List<Map<String, Object>> pages = results(liveData);
        assertThat(pages).isNotEmpty();
        assertThat(pages).allSatisfy(page -> {
            assertThat(String.valueOf(page.get("retrievalSource"))).isEqualTo("tencent_wsa");
            assertThat(String.valueOf(page.get("url"))).startsWith("http");
        });

        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        when(registry.getAllToolNames()).thenReturn(Set.of("web_search"));
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(
            liveOutput, null, null, "success", Map.of("provider", "tencent-wsa")));

        AtomicReference<String> synthesisPrompt = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        String firstUrl = String.valueOf(pages.get(0).get("url"));
        when(model.chat(any(String.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0, String.class);
            if (prompt.contains("JSON schema:")) {
                return "{\"needed\":true,\"keywords\":[\"" + jsonEscape(query)
                    + "\"],\"reason\":\"需要最新外部证据\"}";
            }
            synthesisPrompt.set(prompt);
            return "LIVE_WSA_EVIDENCE_USED [来源](" + firstUrl + ")";
        });

        AgentRuntimeProperties agent = new AgentRuntimeProperties();
        agent.setFinalSummaryWebSearchEnabled(true);
        FinalSummaryWebSearchEnhancer enhancer = new FinalSummaryWebSearchEnhancer(
            registry, runtime, mapper, agent);
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "wsa-live-agent-run",
            "requestId", "wsa-live-agent-request",
            "conversationId", "wsa-live-agent-conversation",
            "tenantId", "wsa-live-agent-tenant",
            "userId", "wsa-live-agent-user"));

        FinalSummaryWebSearchEnhancer.Enhancement result = enhancer.enhance(
            model,
            "请根据最新联网信息分析人工智能行业动态并给出建议",
            "仅使用有来源的事实，时效性事实必须联网检索。",
            "内部资料不足，暂时无法给出最新行情判断。",
            new ArrayList<>(List.of("内部知识库没有当前信息")),
            new ArrayList<>(),
            metadata);

        assertThat(result.attempted()).isTrue();
        assertThat(result.used()).isTrue();
        assertThat(result.enhancedAnswer()).contains("LIVE_WSA_EVIDENCE_USED", firstUrl);
        assertThat(result.observations()).anySatisfy(observation ->
            assertThat(observation).contains("Tencent WSA", firstUrl));
        assertThat(synthesisPrompt.get()).as("Agent synthesis prompt must contain live WSA evidence")
            .isNotBlank().contains(firstUrl, "New web evidence:");
        assertThat(metadata)
            .containsEntry("finalSummaryWebSearchAttempted", true)
            .containsEntry("finalSummaryWebSearchUsed", true)
            .containsEntry("finalSummaryWebSearchEvidenceCount", 1);
    }

    private NewsRuntimeProperties loadNewsProperties() throws Exception {
        String root = System.getProperty("chatchat.e2e.repository-root", ".");
        Path config = Path.of(root, "chatchat-runtime-news", "src", "main", "resources", "application.yml")
            .toAbsolutePath().normalize();
        ConfigurableEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("runtime-news", new FileSystemResource(config))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
            .bind("chatchat.runtime.news", Bindable.of(NewsRuntimeProperties.class))
            .orElseThrow(() -> new IllegalStateException("Missing chatchat.runtime.news configuration"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolOutput output) {
        return (Map<String, Object>) output.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.getOrDefault("results", List.of());
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
