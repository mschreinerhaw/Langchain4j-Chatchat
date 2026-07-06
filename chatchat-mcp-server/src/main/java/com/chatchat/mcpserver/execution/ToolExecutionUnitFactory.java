package com.chatchat.mcpserver.execution;

import com.chatchat.mcpserver.ops.LinuxCommandStepResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ToolExecutionUnitFactory {

    private ToolExecutionUnitFactory() {
    }

    public static Map<String, Object> execution(String toolName, long durationMs, List<Map<String, Object>> steps) {
        Instant finishedAt = Instant.now();
        Instant startedAt = finishedAt.minusMillis(Math.max(0L, durationMs));
        List<Map<String, Object>> safeSteps = steps == null ? List.of() : steps;
        return mapOf(
            "schemaVersion", "execution_unit.v1",
            "executionId", UUID.randomUUID().toString(),
            "toolName", toolName,
            "startedAt", startedAt.toString(),
            "finishedAt", finishedAt.toString(),
            "durationMs", durationMs,
            "stepCount", safeSteps.size(),
            "steps", safeSteps
        );
    }

    public static Map<String, Object> step(int stepIndex, String stepType, Map<String, Object> input,
                                           Map<String, Object> output, boolean success, long durationMs,
                                           String errorMessage, Map<String, Object> attributes) {
        Map<String, Object> value = mapOf(
            "stepIndex", stepIndex,
            "stepId", stepType + "_" + stepIndex,
            "stepType", stepType,
            "input", input == null ? Map.of() : input,
            "output", output == null ? Map.of() : output,
            "success", success,
            "status", success ? "success" : "failed",
            "durationMs", durationMs,
            "error", errorMessage == null || errorMessage.isBlank() ? null : mapOf("message", errorMessage)
        );
        if (attributes != null) {
            value.putAll(attributes);
        }
        return value;
    }

    public static Map<String, Object> commandExecution(String toolName, long durationMs,
                                                       List<LinuxCommandStepResult> steps) {
        return execution(toolName, durationMs, commandSteps(steps));
    }

    public static List<Map<String, Object>> commandSteps(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(step -> step(
                step.stepIndex(),
                "command",
                mapOf(
                    "stepCode", step.stepCode(),
                    "stepName", step.stepName(),
                    "stepType", step.stepType(),
                    "required", step.required(),
                    "analysisHint", step.analysisHint(),
                    "command", step.command(),
                    "commandHash", step.commandHash()
                ),
                mapOf(
                    "stdout", step.stdout(),
                    "stderr", step.stderr(),
                    "exitCode", step.exitCode()
                ),
                step.success(),
                step.durationMs(),
                step.success() ? null : firstText(step.stderr(), "Command step failed"),
                mapOf(
                    "stepCode", step.stepCode(),
                    "stepName", step.stepName(),
                    "required", step.required(),
                    "analysisHint", step.analysisHint(),
                    "exitCode", step.exitCode()
                )
            ))
            .toList();
    }

    public static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
