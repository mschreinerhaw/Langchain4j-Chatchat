package com.chatchat.api.runtime.coding.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.function.Consumer;

/** Isolate unavailable source content to its task; keep pagination and operational metadata. */
public final class TaskCollectionDisclosure {
    private static final Set<String> METADATA = Set.of(
        "taskId", "executionId", "rootExecutionId", "attemptId", "parentAttemptId", "attemptNumber",
        "canonicalState", "tenantId", "userId", "agentId", "sessionId", "status",
        "createTime", "updateTime", "finishedAt", "lastEventSequence");

    private TaskCollectionDisclosure() {}

    /** Null means this is not one of the supported task collection envelopes. */
    public static JsonNode sanitize(String method, String uri, JsonNode body, Consumer<JsonNode> validate) {
        if (!"GET".equals(method) || body == null || !body.isObject() || body.path("code").asInt() != 200)
            return null;
        ObjectNode result = body.deepCopy();
        JsonNode data = result.path("data");
        JsonNode rows;
        switch (uri) {
            case "/api/v1/agent/tasks/page" -> rows = data.path("items");
            case "/api/v1/agent/tasks" -> rows = data;
            case "/api/v1/agent/tasks/runtime" -> rows = data.path("latestTasks");
            default -> { return null; }
        }
        if (!(rows instanceof ArrayNode tasks)) return null;
        for (JsonNode task : tasks) {
            if (!task.isObject() || !task.hasNonNull("taskId") || !task.has("canonicalState")) return null;
        }
        for (int index = 0; index < tasks.size(); index++) {
            ObjectNode task = (ObjectNode) tasks.get(index);
            try {
                validate.accept(task);
            } catch (SecurityException denied) {
                // Allowlist instead of removing known content fields: future payload fields stay private.
                ObjectNode metadata = task.objectNode();
                METADATA.forEach(key -> { if (task.has(key)) metadata.set(key, task.get(key)); });
                metadata.put("contentRestricted", true);
                metadata.put("question", "历史任务内容当前不可访问");
                metadata.put("answerSummary", "证据授权或版本已失效，或该内容属于其他用户。任务状态仍可查看。");
                metadata.putArray("datasets");
                tasks.set(index, metadata);
            }
        }
        return result;
    }
}
