package com.chatchat.common.mcp.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Runtime-owned proof that a template executor identity came from governed discovery. */
public record McpTemplateBindingEvidence(
    String schemaVersion,
    String source,
    String templateId,
    String executorTool,
    String assetId,
    String templateVersion,
    String contentHash,
    String toolContractVersion,
    String toolContractHash
) {
    public static final String SCHEMA_VERSION = "runtime_template_binding.v3";
    public static final String PREVIOUS_SCHEMA_VERSION = "runtime_template_binding.v2";
    public static final String LEGACY_SCHEMA_VERSION = "runtime_template_binding.v1";
    public static final String CONTEXT_KEY = "runtimeTemplateBinding";
    public static final String INVALID_REASON_KEY = "templateBindingInvalidReason";
    public static final String SKIPPED_REASON_KEY = "templateBindingSkippedReason";
    public static final String BATCH_DROPPED_KEY = "bindingDroppedAtBatchChild";

    public McpTemplateBindingEvidence {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)
            && !PREVIOUS_SCHEMA_VERSION.equals(schemaVersion)
            && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MCP template binding schema: " + schemaVersion);
        }
        source = required(source, "source");
        templateId = required(templateId, "templateId");
        executorTool = required(executorTool, "executorTool");
        assetId = clean(assetId);
        templateVersion = clean(templateVersion);
        contentHash = clean(contentHash);
        toolContractVersion = clean(toolContractVersion);
        toolContractHash = clean(toolContractHash);
    }

    public McpTemplateBindingEvidence(String schemaVersion, String source, String templateId,
                                      String executorTool) {
        this(schemaVersion, source, templateId, executorTool, null, null, null, null, null);
    }

    /** Compatibility constructor for v2 callers, whose snapshot fields represented the tool contract. */
    public McpTemplateBindingEvidence(String schemaVersion, String source, String templateId,
                                      String executorTool, String assetId,
                                      String toolContractVersion, String toolContractHash) {
        this(schemaVersion, source, templateId, executorTool, assetId,
            null, null, toolContractVersion, toolContractHash);
    }

    public static Optional<McpTemplateBindingEvidence> from(Object value) {
        return parse(value).evidence();
    }

    /** Distinguishes an absent binding from a present but malformed binding. */
    public static ParseResult parse(Object value) {
        if (value == null) return ParseResult.absent();
        if (!(value instanceof Map<?, ?> map)) {
            return ParseResult.invalid("runtimeTemplateBinding must be an object");
        }
        try {
            String schemaVersion = text(map.get("schemaVersion"));
            boolean previousV2 = PREVIOUS_SCHEMA_VERSION.equals(schemaVersion);
            return ParseResult.valid(new McpTemplateBindingEvidence(
                schemaVersion, text(map.get("source")),
                text(map.get("templateId")), text(map.get("executorTool")),
                text(map.get("assetId")),
                previousV2 ? null : text(map.get("templateVersion")),
                previousV2 ? null : text(map.get("contentHash")),
                first(text(map.get("toolContractVersion")),
                    previousV2 ? text(map.get("templateVersion")) : null),
                first(text(map.get("toolContractHash")),
                    previousV2 ? text(map.get("contentHash")) : null)));
        } catch (IllegalArgumentException invalid) {
            return ParseResult.invalid(invalid.getMessage());
        }
    }

    public boolean authorizes(String expectedTemplateId, String expectedExecutorTool) {
        return templateId.equals(clean(expectedTemplateId))
            && executorTool.equals(clean(expectedExecutorTool));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("source", source);
        result.put("templateId", templateId);
        result.put("executorTool", executorTool);
        putIfPresent(result, "assetId", assetId);
        putIfPresent(result, "templateVersion", templateVersion);
        putIfPresent(result, "contentHash", contentHash);
        putIfPresent(result, "toolContractVersion", toolContractVersion);
        putIfPresent(result, "toolContractHash", toolContractHash);
        return Map.copyOf(result);
    }

    public McpTemplateBindingEvidence withToolSnapshot(String resolvedAssetId,
                                                       String resolvedToolContractVersion,
                                                       String resolvedToolContractHash) {
        return new McpTemplateBindingEvidence(SCHEMA_VERSION, source, templateId, executorTool,
            first(resolvedAssetId, assetId), templateVersion, contentHash,
            first(resolvedToolContractVersion, toolContractVersion),
            first(resolvedToolContractHash, toolContractHash));
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null) values.put(key, value);
    }

    private static String first(String preferred, String fallback) {
        String value = clean(preferred);
        return value == null ? clean(fallback) : value;
    }

    public record ParseResult(boolean present,
                              Optional<McpTemplateBindingEvidence> evidence,
                              String invalidReason) {
        private static ParseResult absent() {
            return new ParseResult(false, Optional.empty(), null);
        }

        private static ParseResult valid(McpTemplateBindingEvidence evidence) {
            return new ParseResult(true, Optional.of(evidence), null);
        }

        private static ParseResult invalid(String reason) {
            return new ParseResult(true, Optional.empty(), clean(reason) == null
                ? "runtimeTemplateBinding is invalid" : clean(reason));
        }

        public boolean valid() {
            return evidence.isPresent();
        }
    }

    private static String required(String value, String field) {
        String result = clean(value);
        if (result == null) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
