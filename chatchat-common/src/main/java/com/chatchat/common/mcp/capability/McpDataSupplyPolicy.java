package com.chatchat.common.mcp.capability;

import java.util.Map;

/** Publisher opt-in for a baseline dataset, distinct from semantic search relevance. */
public final class McpDataSupplyPolicy {
    public static final String METADATA_KEY = "dataSupplyPolicy";
    public static final String VERSION = "mcp.data-supply.v1";
    private McpDataSupplyPolicy() {}

    public static Map<String, Object> fixedBaseline() {
        return Map.of("contractVersion", VERSION, "mode", "FIXED_PARAMETER_FREE_BASELINE",
            "selectionAuthority", "PUBLISHER", "analyticalCoverage", "NOT_EVALUATED");
    }

    public static boolean declared(Map<String, Object> metadata) {
        return metadata != null && fixedBaseline().equals(metadata.get(METADATA_KEY));
    }

    public static boolean parameterFree(Map<String, Object> candidate) {
        if (!(candidate.get("parameterSchema") instanceof Map<?, ?> schema)
            || !"object".equals(schema.get("type"))) return false;
        if (!java.util.Set.of("type","properties","required","additionalProperties",
                "title","description","$schema","$id","$defs","default","examples")
            .containsAll(schema.keySet())) return false;
        Object properties=schema.get("properties"), required=schema.get("required");
        return (properties == null || properties instanceof Map<?, ?> fields && fields.isEmpty())
            && (required == null || required instanceof java.util.Collection<?> fields && fields.isEmpty())
            && !schema.containsKey("$ref") && !schema.containsKey("allOf")
            && !schema.containsKey("oneOf") && !schema.containsKey("anyOf");
    }
}
