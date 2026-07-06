package com.chatchat.mcpserver.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AgentRuntimeTemplateDsl {

    public static final String SCHEMA_VERSION = "agent_runtime_template_dsl.v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentRuntimeTemplateDsl() {
    }

    public static boolean looksLikeDsl(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("{") && text.contains("\"steps\"");
    }

    public static TemplatePlan parse(String value,
                                     String fallbackTemplateCode,
                                     String fallbackTemplateType,
                                     String fallbackStepType) {
        if (!looksLikeDsl(value)) {
            return null;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(value, new TypeReference<>() {});
            Object rawSteps = root.get("steps");
            if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("Agent runtime template DSL steps cannot be empty");
            }
            List<TemplateStep> steps = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                if (!(list.get(index) instanceof Map<?, ?> rawStep)) {
                    throw new IllegalArgumentException("Agent runtime template DSL step must be an object at index " + index);
                }
                Map<String, Object> step = castMap(rawStep);
                int order = integer(step.get("order"), index + 1);
                String stepType = firstText(text(step.get("stepType")), text(step.get("type")), fallbackStepType);
                String command = firstText(text(step.get("command")), text(step.get("sql")), text(step.get("shell")));
                if (command == null || command.isBlank()) {
                    throw new IllegalArgumentException("Agent runtime template DSL step command/sql is required at order " + order);
                }
                steps.add(new TemplateStep(
                    firstText(text(step.get("stepCode")), text(step.get("code")), "STEP_" + order),
                    firstText(text(step.get("stepName")), text(step.get("name")), "Step " + order),
                    normalizeType(stepType, fallbackStepType),
                    order,
                    command.trim(),
                    bool(step.get("required"), false),
                    integerObject(step.get("timeoutSeconds")),
                    text(step.get("analysisHint"))
                ));
            }
            steps = steps.stream()
                .sorted(Comparator.comparingInt(TemplateStep::order))
                .toList();
            return new TemplatePlan(
                firstText(text(root.get("templateCode")), text(root.get("template")), fallbackTemplateCode),
                firstText(text(root.get("templateName")), text(root.get("name")), fallbackTemplateCode),
                firstText(text(root.get("templateType")), fallbackTemplateType),
                text(root.get("targetType")),
                firstText(text(root.get("executionMode")), "SEQUENTIAL"),
                bool(root.get("continueOnError"), true),
                text(root.get("riskLevel")),
                integerObject(root.get("timeoutSeconds")),
                objectMap(root.get("analysisPolicy")),
                steps,
                true
            );
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Agent runtime template DSL JSON is invalid: " + ex.getMessage());
        }
    }

    public static Map<String, Object> metadata(TemplatePlan plan) {
        if (plan == null) {
            return Map.of();
        }
        return mapOf(
            "schemaVersion", SCHEMA_VERSION,
            "templateCode", plan.templateCode(),
            "templateName", plan.templateName(),
            "templateType", plan.templateType(),
            "targetType", plan.targetType(),
            "executionMode", plan.executionMode(),
            "continueOnError", plan.continueOnError(),
            "riskLevel", plan.riskLevel(),
            "timeoutSeconds", plan.timeoutSeconds(),
            "analysisPolicy", plan.analysisPolicy(),
            "stepCount", plan.steps().size(),
            "steps", plan.steps().stream().map(AgentRuntimeTemplateDsl::stepMetadata).toList()
        );
    }

    public static Map<String, Object> stepMetadata(TemplateStep step) {
        if (step == null) {
            return Map.of();
        }
        return mapOf(
            "stepCode", step.stepCode(),
            "stepName", step.stepName(),
            "stepType", step.stepType(),
            "order", step.order(),
            "required", step.required(),
            "timeoutSeconds", step.timeoutSeconds(),
            "analysisHint", step.analysisHint()
        );
    }

    public static TemplateStep singleStep(int order, String stepType, String command) {
        return new TemplateStep(
            "STEP_" + order,
            "Step " + order,
            normalizeType(stepType, stepType),
            order,
            command,
            true,
            null,
            null
        );
    }

    private static String normalizeType(String value, String fallback) {
        String text = firstText(value, fallback, "COMMAND");
        return text.trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer integerObject(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return integer(value, 0);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    public record TemplatePlan(
        String templateCode,
        String templateName,
        String templateType,
        String targetType,
        String executionMode,
        boolean continueOnError,
        String riskLevel,
        Integer timeoutSeconds,
        Map<String, Object> analysisPolicy,
        List<TemplateStep> steps,
        boolean dsl
    ) {
        public TemplatePlan {
            analysisPolicy = analysisPolicy == null ? Map.of() : new LinkedHashMap<>(analysisPolicy);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record TemplateStep(
        String stepCode,
        String stepName,
        String stepType,
        int order,
        String command,
        boolean required,
        Integer timeoutSeconds,
        String analysisHint
    ) {
    }
}
