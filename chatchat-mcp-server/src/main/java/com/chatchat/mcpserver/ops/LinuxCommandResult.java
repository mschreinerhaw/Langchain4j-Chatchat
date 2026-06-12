package com.chatchat.mcpserver.ops;

import java.util.Map;

public record LinuxCommandResult(
    boolean success,
    String hostId,
    String host,
    String toolName,
    String environment,
    String template,
    String command,
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    String errorMessage,
    Map<String, Object> request
) {
}
