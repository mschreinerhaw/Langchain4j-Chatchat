package com.chatchat.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentPropertiesNamingTest {

    @Test
    void envPropertiesNamesResolveToDevYamlProperties() {
        SystemEnvironmentPropertySource source = new SystemEnvironmentPropertySource("test-env", Map.of(
            "CHATCHAT_MODELS_CONTEXT_WINDOW_MAX_TOKENS", "210000",
            "CHATCHAT_AGENT_RUNTIME_FINAL_SUMMARY_WEB_SEARCH_ENABLED", "false",
            "CHATCHAT_SEARCH_OPENSEARCH_MAX_QUERY_TERMS", "25",
            "CHATCHAT_CHAT_DETAIL_STORE_PATH", "./data/custom-chat",
            "CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL", "http://news:8091",
            "CHATCHAT_MCP_LUCENE_OPEN_SEARCH_SEARCH_CONCURRENCY_REQUEST_TIMEOUT_MS", "9000",
            "CHATCHAT_LICENSE_LICENSE_FILE", "./license/custom.dat"
        ));

        assertThat(source.getProperty("chatchat.models.context-window-max-tokens")).isEqualTo("210000");
        assertThat(source.getProperty("chatchat.agent-runtime.final-summary-web-search-enabled"))
            .isEqualTo("false");
        assertThat(source.getProperty("chatchat.search.opensearch.max-query-terms")).isEqualTo("25");
        assertThat(source.getProperty("chatchat.chat.detail-store.path")).isEqualTo("./data/custom-chat");
        assertThat(source.getProperty("chatchat.mcp.news-runtime.base-url")).isEqualTo("http://news:8091");
        assertThat(source.getProperty(
            "chatchat.mcp.lucene.open-search.search-concurrency.request-timeout-ms")).isEqualTo("9000");
        assertThat(source.getProperty("chatchat.license.license-file")).isEqualTo("./license/custom.dat");
    }
}
