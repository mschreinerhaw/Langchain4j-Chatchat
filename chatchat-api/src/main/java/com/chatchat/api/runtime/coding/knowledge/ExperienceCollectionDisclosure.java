package com.chatchat.api.runtime.coding.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.function.Consumer;

/** A revoked historical source must not make the entire monitoring dashboard unavailable. */
final class ExperienceCollectionDisclosure {
    private static final Set<String> METADATA = Set.of("experienceId", "taskId", "agentId", "updateTime");

    private ExperienceCollectionDisclosure() {}

    static JsonNode sanitize(JsonNode body, Consumer<JsonNode> validate, Runnable validateAggregates) {
        ObjectNode result = body.deepCopy();
        ObjectNode data = (ObjectNode) result.path("data");
        var items = data.withArray("experiences");
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            try {
                validate.accept(item);
            } catch (SecurityException denied) {
                ObjectNode metadata = data.objectNode();
                METADATA.forEach(key -> { if (item.has(key)) metadata.set(key, item.get(key)); });
                metadata.put("contentRestricted", true);
                metadata.put("question", "历史经验内容当前不可访问");
                metadata.put("answerSummary", "经验来源未通过当前授权或版本校验，内容暂不可读取。");
                items.set(index, metadata);
            }
        }
        try {
            // Index text and scenario names are derived from the whole tenant history,
            // including experiences outside this page. Checking only visible rows is insufficient.
            validateAggregates.run();
        } catch (SecurityException denied) {
            data.putArray("indexes");
            data.putArray("scenarios");
            data.put("aggregateContentRestricted", true);
            data.put("contentRestrictionMessage", "部分历史经验来源当前不可访问，关联索引与场景汇总暂不可展示。");
        }
        return result;
    }
}
