package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAuthorizationTokenRenewalTest {
    HttpServer server;
    AtomicInteger logins = new AtomicInteger();
    AtomicInteger reads = new AtomicInteger();
    McpAuthorizationProperties properties;
    int mode;

    @BeforeEach void httpApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            int login = logins.incrementAndGet();
            byte[] bytes = ("{\"data\":{\"token\":\"token-" + login + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/snapshot", exchange -> {
            reads.incrementAndGet();
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            boolean allowed = mode == 0 && "Bearer token-2".equals(token);
            int status = allowed ? 200 : mode == 2 ? 403 : 401;
            byte[] bytes = "{\"data\":{\"revision\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new McpAuthorizationProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setSnapshotPath("/snapshot");
        properties.getAuth().setLoginPath("/login");
        properties.getAuth().setUsername("test-user");
        properties.getAuth().setEncryptedPassword("encrypted-test-fixture");
    }
    @AfterEach void close() { server.stop(0); }

    @Test void staleCachedLoginTokenRenewsOnceAndReadsTheCurrentExecutionSnapshot() throws Exception {
        var service = service();
        assertThat(service.fetch().path("revision").asInt()).isEqualTo(2);
        assertThat(service.fetch().path("revision").asInt()).isEqualTo(2);
        assertThat(logins).hasValue(2);
        assertThat(reads).hasValue(3);
    }
    @Test void repeatedUnauthorizedResponseStopsAfterOneRenewal() {
        mode = 1;
        assertThatThrownBy(() -> service().fetch()).isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
        assertThat(logins).hasValue(2);
        assertThat(reads).hasValue(2);
    }
    @Test void permissionDenialDoesNotTriggerCredentialRenewal() {
        mode = 2;
        assertThatThrownBy(() -> service().fetch()).isInstanceOf(IllegalStateException.class).hasMessageContaining("403");
        assertThat(logins).hasValue(1);
        assertThat(reads).hasValue(1);
    }
    @Test void explicitBearerTokenNeverFallsBackToLoginCredentials() {
        properties.getAuth().setBearerToken("pinned-token");
        assertThatThrownBy(() -> service().fetch()).isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
        assertThat(logins).hasValue(0);
        assertThat(reads).hasValue(1);
    }
    private Fetcher service() { return new Fetcher(properties); }
    private static class Fetcher extends McpAuthorizationService {
        Fetcher(McpAuthorizationProperties properties) {
            super(properties, credentials(), new ObjectMapper(), mock(McpSynchronizedRoleRepository.class));
        }
        private static InternalCredentialProperties credentials() {
            var credentials = mock(InternalCredentialProperties.class);
            when(credentials.resolveSecret("encrypted-test-fixture", "")).thenReturn("test-password");
            return credentials;
        }
        JsonNode fetch() throws Exception { return fetchExecutionSnapshot(); }
    }
}
