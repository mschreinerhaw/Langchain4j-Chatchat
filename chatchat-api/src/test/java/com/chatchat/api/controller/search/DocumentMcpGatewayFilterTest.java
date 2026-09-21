package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class DocumentMcpGatewayFilterTest {
    @Test
    void forwardsUploadBytesWithAuthenticatedIdentity() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/api/v1/search/documents/upload", exchange -> {
            received.set(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("X-Document-User-Id")
                + "|" + exchange.getRequestHeaders().getFirst("X-Document-Tenant-Id") + "|"
                + exchange.getRequestHeaders().getFirst("X-Document-Gateway-Token") + "|"
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DocumentMcpGatewayFilter filter = new DocumentMcpGatewayFilter(
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/search/documents/upload");
            request.setContent("document bytes".getBytes(StandardCharsets.UTF_8));
            request.setContentType("multipart/form-data; boundary=test");
            request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
            request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> fail("local search must not run"));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
            assertThat(received.get()).isEqualTo("POST|user-1|tenant-1|secret|document bytes");
        } finally {
            server.stop(0);
        }
    }
}
