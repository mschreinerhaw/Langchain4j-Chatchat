package com.chatchat.agents.runtime.toolcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime-owned admission gate between template discovery and template execution.
 *
 * <p>A discovery hit is not executable evidence by itself. This selector accepts a template only
 * when its identity came from the discovery response, its declared executor is compatible with
 * the requested executor, it is not disabled, and its parameter schema is structurally usable.
 * It deliberately refuses to pick the first item from an ambiguous candidate set.</p>
 */
public final class TemplateExecutionContractSelector {

    public Selection select(List<Map<String, Object>> candidates,
                            String requestedTemplateId,
                            String requestedExecutor,
                            String discoveryExecutor) {
        List<Map<String, Object>> source = candidates == null ? List.of() : candidates;
        List<CandidateRejection> rejected = new ArrayList<>();
        List<Map<String, Object>> executable = new ArrayList<>();
        for (Map<String, Object> candidate : source) {
            CandidateValidation validation = validate(candidate, requestedExecutor, discoveryExecutor);
            if (validation.accepted()) {
                executable.add(validation.template());
            } else {
                rejected.add(new CandidateRejection(validation.templateId(), validation.code(), validation.reason()));
            }
        }

        String requested = canonicalText(requestedTemplateId);
        if (requested != null) {
            List<Map<String, Object>> exact = executable.stream()
                .filter(template -> requested.equalsIgnoreCase(templateId(template)))
                .toList();
            if (exact.size() == 1) {
                return Selection.selected(exact.get(0), requested, rejected, executable.size());
            }
            boolean requestedWasDiscovered = source.stream()
                .anyMatch(item -> requested.equalsIgnoreCase(templateId(item)));
            // A model-authored id is not authoritative. When discovery proves one and only one
            // executable contract, Runtime replaces the invented/stale hint with that contract.
            // A discovered-but-incompatible exact id still fails closed.
            if (!requestedWasDiscovered && executable.size() == 1) {
                Map<String, Object> selected = executable.get(0);
                return Selection.selected(selected, templateId(selected), rejected, 1);
            }
            String code = requestedWasDiscovered
                ? "TEMPLATE_EXECUTION_CONTRACT_REJECTED" : "TEMPLATE_ID_NOT_DISCOVERED";
            return Selection.rejected(code,
                "Template " + requested + " is not an executable candidate for " + requestedExecutor,
                rejected, executable.size());
        }

        if (executable.size() == 1) {
            Map<String, Object> selected = executable.get(0);
            return Selection.selected(selected, templateId(selected), rejected, 1);
        }
        if (executable.size() > 1) {
            return Selection.rejected("TEMPLATE_SELECTION_AMBIGUOUS",
                "Multiple executable templates remain; Runtime requires an evidence-reviewed template id",
                rejected, executable.size());
        }
        return Selection.rejected("TEMPLATE_EXECUTION_CONTRACT_NOT_FOUND",
            "Discovery returned no template with a valid execution contract for " + requestedExecutor,
            rejected, 0);
    }

    private CandidateValidation validate(Map<String, Object> raw,
                                         String requestedExecutor,
                                         String discoveryExecutor) {
        Map<String, Object> template = raw == null ? Map.of() : new LinkedHashMap<>(raw);
        String id = templateId(template);
        if (id == null) {
            return CandidateValidation.rejected(null, "TEMPLATE_ID_MISSING",
                "candidate has no scalar template identity");
        }
        if (disabled(template)) {
            return CandidateValidation.rejected(id, "TEMPLATE_NOT_EXECUTABLE",
                "template is disabled, inactive, deprecated or explicitly non-executable");
        }
        String declaredExecutor = firstText(
            path(template, "parameterContract", "executionTool"),
            path(template, "invocationExample", "tool"),
            path(template, "sqlExecutionBinding", "toolName"),
            path(template, "executionBinding", "toolName"),
            path(template, "execution", "executorTool"),
            path(template, "execution", "toolName"),
            path(template, "execution", "executionTool"),
            template.get("executionTool"),
            template.get("executorTool")
        );
        String effectiveExecutor = firstNonBlank(declaredExecutor, discoveryExecutor);
        if (canonicalText(effectiveExecutor) == null) {
            return CandidateValidation.rejected(id, "TEMPLATE_EXECUTOR_MISSING",
                "neither the template nor discovery envelope declares an executor");
        }
        if (!sameExecutor(requestedExecutor, effectiveExecutor)) {
            return CandidateValidation.rejected(id, "TEMPLATE_EXECUTOR_MISMATCH",
                "declared executor " + effectiveExecutor + " does not match " + requestedExecutor);
        }
        String schemaError = schemaError(template);
        if (schemaError != null) {
            return CandidateValidation.rejected(id, "TEMPLATE_PARAMETER_SCHEMA_INVALID", schemaError);
        }
        return CandidateValidation.accepted(template, id);
    }

