package com.chatchat.api.runtime;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisEvidenceSearchIndexTest {
    @Test void indexesOnlyOwnerScopedMetadataWithoutEvidenceContent() throws Exception {
        var body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("PUT") && exchange.getRequestURI().getPath().contains("/_doc/"))
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
                else {
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new SearchProperties();
            properties.setEngine("opensearch");
            properties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var index = new AnalysisEvidenceSearchIndex(properties, new ObjectMapper(), "analysis-evidence-test");
            var entity = new AnalysisEvidenceArchiveEntity("archive-1", "tenant", "user", "run",
                "sha", 22, "SECRET_EVIDENCE_BODY");

            index.index(entity);

            assertThat(body.get()).contains("archive-1", "tenant", "user", "run", "sha")
                .doesNotContain("SECRET_EVIDENCE_BODY", "bundleJson");
        } finally { server.stop(0); }
    }
}
