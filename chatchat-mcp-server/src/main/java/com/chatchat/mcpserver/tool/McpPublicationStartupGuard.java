package com.chatchat.mcpserver.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/** Isolates one MCP publisher failure from the remaining Spring startup listeners. */
@Slf4j
public final class McpPublicationStartupGuard {

    private McpPublicationStartupGuard() {
    }

    public static StartupPublicationResult run(Class<?> publisherType, Runnable publication) {
        Objects.requireNonNull(publisherType, "publisherType");
        Objects.requireNonNull(publication, "publication");
        String publisher = publisherType.getName();
        long startedAt = System.currentTimeMillis();
        try {
            publication.run();
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.info("MCP startup publication completed publisher={} status=SUCCESS durationMs={}",
                publisher, durationMs);
            return new StartupPublicationResult(publisher, true, durationMs, null, null);
        } catch (Exception failure) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.error("MCP startup publication failed publisher={} status=FAILED durationMs={} errorType={} error={}",
                publisher, durationMs, failure.getClass().getName(), failure.getMessage(), failure);
            return new StartupPublicationResult(
                publisher, false, durationMs, failure.getClass().getName(), failure.getMessage());
        }
    }

    public record StartupPublicationResult(
        String publisher,
        boolean success,
        long durationMs,
        String errorType,
        String errorMessage
    ) {
    }
}
