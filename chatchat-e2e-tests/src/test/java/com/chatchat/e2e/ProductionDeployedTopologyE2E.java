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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            optional("chatchat.e2e.api-auth-header", null),
            optional("chatchat.e2e.api-auth-value", "CHATCHAT_E2E_API_AUTH_VALUE"));
        assertThat(apiMcp.path("code").asInt()).isEqualTo(200);

        JsonNode mcpServices = getJson(required("chatchat.e2e.mcp-base-url") + "/api/v1/mcp-services",
            optional("chatchat.e2e.mcp-auth-header", null),
            optional("chatchat.e2e.mcp-auth-value", "CHATCHAT_E2E_MCP_AUTH_VALUE"));
        assertThat(mcpServices.path("code").asInt()).isEqualTo(200);

        JsonNode newsHealth = getJson(required("chatchat.e2e.news-base-url") + "/internal/v1/news/health",
            optional("chatchat.e2e.news-auth-header", null),
            optional("chatchat.e2e.news-auth-value", "CHATCHAT_E2E_NEWS_AUTH_VALUE"));
        assertThat(newsHealth.path("code").asInt()).isEqualTo(200);
        assertThat(newsHealth.toString()).contains("chatchat-runtime-news", "UP");

        JsonNode inference = postJson(required("chatchat.e2e.api-base-url") + "/api/v1/interactions/chat",
            Map.of(
                "conversationId", "release-e2e-" + UUID.randomUUID(),
                "mode", "agent_chat",
                "query", required("chatchat.e2e.inference-query")
            ),
            optional("chatchat.e2e.api-auth-header", null),
            optional("chatchat.e2e.api-auth-value", "CHATCHAT_E2E_API_AUTH_VALUE"));
        assertThat(inference.path("code").asInt()).isEqualTo(200);
        assertThat(inference.path("data").path("answer").asText()).isNotBlank();
        assertThat(inference.path("data").path("requestId").asText()).isNotBlank();
        assertThat(inference.path("data").path("toolTraces").isArray()).isTrue();
        assertThat(inference.path("data").path("toolTraces").isEmpty()).isFalse();
        assertThat(inference.path("data").toString().toLowerCase())
            .contains(required("chatchat.e2e.inference-expected-evidence").toLowerCase());

        String runId = inference.path("data").path("metadata").path("agentRunId").asText();
        assertThat(runId).as("online evaluation requires persisted Agent runId").isNotBlank();
        String query = required("chatchat.e2e.inference-query");
        String expectedEvidence = required("chatchat.e2e.inference-expected-evidence");
        String expectedTool = required("chatchat.e2e.inference-expected-tool");
        String expectedQueryArgument = optional("chatchat.e2e.inference-expected-query-argument", null);
        if (expectedQueryArgument == null) expectedQueryArgument = query;
        Map<String, Double> thresholds = Map.of(
            "minRetrievalScore", qualityProperty("chatchat.e2e.quality.min-retrieval", 0.90D),
            "minToolSelectionScore", qualityProperty("chatchat.e2e.quality.min-tool-selection", 0.95D),
            "minParameterAccuracy", qualityProperty("chatchat.e2e.quality.min-parameter-accuracy", 0.95D),
            "minEvidenceCompleteness", qualityProperty("chatchat.e2e.quality.min-evidence-completeness", 0.95D),
            "minOverallScore", qualityProperty("chatchat.e2e.quality.min-overall", 0.95D));
        JsonNode evaluation = postJson(required("chatchat.e2e.api-base-url")
                + "/api/v1/agent/runtime/runs/" + runId + "/evaluation",
            Map.of(
                "question", query,
                "expectedEvidence", List.of(),
                "expectedKeywords", List.of(),
                "mustHaveCitation", true,
                "expectedRetrieval", List.of(Map.of(
                    "mustContainAny", List.of(expectedEvidence), "maxRank", 10)),
                "expectedTools", List.of(Map.of(
                    "toolName", expectedTool,
                    "expectedArguments", Map.of("query", expectedQueryArgument))),
                "thresholds", thresholds
            ),
            optional("chatchat.e2e.api-auth-header", null),
            optional("chatchat.e2e.api-auth-value", "CHATCHAT_E2E_API_AUTH_VALUE"));
        assertThat(evaluation.path("code").asInt()).isEqualTo(200);
        assertThat(evaluation.path("data").path("contractVersion").asText()).isEqualTo("agent_evaluation_v2");
        assertThat(evaluation.path("data").path("passed").asBoolean())
            .as("online quality evaluation: %s", evaluation.path("data")).isTrue();
        JsonNode dimensions = evaluation.path("data").path("dimensions");
        assertThat(dimensions.has("retrieval")).isTrue();
        assertThat(dimensions.has("toolSelection")).isTrue();
        assertThat(dimensions.has("parameterAccuracy")).isTrue();
        assertThat(dimensions.has("evidenceCompleteness")).isTrue();
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

    private JsonNode postJson(String url, Object body, String authHeader, String authValue) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (authHeader != null && authValue != null) request.header(authHeader, authValue);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s response=%s", url, preview(response.body()))
            .isBetween(200, 299);
        return mapper.readTree(response.body());
    }

    private String required(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required deployed-topology property %s", name).isNotBlank();
        return value.replaceFirst("/+$", "");
    }

    private String optional(String name, String environmentName) {
        String value = System.getProperty(name);
        if ((value == null || value.isBlank()) && environmentName != null) {
            value = System.getenv(environmentName);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String preview(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private double qualityProperty(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name, String.valueOf(fallback)));
    }
}
