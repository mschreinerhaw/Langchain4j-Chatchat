package com.chatchat.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-process release probes. No application class or service is instantiated in this JVM. */
@EnabledIfSystemProperty(named = "chatchat.e2e.deployed-topology.live", matches = "true")
class ProductionDeployedTopologyE2E {

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deployedApiMcpAndNewsRuntimeAreReachableAcrossNetworkBoundaries() throws Exception {
        JsonNode apiHealth = getJson(required("chatchat.e2e.api-base-url") + "/api/v1/health", null, null);
        assertThat(apiHealth.toString()).contains("UP");

        JsonNode apiMcp = getJson(required("chatchat.e2e.api-base-url") + "/api/v1/mcp/center/status",
            optional("chatchat.e2e.api-auth-header"), optional("chatchat.e2e.api-auth-value"));
        assertThat(apiMcp.path("code").asInt()).isEqualTo(200);

        JsonNode mcpServices = getJson(required("chatchat.e2e.mcp-base-url") + "/api/v1/mcp-services",
            optional("chatchat.e2e.mcp-auth-header"), optional("chatchat.e2e.mcp-auth-value"));
        assertThat(mcpServices.path("code").asInt()).isEqualTo(200);

        JsonNode newsHealth = getJson(required("chatchat.e2e.news-base-url") + "/internal/v1/news/health",
            optional("chatchat.e2e.news-auth-header"), optional("chatchat.e2e.news-auth-value"));
        assertThat(newsHealth.path("code").asInt()).isEqualTo(200);
        assertThat(newsHealth.toString()).contains("chatchat-runtime-news", "UP");
    }

    private JsonNode getJson(String url, String authHeader, String authValue) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET();
        if (authHeader != null && authValue != null) request.header(authHeader, authValue);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s response=%s", url, preview(response.body()))
            .isBetween(200, 299);
        return mapper.readTree(response.body());
    }

    private String required(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required deployed-topology property %s", name).isNotBlank();
        return value.replaceFirst("/+$", "");
    }

    private String optional(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String preview(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
