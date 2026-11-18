package com.chatchat.deployment.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionJsonSecurityTest {
    @Test void parameterViewsKeepOrdinaryEnvironmentAndMaskCredentialValues() throws Exception {
        var input=StateFiles.JSON.readTree("{\"environment\":{\"LANG\":\"C.UTF-8\",\"API_KEY\":\"do-not-return\",\"AWS_ACCESS_KEY_ID\":\"do-not-return\",\"DB_PASSWORD\":\"do-not-return\"},\"Cmd\":[\"worker\",\"--token\",\"do-not-return\"]}");
        var visible=ExecutionJson.redact(input);
        assertEquals("C.UTF-8",visible.path("environment").path("LANG").asText());
        assertFalse(visible.toString().contains("do-not-return"));
        assertEquals("do-not-return",input.path("environment").path("API_KEY").asText());
    }
}
