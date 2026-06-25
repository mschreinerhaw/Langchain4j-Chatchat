package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.execution.ToolExecutionUnitFactory;

import java.util.List;
import java.util.Map;

public record LinuxCommandResult(
    boolean success,
    String hostId,
    String host,
    String toolName,
    String environment,
    String template,
    String command,
    String commandHash,
    List<LinuxCommandStepResult> steps,
    Integer failedStepIndex,
    String failedCommand,
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    String errorMessage,
    Map<String, Object> request,
    Map<String, Object> execution
) {
    public LinuxCommandResult {
        steps = steps == null ? List.of() : steps;
        execution = execution == null
            ? ToolExecutionUnitFactory.commandExecution(toolName, durationMs, steps)
            : execution;
    }

    public LinuxCommandResult(
        boolean success,
        String hostId,
        String host,
        String toolName,
        String environment,
        String template,
        String command,
        String commandHash,
        List<LinuxCommandStepResult> steps,
        Integer failedStepIndex,
        String failedCommand,
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        String errorMessage,
        Map<String, Object> request
    ) {
        this(
            success,
            hostId,
            host,
            toolName,
            environment,
            template,
            command,
            commandHash,
            steps,
            failedStepIndex,
            failedCommand,
            exitCode,
            stdout,
            stderr,
            durationMs,
            errorMessage,
            request,
            null
        );
    }
}
