package com.chatchat.mcpserver.ops;

public record LinuxCommandStepResult(
    int stepIndex,
    String command,
    String commandHash,
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean success
) {
}
