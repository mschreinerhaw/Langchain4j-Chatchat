package com.chatchat.mcpserver.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchTemplateQuerySafetyTest {

    @Test
    void longMixedLanguageIntentIsBoundedAndDoesNotUsePinyinExpansion() throws Exception {
        OpenSearchMcpSearchService service = service();
        String intent = ("融资融券数据观察 margin trading observation ").repeat(100)
            + "query_margin_trade_latest";

        Map<String, Object> body = templateQueryBody(service,
            new LuceneMcpSearchService.TemplateSearchRequest(
                "database_query", null, intent, 20));
        String json = new ObjectMapper().writeValueAsString(body);

        assertThat(json).doesNotContain(".pinyin");
        assertThat(json).contains("融资融券数据观察", "database_query");
        assertThat(extractedQuery(json).length()).isLessThanOrEqualTo(512);
    }

    @Test
    void compactPinyinIntentKeepsPinyinRecallFields() throws Exception {
        Map<String, Object> body = templateQueryBody(service(),
            new LuceneMcpSearchService.TemplateSearchRequest(
                "database_query", null, "rong zi rong quan", 20));

        assertThat(new ObjectMapper().writeValueAsString(body))
            .contains("intentText.pinyin", "text.pinyin", "keywordAliases");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> templateQueryBody(
        OpenSearchMcpSearchService service,
        LuceneMcpSearchService.TemplateSearchRequest request
    ) throws Exception {
        Method method = OpenSearchMcpSearchService.class
            .getDeclaredMethod("templateQueryBody", LuceneMcpSearchService.TemplateSearchRequest.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, request);
    }

    @SuppressWarnings("unchecked")
    private String extractedQuery(String json) throws Exception {
        Map<String, Object> root = new ObjectMapper().readValue(json, Map.class);
        Map<String, Object> bool = (Map<String, Object>) root.get("bool");
        List<Map<String, Object>> must = (List<Map<String, Object>>) bool.get("must");
        Map<String, Object> multiMatch = (Map<String, Object>) must.get(must.size() - 1).get("multi_match");
        return String.valueOf(multiMatch.get("query"));
    }

    private OpenSearchMcpSearchService service() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        return new OpenSearchMcpSearchService(
            properties,
            new McpEmbeddingClient(properties, new ObjectMapper()),
            new ObjectMapper()
        );
    }
}
