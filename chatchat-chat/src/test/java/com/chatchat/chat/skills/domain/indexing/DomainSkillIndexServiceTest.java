package com.chatchat.chat.skills.domain.indexing;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainSkillIndexServiceTest {
    @Test void longSqlRecallIsBoundedAndRetainsAuthorizationFilters() throws Exception {
        var mapper = new ObjectMapper();
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"hits\":{\"hits\":[{\"_source\":{\"skillId\":\"authorized-skill\"}}]}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var config = new SearchProperties();
            config.setEngine("opensearch");
            config.getOpenSearch().setEnabled(true);
            config.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.getOpenSearch().getEmbedding().setEnabled(false);
            var embedding = mock(OpenSearchEmbeddingClient.class);
            String query = "分析以下SQL并解释字段映射 " + "CREATE TABLE t AS SELECT customer_id, amount FROM orders; ".repeat(1000);
            when(embedding.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
            var service = new DomainSkillIndexService(new DomainSkillProperties(), config, embedding, mapper);
            assertThat(service.searchIds(query, List.of("authorized-skill"), 3)).containsExactly("authorized-skill");
            var bool = mapper.readTree(captured.get()).path("query").path("bool");
            var match = bool.path("must").get(0).path("multi_match");
            String bounded = match.path("query").asText();
            assertThat(bounded).isNotEqualTo(query);
            assertThat(bounded.length()).isLessThan(query.length());
            assertThat(match.path("analyzer").asText()).isEqualTo("standard");
            assertThat(bool.path("filter").toString()).contains("authorized-skill", "PUBLISHED");
            verify(embedding).embed(bounded); // Both channels reuse the accepted full-input extraction.
        } finally { server.stop(0); }
    }

    @Test void trailingEntitiesSurviveInsteadOfBeingPrefixTruncated() {
        try (var optimizer = new com.chatchat.common.retrieval.query.SearchQueryOptimizer()) {
            assertThat(optimizer.text("short query", 4)).isEqualTo("short query");
            String extracted = optimizer.text("请分析这些信息。".repeat(100) + " 600519 “现金流”", 4);
            assertThat(extracted).contains("600519", "现金流");
        }
    }
}
