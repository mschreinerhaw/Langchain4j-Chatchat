package com.chatchat.runtime.news;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:news-smoke;MODE=MySQL",
    "chatchat.internal-credential.secret=test-secret",
    "chatchat.runtime.news.open-search.enabled=false"
})
class NewsRuntimeStandaloneSmokeTest {
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;

    @Test
    void protectsInternalApiAndAcceptsConfiguredInternalAccount() {
        assertThat(rest.getForEntity("/internal/v1/news/health", String.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        var response = rest.withBasicAuth("chatchat_mcp_internal", "test-secret")
            .getForEntity("/internal/v1/news/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("chatchat-runtime-news", "UP");

        var records = rest.withBasicAuth("chatchat_mcp_internal", "test-secret")
            .getForEntity("/internal/v1/news/records?page=0&size=20", String.class);
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(records.getBody()).contains("\"items\":[]", "\"total\":0");
    }

    @Test
    void bindsExplicitSourcePathVariableWithoutCompilerParameterMetadata() throws Exception {
        var client = rest.withBasicAuth("chatchat_mcp_internal", "test-secret");
        var created = client.postForEntity("/internal/v1/news/sources", Map.of(
            "sourceCode", "path-variable-smoke",
            "sourceName", "Path Variable Smoke",
            "sourceType", "WEB_SINGLE_PAGE",
            "entryUrl", "https://example.com/news",
            "enabled", false,
            "configuration", Map.of()
        ), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long sourceId = objectMapper.readTree(created.getBody()).path("data").path("id").asLong();

        var rule = client.getForEntity("/internal/v1/news/sources/" + sourceId + "/rule", String.class);
        assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rule.getBody()).contains("\"sourceId\":" + sourceId);
    }

    @Test
    void persistsNewDedicatedSourceTypesAsVarchar() {
        var created = rest.withBasicAuth("chatchat_mcp_internal", "test-secret").postForEntity(
            "/internal/v1/news/sources", Map.of(
                "sourceCode", "cninfo-enum-smoke",
                "sourceName", "CNINFO Enum Smoke",
                "sourceType", "CNINFO_ANNOUNCEMENTS",
                "entryUrl", "https://www.cninfo.com.cn/new/index",
                "enabled", false,
                "configuration", Map.of()
            ), String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).contains("CNINFO_ANNOUNCEMENTS");
    }
}
