package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.task.learning.AgentExperienceSummary;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperienceCollectionDisclosureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeDisclosureGuard guard = mock(KnowledgeDisclosureGuard.class);
    private final KnowledgeResponseAdvice advice = new KnowledgeResponseAdvice(guard, json);
    private final ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

    private Object body() {
        return ApiResponse.success(json.convertValue(Map.of(
            "tenantId", "tenant", "totalExperiences", 120, "totalIndexes", 9,
            "experiences", List.of(
                Map.of("experienceId", "allowed", "taskId", "task-a", "question", "allowed question", "answerSummary", "visible"),
                Map.of("experienceId", "denied", "taskId", "task-b", "question", "private question", "answerSummary", "truncated private facts",
                    "attributionSummary", "private derivation", "feedbackComment", "private feedback")),
            "indexes", List.of(Map.of("id", "index", "keywords", "private keywords", "bestPractice", "derived facts")),
            "scenarios", List.of(Map.of("scenarioKey", "scenario", "scenarioName", "private name"))
        ), AgentExperienceSummary.class));
    }

    private JsonNode write(Object body) {
        var request = new MockHttpServletRequest("GET", "/api/v1/agent/tasks/runtime/experiences");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "owner");
        return (JsonNode) advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), response);
    }

    @Test void unavailableExperienceDoesNotBlockOtherRowsAndNeverLeaksDerivedContent() {
        doThrow(new SecurityException("Source cannot be revalidated; retained content is withheld"))
            .when(guard).checkExperience("tenant", "owner", "denied", "task-b");
        doThrow(new SecurityException("unavailable aggregate source"))
            .when(guard).checkExperienceAggregates(eq("tenant"), eq("owner"), anySet());
        Object original = body();
        JsonNode result = write(original);
        assertThat(result.at("/data/totalExperiences").asInt()).isEqualTo(120);
        assertThat(result.at("/data/totalIndexes").asInt()).isEqualTo(9);
        assertThat(result.at("/data/experiences/0/answerSummary").asText()).isEqualTo("visible");
        assertThat(result.at("/data/experiences/1/contentRestricted").asBoolean()).isTrue();
        assertThat(result.at("/data/experiences/1/taskId").asText()).isEqualTo("task-b");
        assertThat(result.at("/data/aggregateContentRestricted").asBoolean()).isTrue();
        assertThat(result.at("/data/indexes")).isEmpty();
        assertThat(result.at("/data/scenarios")).isEmpty();
        assertThat(result.toString()).doesNotContain("private", "derived facts");
        assertThat(result.at("/data/experiences/1").toString()).doesNotContain("feedbackComment", "attributionSummary");
        assertThat(json.valueToTree(original).toString()).contains("private question");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(guard).checkExperienceAggregates("tenant", "owner", java.util.Set.of("allowed"));
    }

    @Test void allowedExperiencesKeepTheirOriginalContract() throws Exception {
        Object original = body();
        assertThat(write(original)).isEqualTo(json.readTree(json.writeValueAsString(original)));
        verify(guard).checkExperience("tenant", "owner", "allowed", "task-a");
        verify(guard).checkExperience("tenant", "owner", "denied", "task-b");
        verify(guard).checkExperienceAggregates("tenant", "owner", java.util.Set.of("allowed", "denied"));
    }

    @Test void anUnavailableSourceOutsideTheVisiblePageStillWithholdsAggregates() {
        doThrow(new SecurityException("outside visible page"))
            .when(guard).checkExperienceAggregates(anyString(), anyString(), anySet());
        JsonNode result = write(body());
        assertThat(result.at("/data/experiences/1/answerSummary").asText()).isEqualTo("truncated private facts");
        assertThat(result.at("/data/indexes")).isEmpty();
        assertThat(result.at("/data/scenarios")).isEmpty();
    }

    @Test void unsupportedPayloadsCannotOptOutOfTheOriginalSourceGuard() {
        doThrow(new SecurityException("Knowledge reference unavailable to current user"))
            .when(guard).check(any(), any(), contains("runtime-evidence:knowledge:"));
        assertThatThrownBy(() -> write(ApiResponse.success(Map.of("experiences", List.of(
            Map.of("answerSummary", "runtime-evidence:knowledge:private-ref"))))))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void operationalErrorsAreNotConvertedToRestrictedContent() {
        doThrow(new IllegalStateException("database unavailable"))
            .when(guard).checkExperience(any(), any(), any(), any());
        assertThatThrownBy(() -> write(body())).isInstanceOf(IllegalStateException.class);
    }
}
