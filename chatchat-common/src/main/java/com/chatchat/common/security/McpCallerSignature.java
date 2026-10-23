package com.chatchat.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Authenticates business identity independently of model-controlled tool arguments. */
public final class McpCallerSignature {
    public static final String KEY = "callerProof";
    private McpCallerSignature() { }

    public static Map<String, Object> issue(String secret, String tenant, String user, String agent) {
        if (secret == null || secret.isBlank() || tenant == null || tenant.isBlank() || user == null || user.isBlank())
            throw new IllegalArgumentException("Configured signing secret and canonical caller identity are required");
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant); result.put("userId", user);
        result.put("agentId", agent == null ? "" : agent);
        result.put("timestamp", timestamp); result.put("nonce", nonce);
        result.put("signature", InternalRequestSigner.signBody(secret, "MCP", "/caller", timestamp, nonce,
            body(tenant, user, agent)));
        return Map.copyOf(result);
    }

    public static boolean verify(String secret, Map<String, Object> proof, long now) {
        if (secret == null || secret.isBlank()) return false;
        try {
            String tenant = text(proof, "tenantId"), user = text(proof, "userId");
            if (tenant.isBlank() || user.isBlank()) return false;
            String timestamp = text(proof, "timestamp"), nonce = text(proof, "nonce");
            long issued = Long.parseLong(timestamp);
            if (issued < now - 120_000L || issued > now + 10_000L || nonce.isBlank()) return false;
            return InternalRequestSigner.matches(text(proof, "signature"),
                InternalRequestSigner.signBody(secret, "MCP", "/caller", timestamp, nonce,
                    body(tenant, user, text(proof, "agentId"))));
        } catch (RuntimeException invalid) { return false; }
    }

    private static byte[] body(String tenant, String user, String agent) {
        // Length-prefixed fields prevent delimiter ambiguity without relying on JSON ordering.
        StringBuilder result = new StringBuilder();
        for (String value : new String[]{tenant, user, agent == null ? "" : agent}) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? "" : value.toString();
    }
}
