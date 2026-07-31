package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpInternalCredentialFallbackTest {

    @Test
    void authorizationDefaultsDoNotInjectLegacyPlaintextCredentials() {
        McpAuthorizationProperties.Auth auth = new McpAuthorizationProperties().getAuth();

        assertThat(auth.getUsername()).isBlank();
        assertThat(auth.getPassword()).isBlank();
        assertThat(auth.getEncryptedPassword()).isBlank();
    }

    @Test
    void documentSearchDefaultsDoNotInjectLegacyPlaintextCredentials() {
        var auth = new ChatChatMcpServerProperties().getDocumentSearch().getAuth();

        assertThat(auth.getUsername()).isBlank();
        assertThat(auth.getPassword()).isBlank();
        assertThat(auth.getEncryptedPassword()).isBlank();
    }

    @Test
    void authorizationFallsBackToSharedEncryptedInternalCredential() throws Exception {
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedUsername()).thenReturn("chatchat_mcp_internal");
        when(credentials.resolveSecret("", "")).thenReturn("");
        when(credentials.resolvedSecret()).thenReturn("decrypted-internal-secret");
        McpAuthorizationService service = new McpAuthorizationService(
            new McpAuthorizationProperties(),
            credentials,
            new ObjectMapper(),
            mock(McpSynchronizedRoleRepository.class)
        );

        Method username = McpAuthorizationService.class.getDeclaredMethod("authUsername", String.class);
        Method password = McpAuthorizationService.class.getDeclaredMethod(
            "authPassword",
            String.class,
            String.class
        );
        username.setAccessible(true);
        password.setAccessible(true);

        assertThat(username.invoke(service, "")).isEqualTo("chatchat_mcp_internal");
        assertThat(password.invoke(service, "", "")).isEqualTo("decrypted-internal-secret");
    }
}
