package com.chatchat.api.runtime;

import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable across service restarts; process-local registry counters remain useful only for invocation admission. */
final class AnalysisToolContractIdentity {
    private AnalysisToolContractIdentity() {}
    static String fingerprint(ToolMetadata metadata, ObjectMapper mapper) {
        try {
            byte[] bytes = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception invalid) { throw new IllegalStateException("Published tool contract cannot be fingerprinted", invalid); }
    }
}
