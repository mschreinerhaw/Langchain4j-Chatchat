package com.chatchat.agents.runtime;

import java.util.concurrent.CompletableFuture;

public record AgentRunHandle(
    String runId,
    CompletableFuture<AgentRunResult> completion
) {
}
