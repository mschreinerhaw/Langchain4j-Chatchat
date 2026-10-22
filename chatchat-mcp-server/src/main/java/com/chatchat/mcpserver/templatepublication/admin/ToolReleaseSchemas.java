package com.chatchat.mcpserver.templatepublication.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;

/** Local draft-2020-12 contracts only; external references must never trigger outbound I/O. */
public final class ToolReleaseSchemas {
    private ToolReleaseSchemas() { }
    public static Schema compile(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText()))
            throw new IllegalArgumentException("Tool Schema must declare type object");
        checkReferences(schema);
        if (schema.has("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(schema.path("$schema").asText()))
            throw new IllegalArgumentException("Only JSON Schema draft 2020-12 is supported");
        if (schema.has("properties") && !schema.get("properties").isObject())
            throw new IllegalArgumentException("Schema properties must be an object");
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean())
            throw new IllegalArgumentException("Tool additionalProperties must be boolean");
        Schema result = SchemaRegistry.withDefaultDialect(com.networknt.schema.dialect.Dialects.getDraft202012()).getSchema(schema);
        result.initializeValidators();
        return result;
    }
    public static void validate(JsonNode schema, JsonNode sample) {
        if (sample == null) throw new IllegalArgumentException("Test sample is required");
        var errors = compile(schema).validate(sample);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Schema validation failed: " + errors.iterator().next().getMessage());
    }
    private static void checkReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if ((field.getKey().equals("$ref") || field.getKey().equals("$dynamicRef"))
                    && (!field.getValue().isTextual() || !field.getValue().asText().startsWith("#")))
                    throw new IllegalArgumentException("Only local Schema references are supported");
                if (field.getKey().equals("$id")) throw new IllegalArgumentException("External Schema identity is not supported");
                checkReferences(field.getValue());
            });
        } else if (node.isArray()) node.forEach(ToolReleaseSchemas::checkReferences);
    }
}
