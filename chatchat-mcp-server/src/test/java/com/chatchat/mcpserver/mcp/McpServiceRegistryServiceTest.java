package com.chatchat.mcpserver.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServiceRegistryServiceTest {

    private final McpServiceRegistrationRepository repository = mock(McpServiceRegistrationRepository.class);
    private final McpTokenGenerator tokenGenerator = mock(McpTokenGenerator.class);
    private final McpServiceRegistryService service = new McpServiceRegistryService(
        repository, tokenGenerator, new ObjectMapper());

    @Test
    void repeatedLocalRegistrationUpdatesCanonicalRowWithoutRotatingIdentityOrToken() {
        McpServiceRegistration existing = registration("runtime-id", "old", "http://127.0.0.1:18081/mcp", "LOCAL");
        existing.setServiceToken("stable-token");
        McpServiceRegistration repeated = registration(null, "runtime", "http://127.0.0.1:18082/mcp", "LOCAL");
        repeated.setServiceToken("new-token-must-not-rotate-identity");
        when(repository.findFirstByServiceTypeIgnoreCaseOrderByCreatedAtAsc("LOCAL"))
            .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        McpServiceRegistration saved = service.create(repeated);

        assertThat(saved.getId()).isEqualTo("runtime-id");
        assertThat(saved.getServiceToken()).isEqualTo("stable-token");
        assertThat(saved.getName()).isEqualTo("runtime");
        assertThat(saved.getEndpoint()).isEqualTo("http://127.0.0.1:18082/mcp");
        verify(tokenGenerator, never()).generate();
    }

    @Test
    void remoteRegistrationsRemainMultiValued() {
        McpServiceRegistration remote = registration(null, "remote", "https://example.test/mcp", "REMOTE");
        when(tokenGenerator.generate()).thenReturn("generated-token");
        when(repository.existsByServiceToken("generated-token")).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        McpServiceRegistration saved = service.create(remote);

        assertThat(saved.getServiceToken()).isEqualTo("generated-token");
        verify(repository, never()).findFirstByServiceTypeIgnoreCaseOrderByCreatedAtAsc("LOCAL");
    }

    @Test
    void updateCannotConvertAnotherRegistrationIntoSecondLocalRuntime() {
        McpServiceRegistration current = registration("remote-id", "remote", "https://example.test/mcp", "REMOTE");
        McpServiceRegistration canonical = registration("runtime-id", "runtime", "http://127.0.0.1:18081/mcp", "LOCAL");
        McpServiceRegistration update = registration(null, "other runtime", "http://127.0.0.1:18082/mcp", "LOCAL");
        when(repository.findById("remote-id")).thenReturn(Optional.of(current));
        when(repository.findFirstByServiceTypeIgnoreCaseOrderByCreatedAtAsc("LOCAL"))
            .thenReturn(Optional.of(canonical));

        assertThatThrownBy(() -> service.update("remote-id", update))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only one LOCAL Runtime");
        verify(repository, never()).save(current);
    }

    private McpServiceRegistration registration(String id, String name, String endpoint, String type) {
        McpServiceRegistration value = new McpServiceRegistration();
        value.setId(id);
        value.setName(name);
        value.setEndpoint(endpoint);
        value.setServiceType(type);
        value.setEnvironment("PROD");
        value.setEnabled(true);
        value.setStatus("ACTIVE");
        if (id != null) {
            value.prePersist();
        }
        return value;
    }
}
