package com.chatchat.mcpserver.ops;

public record LinuxCommandStepResult(
    int stepIndex,
    String stepCode,
    String stepName,
    String stepType,
    boolean required,
    String analysisHint,
    String command,
    String commandHash,
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean success
) {
    public LinuxCommandStepResult(
        int stepIndex,
        String command,
        String commandHash,
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean success
    ) {
        this(
            stepIndex,
            "STEP_" + stepIndex,
            "Step " + stepIndex,
            "SHELL",
            true,
            null,
            command,
            commandHash,
            exitCode,
            stdout,
            stderr,
            durationMs,
            success
        );
    }
}
