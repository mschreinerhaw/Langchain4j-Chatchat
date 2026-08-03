package com.chatchat.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box regression for workflow state that is produced before an InterpretationPlan starts.
 * The test talks only to deployed HTTP endpoints: application services are never instantiated or mocked here.
 */
@EnabledIfSystemProperty(named = "chatchat.e2e.deployed-topology.live", matches = "true")
class ProductionDeployedPrePlanWorkflowE2E {

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void userDiagnosticRequestCrossesApiAgentMcpPersistenceAndReturnsRenderableEvidence() throws Exception {
        String apiBaseUrl = required("chatchat.e2e.api-base-url").replaceFirst("/+$", "");
        String query = required("chatchat.e2e.preplan-workflow-query");
        String answerEvidence = required("chatchat.e2e.preplan-expected-answer-evidence");
        List<String> expectedTools = csv("chatchat.e2e.preplan-expected-tools");
        assertThat(expectedTools)
            .as("the deployed workflow must prove a pre-plan step and at least one dependent step")
            .hasSizeGreaterThanOrEqualTo(2);

        String suffix = UUID.randomUUID().toString();
        String runId = "deployed-preplan-" + suffix;
        String conversationId = "deployed-preplan-conversation-" + suffix;
        String userId = "deployed-preplan-user-" + suffix;

        JsonNode interaction = postJson(apiBaseUrl + "/api/v1/interactions/chat", Map.of(
            "conversationId", conversationId,
            "userId", userId,
            "mode", "agent_chat",
            "query", query,
            "toolInput", Map.of("__agentRunId", runId)
        ));

        assertThat(interaction.path("code").asInt()).isEqualTo(200);
        JsonNode response = interaction.path("data");
        assertThat(response.path("requestId").asText()).isNotBlank();
        assertThat(response.path("conversationId").asText()).isEqualTo(conversationId);
        assertThat(response.path("answer").asText())
            .isNotBlank()
            .containsIgnoringCase(answerEvidence);

        JsonNode traces = response.path("toolTraces");
        assertThat(traces.isArray()).isTrue();
        assertSuccessfulToolOrder(traces, expectedTools);
        assertNoWorkflowDependencyFailure(response);

        JsonNode timeline = getJson(apiBaseUrl + "/api/v1/agent/runtime/runs/"
            + encode(runId) + "/timeline?eventLimit=500&stepLimit=100&observationLimit=500");
        assertThat(timeline.path("code").asInt()).isEqualTo(200);
        JsonNode timelineData = timeline.path("data");
        assertThat(timelineData.path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(timelineData.path("events").isArray()).isTrue();
        assertThat(timelineData.path("events").isEmpty()).isFalse();
        assertThat(timelineData.path("observations").isArray()).isTrue();
        assertThat(timelineData.path("observations").isEmpty()).isFalse();
        for (String tool : expectedTools) {
            assertThat(timelineData.toString()).as("persisted runtime evidence for %s", tool).contains(tool);
        }
        assertNoWorkflowDependencyFailure(timelineData);

        JsonNode conversation = getJson(apiBaseUrl + "/api/v1/conversations/" + encode(conversationId));
        assertThat(conversation.path("code").asInt()).isEqualTo(200);
        JsonNode messages = conversation.path("data").path("messages");
        assertThat(messages.isArray()).isTrue();
        assertThat(messages.toString()).contains(query, response.path("answer").asText());
        assertThat(messages.toString()).contains(expectedTools.toArray(String[]::new));
    }

    @Test
    void failedPrePlanToolStopsDependentsPersistsFailureAndReturnsUserFacingExplanation() throws Exception {
        String apiBaseUrl = required("chatchat.e2e.api-base-url").replaceFirst("/+$", "");
        String query = required("chatchat.e2e.preplan-failure-query");
        String failedTool = required("chatchat.e2e.preplan-failure-tool");
        String expectedExplanation = required("chatchat.e2e.preplan-failure-expected-evidence");
        List<String> blockedTools = csv("chatchat.e2e.preplan-failure-blocked-tools");

        String suffix = UUID.randomUUID().toString();
        String runId = "deployed-preplan-failure-" + suffix;
        String conversationId = "deployed-preplan-failure-conversation-" + suffix;
        JsonNode interaction = postJson(apiBaseUrl + "/api/v1/interactions/chat", Map.of(
            "conversationId", conversationId,
            "userId", "deployed-preplan-failure-user-" + suffix,
            "mode", "agent_chat",
            "query", query,
            "toolInput", Map.of("__agentRunId", runId)
        ));

        assertThat(interaction.path("code").asInt()).isEqualTo(200);
        JsonNode response = interaction.path("data");
        assertThat(response.path("answer").asText())
            .isNotBlank()
            .containsIgnoringCase(expectedExplanation);
        JsonNode traces = response.path("toolTraces");
        JsonNode failedTrace = findToolTrace(traces, failedTool);
        assertThat(failedTrace).as("failed pre-plan trace %s", failedTool).isNotNull();
        assertThat(failedTrace.path("success").asBoolean()).isFalse();
        for (String blockedTool : blockedTools) {
            assertThat(findToolTrace(traces, blockedTool))
                .as("dependent tool %s must not execute after pre-plan failure", blockedTool)
                .isNull();
        }
        assertNoWorkflowDependencyFailure(response);

        JsonNode timeline = getJson(apiBaseUrl + "/api/v1/agent/runtime/runs/"
            + encode(runId) + "/timeline?eventLimit=500&stepLimit=100&observationLimit=500");
        assertThat(timeline.path("code").asInt()).isEqualTo(200);
        JsonNode timelineData = timeline.path("data");
        assertThat(timelineData.path("run").path("status").asText()).isIn("COMPLETED", "FAILED");
        assertThat(timelineData.path("observations").toString()).contains(failedTool);
        for (String blockedTool : blockedTools) {
            assertThat(timelineData.path("observations").toString()).doesNotContain(blockedTool);
        }
        assertNoWorkflowDependencyFailure(timelineData);

        JsonNode conversation = getJson(apiBaseUrl + "/api/v1/conversations/" + encode(conversationId));
        assertThat(conversation.path("code").asInt()).isEqualTo(200);
        assertThat(conversation.path("data").path("messages").toString())
            .contains(query, expectedExplanation);
    }

    private void assertSuccessfulToolOrder(JsonNode traces, List<String> expectedTools) {
        int previousIndex = -1;
        for (String expectedTool : expectedTools) {
            int index = -1;
            for (int candidate = previousIndex + 1; candidate < traces.size(); candidate++) {
                if (expectedTool.equals(traces.get(candidate).path("toolName").asText())) {
                    index = candidate;
                    break;
                }
            }
            assertThat(index)
                .as("successful tool %s must occur after the preceding workflow step; traces=%s",
                    expectedTool, preview(traces.toString()))
                .isGreaterThan(previousIndex);
            assertThat(traces.get(index).path("success").asBoolean())
                .as("tool %s must reach a successful terminal state", expectedTool)
                .isTrue();
            previousIndex = index;
        }
    }

    private JsonNode findToolTrace(JsonNode traces, String toolName) {
        if (!traces.isArray()) return null;
        for (JsonNode trace : traces) {
            if (toolName.equals(trace.path("toolName").asText())) return trace;
        }
        return null;
    }

    private void assertNoWorkflowDependencyFailure(JsonNode value) {
        assertThat(value.toString().toLowerCase())
            .doesNotContain("required previous steps", "workflow dependency", "依赖校验阻断");
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET();
        authorize(request);
        return send(request.build(), "GET " + url);
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        authorize(request);
        return send(request.build(), "POST " + url);
    }

    private JsonNode send(HttpRequest request, String operation) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s response=%s", operation, preview(response.body()))
            .isBetween(200, 299);
        return mapper.readTree(response.body());
    }

    private void authorize(HttpRequest.Builder request) {
        String header = optional("chatchat.e2e.api-auth-header", null);
        String value = optional("chatchat.e2e.api-auth-value", "CHATCHAT_E2E_API_AUTH_VALUE");
        if (header != null && value != null) request.header(header, value);
    }

    private List<String> csv(String name) {
        List<String> values = Arrays.stream(required(name).split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
        assertThat(values).as("comma-separated property %s", name).doesNotHaveDuplicates();
        return values;
    }

    private String required(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required deployed E2E property %s", name).isNotBlank();
        return value.trim();
    }

    private String optional(String name, String environmentName) {
        String value = System.getProperty(name);
        if ((value == null || value.isBlank()) && environmentName != null) {
            value = System.getenv(environmentName);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String preview(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800) + "...";
    }
}
