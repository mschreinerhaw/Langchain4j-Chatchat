package com.chatchat.agents.orchestration.analysis.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-local, lossless interning of repeated schemas/semantics. Never caches model decisions. */
public final class ToolReviewPromptContext {
    private static final int MIN_SHARED_CHARS = 256;
    private final ObjectMapper mapper;
    public ToolReviewPromptContext(ObjectMapper mapper) { this.mapper = mapper; }
    public record Projection(String json, int originalChars, int projectedChars, int sharedValues) { }

    public Projection project(Object context) {
        JsonNode source = mapper.valueToTree(context);
        String original = source.toString();
        Map<String,Integer> counts = new LinkedHashMap<>();
        count(source, counts, 0);
        ObjectNode definitions = mapper.createObjectNode();
        JsonNode data = encode(source, counts, new LinkedHashMap<>(), definitions, 0);
        if (definitions.isEmpty()) return new Projection(original, original.length(), original.length(), 0);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", "tool_review_shared_context.v1");
        envelope.set("sharedValues", definitions);
        envelope.set("context", data);
        String projected = envelope.toString();
        return projected.length() < original.length()
            ? new Projection(projected, original.length(), projected.length(), definitions.size())
            : new Projection(original, original.length(), original.length(), 0);
    }
    private void count(JsonNode node, Map<String,Integer> counts, int depth) {
        if (depth > 64 || node == null) return;
        if (shareable(node)) counts.merge(node.toString(), 1, Integer::sum);
        if (node.isContainerNode()) node.forEach(child -> count(child, counts, depth + 1));
    }
    private JsonNode encode(JsonNode node, Map<String,Integer> counts, Map<String,String> identities,
                            ObjectNode definitions, int depth) {
        if (node == null || depth > 64) return node;
        if (node.isObject() && (node.has("$runtimeReviewRef") || node.has("$runtimeReviewLiteral"))) {
            ObjectNode literal = mapper.createObjectNode();
            literal.set("$runtimeReviewLiteral", node);
            return literal;
        }
        if (shareable(node)) {
            String value = node.toString();
            if (counts.getOrDefault(value, 0) > 1) {
                String id = identities.get(value);
                if (id == null) {
                    id = "context-" + identities.size();
                    identities.put(value, id);
                    definitions.set(id, node);
                }
                return mapper.createObjectNode().put("$runtimeReviewRef", id);
            }
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), encode(entry.getValue(), counts, identities, definitions, depth + 1)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(child -> result.add(encode(child, counts, identities, definitions, depth + 1)));
            return result;
        }
        return node;
    }
    private boolean shareable(JsonNode node) {
        return (node.isTextual() || node.isContainerNode()) && node.toString().length() >= MIN_SHARED_CHARS;
    }
}
