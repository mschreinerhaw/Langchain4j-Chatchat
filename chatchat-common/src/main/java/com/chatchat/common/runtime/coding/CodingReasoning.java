package com.chatchat.common.runtime.coding;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen request preference and provider parameter. Neither field grants model access. */
public final class CodingReasoning {
    public static final String REQUEST_KEY = "codingReasoningEffort";
    public static final String OFF = "off";
    public static final String CONTEXT_KEY = "reasoning";
    public static final List<String> LEVELS = List.of("minimal", "low", "medium", "high", "xhigh");
    public static final Set<String> WIRE_LEVELS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "no_think");
    public static final Set<String> PARAMETERS = Set.of("standard","deepseek-thinking","qwen-thinking","hunyuan-template");

    public static boolean permits(String parameter,String effort) {
        return switch (parameter) {
            case "hunyuan-template" -> Set.of("no_think","low","high").contains(effort);
            case "deepseek-thinking" -> Set.of("low","high","max").contains(effort);
            case "qwen-thinking" -> WIRE_LEVELS.contains(effort) && !"no_think".equals(effort);
            case "standard" -> WIRE_LEVELS.contains(effort);
            default -> false;
        };
    }

    private CodingReasoning() {}

    public static String requested(Map<String, ?> input) {
        Object value = input == null ? null : input.get(REQUEST_KEY);
        if (value == null) return null;
        if (value instanceof String level && ("auto".equals(level) || OFF.equals(level) || LEVELS.contains(level))) return level;
        throw new IllegalArgumentException("Invalid Coding reasoning effort");
    }

    public static String requestedFromContext(Map<String, ?> context) {
        return read(context, "requested");
    }

    public static String effective(Map<String, ?> context) {
        return read(context, "effective");
    }

    public static String parameter(Map<String, ?> context) {
        return read(context,"parameter");
    }

    public static String submittedFromContext(Map<String, ?> context) {
        return read(context,"source") == null ? requestedFromContext(context) : null;
    }

    private static String read(Map<String, ?> context, String field) {
        return context.get(CONTEXT_KEY) instanceof Map<?, ?> setting && setting.get(field) instanceof String level
            ? level : null;
    }

    static void validate(Object value) {
        CodingProtocol.require(value instanceof Map<?, ?>, "Invalid frozen Coding reasoning");
        var setting = (Map<?, ?>) value;
        CodingProtocol.require(Set.of("requested","effective","parameter","source").containsAll(setting.keySet())
            && setting.get("requested") instanceof String requested
            && ("auto".equals(requested) || OFF.equals(requested) || LEVELS.contains(requested))
            && (!setting.containsKey("source") || "model-default".equals(setting.get("source")) && (OFF.equals(requested) || LEVELS.contains(requested)))
            && (!setting.containsKey("parameter") || setting.get("parameter") instanceof String parameter && PARAMETERS.contains(parameter))
            && (!setting.containsKey("effective") || setting.get("effective") instanceof String effort
                && (setting.get("parameter") instanceof String format ? permits(format,effort)
                    : WIRE_LEVELS.contains(effort) && ("auto".equals(requested) || requested.equals(effort))))
            && (!OFF.equals(requested) || !setting.containsKey("effective"))
            && (!setting.containsKey("parameter") || setting.containsKey("effective")), "Invalid frozen Coding reasoning");
    }
}
