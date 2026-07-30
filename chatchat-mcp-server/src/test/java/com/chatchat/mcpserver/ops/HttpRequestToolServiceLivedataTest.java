package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.tools.livedata.LivedataSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpRequestToolServiceLivedataTest {

    @Test
    void gatewayTestRefreshesInvalidLivedataSessionAndRetriesWithFreshSession() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> retriedSession = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/service/com.apex.livedata.cx_ds_by_tab/call", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String sessionId = request.path("sessionId").asText();
            int call = calls.incrementAndGet();
            String response;
            if (call == 1) {
                response = "{\"code\":-10002,\"note\":\"sessionId无效\"}";
            } else {
                retriedSession.set(sessionId);
                response = "{\"code\":0,\"data\":[{\"table\":\"customer\"}]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            InvocationAuditService auditService = mock(InvocationAuditService.class);
            LivedataSessionService sessionService = mock(LivedataSessionService.class);
            when(sessionService.currentSessionId()).thenReturn("expired-session");
            when(sessionService.refreshSessionId()).thenReturn("fresh-session");
            HttpRequestToolService service = service(objectMapper, auditService, sessionService);

            HttpRequestToolResult result = service.execute(
                endpoint(server.getAddress().getPort()), Map.of("sourceTaskId", "asset-center"));

            assertThat(result.success()).isTrue();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.errorMessage()).isNull();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(retriedSession.get()).isEqualTo("fresh-session");
            verify(sessionService).refreshSessionId();
            verify(auditService).recordOpsHttpCall(anyMap(), any(HttpRequestToolResult.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayTestReportsHttp200LivedataBusinessErrorAsFailureAfterRetry() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/service/com.apex.livedata.cx_ds_by_tab/call", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"code\":-10002,\"note\":\"sessionId无效\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            LivedataSessionService sessionService = mock(LivedataSessionService.class);
            when(sessionService.currentSessionId()).thenReturn("expired-session");
            when(sessionService.refreshSessionId()).thenReturn("also-invalid-session");
            HttpRequestToolService service = service(
                objectMapper, mock(InvocationAuditService.class), sessionService);

            HttpRequestToolResult result = service.execute(
                endpoint(server.getAddress().getPort()), Map.of("sourceTaskId", "asset-center"));

            assertThat(result.success()).isFalse();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.errorMessage()).contains("sessionId无效", "code=-10002");
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayTestReportsGenericHttp200BusinessFailureAsFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/business-api", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"code\":500,\"message\":\"服务执行异常\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            HttpRequestToolService service = service(
                objectMapper, mock(InvocationAuditService.class), mock(LivedataSessionService.class));
            HttpEndpointConfig endpoint = endpoint(server.getAddress().getPort());
            endpoint.setName("Generic business API");
            endpoint.setToolName("generic_business_api");
            endpoint.setUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort() + "/business-api");
            endpoint.setBodyTemplate("{}");

            HttpRequestToolResult result = service.execute(endpoint, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.errorMessage()).contains("服务执行异常", "code=500");
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpRequestToolService service(ObjectMapper objectMapper,
                                           InvocationAuditService auditService,
                                           LivedataSessionService sessionService) {
        TemplateParameterValidator validator = mock(TemplateParameterValidator.class);
        when(validator.validateDeclaredOnly(anyString(), any(), anyMap(), anyMap()))
            .thenAnswer(invocation -> new LinkedHashMap<>((Map<String, Object>) invocation.getArgument(3)));
        ObjectProvider<LivedataSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sessionService);
        return new HttpRequestToolService(objectMapper, auditService, validator, provider);
    }

    private HttpEndpointConfig endpoint(int port) {
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setId("4277d6f8-743d-4607-9e17-9252369ba709");
        config.setName("LiveData Gateway - cx_ds_by_tab");
        config.setToolName("http_livedata_cx_ds_by_tab");
        config.setMethod("POST");
        config.setUrlTemplate("http://127.0.0.1:" + port
            + "/service/com.apex.livedata.cx_ds_by_tab/call");
        config.setHeadersJson("{\"Content-Type\":\"application/json;charset=UTF-8\"}");
        config.setBodyTemplate("""
            {
              "sessionId": "persisted-expired-session",
              "namespace": "livedata",
              "head": {"x-ams-token": "test-token"},
              "data": {}
            }
            """);
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        config.setTimeoutMs(5000);
        config.setCategory("api_gateway");
        config.setEnabled(true);
        return config;
    }
}