    private String schemaError(Map<String, Object> template) {
        Object rawSchema = first(
            template.get("parameterSchema"), template.get("parameter_schema"),
            template.get("inputSchema"), template.get("schema"));
        if (rawSchema == null) return null;
        if (!(rawSchema instanceof Map<?, ?> schema)) {
            return "parameter schema must be an object";
        }
        Object type = schema.get("type");
        if (type != null && !"object".equalsIgnoreCase(String.valueOf(type))) {
            return "parameter schema type must be object";
        }
        Object propertiesValue = schema.get("properties");
        if (propertiesValue != null && !(propertiesValue instanceof Map<?, ?>)) {
            return "parameter schema properties must be an object";
        }
        Object requiredValue = schema.get("required");
        if (requiredValue != null && !(requiredValue instanceof Iterable<?>)) {
            return "parameter schema required must be an array";
        }
        if (requiredValue instanceof Iterable<?> required) {
            Map<?, ?> properties = propertiesValue instanceof Map<?, ?> values ? values : Map.of();
            for (Object field : required) {
                if (field == null || !properties.containsKey(String.valueOf(field))) {
                    return "required parameter " + field + " is not declared in properties";
                }
            }
        }
        return null;
    }

    private boolean disabled(Map<String, Object> template) {
        for (Object flag : new Object[] {
            template.get("enabled"), template.get("active"), template.get("executable"),
            path(template, "execution", "enabled"), path(template, "execution", "executable")
        }) {
            if (Boolean.FALSE.equals(booleanValue(flag))) return true;
        }
        String status = canonicalText(first(template.get("status"), template.get("state"),
            path(template, "execution", "status")));
        if (status == null) return false;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "disabled", "inactive", "deprecated", "deleted", "unavailable", "blocked" -> true;
            default -> false;
        };
    }

    private String templateId(Map<String, Object> template) {
        return canonicalText(first(template.get("templateId"), template.get("template_id"),
            template.get("id"), template.get("code"), template.get("template"),
            path(template, "executionBinding", "templateId"),
            path(template, "sqlExecutionBinding", "templateId")));
    }

    private boolean sameExecutor(String requested, String declared) {
        String left = semanticToolName(requested);
        String right = semanticToolName(declared);
        return !left.isBlank() && !right.isBlank()
            && (left.equals(right) || left.endsWith("_" + right) || right.endsWith("_" + left));
    }

    private String semanticToolName(String value) {
        String text = canonicalText(value);
        if (text == null) return "";
        String normalized = text.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.startsWith("mcp_")) return normalized;
        String[] parts = normalized.split("_");
        if (parts.length <= 2) return normalized;
        int start = 1;
        if (parts[start].matches("[a-f0-9]{8,}")) start++;
        return String.join("_", java.util.Arrays.copyOfRange(parts, start, parts.length));
    }

    private Object path(Map<String, Object> source, String... segments) {
        Object value = source;
        for (String segment : segments) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(segment);
        }
        return value;
    }

    private Object first(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = canonicalText(value);
            if (text != null) return text;
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return canonicalText(first) == null ? canonicalText(second) : canonicalText(first);
    }

    private String canonicalText(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value == null) return null;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return Boolean.FALSE;
        return null;
    }

    private record CandidateValidation(Map<String, Object> template, String templateId,
                                       boolean accepted, String code, String reason) {
        static CandidateValidation accepted(Map<String, Object> template, String id) {
            return new CandidateValidation(
                Collections.unmodifiableMap(new LinkedHashMap<>(template)), id, true, null, null);
        }

        static CandidateValidation rejected(String id, String code, String reason) {
            return new CandidateValidation(Map.of(), id, false, code, reason);
        }
    }

    public record CandidateRejection(String templateId, String code, String reason) { }

    public record Selection(Map<String, Object> template, String templateId, boolean selected,
                            String code, String reason, List<CandidateRejection> rejections,
                            int executableCandidateCount) {
        static Selection selected(Map<String, Object> template, String id,
                                  List<CandidateRejection> rejected, int executableCount) {
            return new Selection(Collections.unmodifiableMap(new LinkedHashMap<>(template)),
                id, true, null, null,
                List.copyOf(rejected), executableCount);
        }

        static Selection rejected(String code, String reason, List<CandidateRejection> rejected,
                                  int executableCount) {
            return new Selection(Map.of(), null, false, code, reason,
                List.copyOf(rejected), executableCount);
        }
    }
}
