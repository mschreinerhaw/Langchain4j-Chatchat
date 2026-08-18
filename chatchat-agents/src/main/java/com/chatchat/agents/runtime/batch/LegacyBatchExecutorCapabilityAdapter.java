package com.chatchat.agents.runtime.batch;

import java.util.Locale;
import java.util.Set;

/**
 * Compatibility adapter for executors registered before runtime capabilities
 * became part of tool metadata.
 *
 * <p>This is deliberately outside the orchestration kernel. New executors must
 * declare {@code batch_execution} through metadata and must not be added here.</p>
 */
final class LegacyBatchExecutorCapabilityAdapter {

    private static final Set<String> LEGACY_PROTOCOL_NAMES = Set.of(
        "sql_query_execute",
        "ssh_linux_execute",
        "linux_command_execute",
        "api_query_execute",
        "api_template_execute",
        "http_request_execute"
    );

    private LegacyBatchExecutorCapabilityAdapter() {
    }

    static boolean supports(String toolName) {
        String normalized = normalize(toolName);
        return LEGACY_PROTOCOL_NAMES.stream()
            .anyMatch(name -> normalized.equals(name) || normalized.endsWith("_" + name));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
