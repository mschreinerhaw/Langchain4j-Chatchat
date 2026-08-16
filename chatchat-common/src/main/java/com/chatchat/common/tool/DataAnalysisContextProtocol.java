package com.chatchat.common.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source-neutral summary-governance context supplied alongside returned data.
 *
 * <p>Every structured data producer may populate this contract. Consumers must use it to
 * understand data identity and relationships, while keeping returned values and presentation
 * keys unchanged.</p>
 */
public final class DataAnalysisContextProtocol {

    public static final String SCHEMA_VERSION = "data_analysis_context.v1";
    public static final String GOVERNANCE_VERSION = "summary_governance.v1";

    private DataAnalysisContextProtocol() {
    }

    public static Map<String, Object> create(Map<String, Object> source,
                                             Object capability,
                                             Map<String, Object> business,
                                             Map<String, Object> schema,
                                             Object relationships) {
        return create(source, capability, business, schema, relationships,
            Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Creates the extensible, source-neutral analysis contract. Domain producers should place
     * analytical meaning in the stable sections and keep source-specific metadata under
     * {@code extensions}; consumers must not require a domain-specific extension to exist.
     */
    public static Map<String, Object> create(Map<String, Object> source,
                                             Object capability,
                                             Map<String, Object> business,
                                             Map<String, Object> schema,
                                             Object relationships,
                                             Map<String, Object> semantics,
                                             Map<String, Object> quality,
                                             Map<String, Object> analysisPolicy,
                                             Map<String, Object> extensions) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schemaVersion", SCHEMA_VERSION);
        context.put("governance", Map.of(
            "protocolVersion", GOVERNANCE_VERSION,
            "contextRole", "DATA_IDENTITY_FOR_SUMMARY",
            "fieldMetadataRole", "SEMANTIC_INPUT_ONLY",
            "presentationPolicy", "PRESERVE_RETURNED_FIELD_KEYS",
            "relationshipPolicy", "EXPLICIT_RELATIONSHIPS_ONLY"
        ));
        context.put("source", immutableMap(source));
        context.put("capability", capability == null ? Map.of() : capability);
        context.put("business", immutableMap(business));
        context.put("schema", immutableMap(schema));
        context.put("relationships", relationships == null ? Map.of() : relationships);
        context.put("semantics", immutableMap(semantics));
        context.put("quality", immutableMap(quality));
        context.put("analysisPolicy", immutableMap(analysisPolicy));
        context.put("extensions", immutableMap(extensions));
        return Collections.unmodifiableMap(context);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null || value.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
