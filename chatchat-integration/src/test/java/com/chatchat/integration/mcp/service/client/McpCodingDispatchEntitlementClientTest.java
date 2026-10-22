package com.chatchat.integration.mcp.service.client;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpCodingDispatchEntitlementClientTest {
    @Test
    void readsCurrentSignedEntitlementAndDeniesUnavailableOrMalformedResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<>("{\"code\":200,\"data\":{\"licenseValid\":false,\"licenseStatus\":\"EXPIRED\"}}");
        var signature = new AtomicReference<String>();
        server.createContext(McpLicenseEntitlementClient.CODING_DISPATCH_PATH, exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE_HEADER));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        var properties = new McpCenterProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedUsername()).thenReturn("internal");
        when(credentials.resolvedSecret()).thenReturn("0123456789abcdef0123456789abcdef");
        var client = new McpLicenseEntitlementClient(properties, credentials);
        try {
            assertThat(client.codingDispatchEntitlement().licenseStatus()).isEqualTo("EXPIRED");
            assertThat(client.codingDispatchEntitlement().licenseValid()).isFalse();
            assertThat(signature.get()).isNotBlank();
            body.set("{\"code\":200,\"data\":{\"licenseValid\":true,\"licenseStatus\":\"VALID\"}}");
            assertThat(client.codingDispatchEntitlement().licenseValid()).isTrue();
            body.set("{\"code\":200,\"data\":{\"licenseValid\":true,\"licenseStatus\":\"EXPIRED\"}}");
            assertThat(client.codingDispatchEntitlement().licenseValid()).isFalse();
            body.set("{\"code\":200,\"data\":{}}");
            assertThat(client.codingDispatchEntitlement().licenseValid()).isFalse();
            body.set("{\"code\":503,\"message\":\"unavailable\"}");
            assertThat(client.codingDispatchEntitlement().licenseStatus()).isEqualTo("UNAVAILABLE");
        } finally { server.stop(0); }
        assertThat(client.codingDispatchEntitlement().licenseValid()).isFalse();
    }
}
