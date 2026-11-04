package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeResponseAdviceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Guard guard = new Guard();
    private final KnowledgeResponseAdvice advice = new KnowledgeResponseAdvice(guard, json);
    private final ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

    static class Guard extends KnowledgeDisclosureGuard {
        Guard() { super(null, null, (CodingInvocationContext) null, null, null, null); }
        @Override public void check(String tenant, String user, String text) {
            if (text.contains("private-ref")) throw new SecurityException("Knowledge reference unavailable to current user");
        }
        @Override public boolean checkRun(String tenant, String user, String run) {
            if ("revoked".equals(run)) throw new SecurityException("Data resource authorization was revoked or not requested");
            return "resource-run".equals(run);
        }
    }

    private Object write(String route, Object body) {
        var request = new MockHttpServletRequest("GET", "/api/v1/agent/" + route);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "owner");
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), response);
    }
    private Map<String, Object> task(String id, String run, String summary) {
        return Map.of("taskId", id, "canonicalState", "SUCCEEDED", "status", "SUCCESS", "attemptId", run,
            "answerSummary", summary, "question", "private question", "feedbackComment", "private feedback",
            "datasets", Map.of("private", true), "futureContentField", "private future content");
    }

    @Test void mixedPageRedactsOnlyUnavailableTasksAndPreservesPagination() {
        var publicTask = task("allowed", "ordinary", "visible answer");
        var denied = task("denied", "ordinary", "runtime-evidence:knowledge:private-ref");
        var body = Map.of("code", 200, "data", Map.of("items", java.util.List.of(publicTask, denied),
            "total", 1831, "page", 1, "pageSize", 10, "totalPages", 184));
        var result = (JsonNode) write("tasks/page", body);
        assertEquals(1831, result.at("/data/total").asInt());
        assertEquals(184, result.at("/data/totalPages").asInt());
        assertEquals(json.valueToTree(publicTask), result.at("/data/items/0"));
        var restricted = result.at("/data/items/1");
        assertTrue(restricted.path("contentRestricted").asBoolean());
        assertEquals("denied", restricted.path("taskId").asText());
        assertEquals("SUCCESS", restricted.path("status").asText());
        assertEquals(0, restricted.path("datasets").size());
        assertFalse(restricted.has("feedbackComment"));
        assertFalse(restricted.has("futureContentField"));
        assertFalse(restricted.toString().contains("private"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("private question", denied.get("question")); // Never rewrite retained audit data.
    }

    @Test void revokedRunWithoutAnyCitationIsStillRedacted() {
        var body = Map.of("code", 200, "data", Map.of("items", java.util.List.of(task("task", "revoked", "truncated facts"))));
        var result = (JsonNode) write("tasks/page", body);
        assertTrue(result.at("/data/items/0/contentRestricted").asBoolean());
        assertFalse(result.toString().contains("truncated facts"));
    }

    @ParameterizedTest @ValueSource(strings={"tasks", "tasks/runtime"})
    void legacyListsAndDashboardLatestTasksRemainAvailable(String route) {
        var rows = java.util.List.of(task("task", "revoked", "private facts"));
        Object data = route.endsWith("runtime") ? Map.of("latestTasks", rows, "totalTasks", 1831) : rows;
        var result = (JsonNode) write(route, Map.of("code", 200, "data", data));
        assertFalse(result.toString().contains("private"));
        assertTrue(result.toString().contains("contentRestricted"));
        if (route.endsWith("runtime")) assertEquals(1831, result.at("/data/totalTasks").asInt());
    }

    @ParameterizedTest @ValueSource(strings={"runtime/runs/revoked/coding", "runtime/runs/revoked/coding/task/events", "runtime/runs/revoked"})
    void directRunDetailsStillDenyTruncatedContent(String route) {
        assertThrows(ResponseStatusException.class, () -> write(route, Map.of("summary", "truncated private facts")));
    }

    @ParameterizedTest @ValueSource(strings={"tasks/task-1", "tasks/task-1/events", "tasks/task-1/plan"})
    void directTaskContentStillReturnsForbidden(String route) {
        assertThrows(ResponseStatusException.class, () -> write(route, task("task", "revoked", "private facts")));
    }

    @Test void unrelatedErrorsAreNotConvertedToPartialSuccess() {
        assertNull(TaskCollectionDisclosure.sanitize("GET", "/api/v1/agent/tasks/page",
            json.valueToTree(Map.of("code", 403, "data", Map.of("items", java.util.List.of()))), node -> {}));
        assertThrows(IllegalStateException.class, () -> TaskCollectionDisclosure.sanitize("GET", "/api/v1/agent/tasks/page",
            json.valueToTree(Map.of("code", 200, "data", Map.of("items", java.util.List.of(task("task", "run", "ok"))))),
            node -> { throw new IllegalStateException("storage unavailable"); }));
    }
}
