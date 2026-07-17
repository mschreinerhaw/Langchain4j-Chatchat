package com.chatchat.runtime.news;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:news-smoke;MODE=MySQL",
    "chatchat.internal-credential.secret=test-secret",
    "chatchat.runtime.news.open-search.enabled=false"
})
class NewsRuntimeStandaloneSmokeTest {
    @Autowired TestRestTemplate rest;

    @Test
    void protectsInternalApiAndAcceptsConfiguredInternalAccount() {
        assertThat(rest.getForEntity("/internal/v1/news/health", String.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        var response = rest.withBasicAuth("chatchat_mcp_internal", "test-secret")
            .getForEntity("/internal/v1/news/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("chatchat-runtime-news", "UP");
    }
}
