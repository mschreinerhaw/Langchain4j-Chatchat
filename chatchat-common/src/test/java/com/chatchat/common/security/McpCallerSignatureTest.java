package com.chatchat.common.security;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class McpCallerSignatureTest {
    @Test void authenticatesEveryIdentityFieldAndRejectsExpiredOrUnsignedIdentity() {
        var proof = McpCallerSignature.issue("secret", "tenant", "user", "agent");
        assertTrue(McpCallerSignature.verify("secret", proof, System.currentTimeMillis()));
        assertFalse(McpCallerSignature.verify("other", proof, System.currentTimeMillis()));
        for (String field : java.util.List.of("tenantId", "userId", "agentId", "timestamp", "nonce", "signature")) {
            var tampered = new HashMap<String,Object>(proof); tampered.put(field, "forged");
            assertFalse(McpCallerSignature.verify("secret", tampered, System.currentTimeMillis()), field);
        }
        assertFalse(McpCallerSignature.verify("secret", proof, System.currentTimeMillis() + 121_000));
        assertFalse(McpCallerSignature.verify("secret", proof, System.currentTimeMillis() - 11_000));
        assertFalse(McpCallerSignature.verify("secret", java.util.Map.of(), System.currentTimeMillis()));
        assertFalse(McpCallerSignature.verify("", proof, System.currentTimeMillis()));
        assertThrows(IllegalArgumentException.class, () -> McpCallerSignature.issue("", "tenant", "user", "agent"));
    }
}
