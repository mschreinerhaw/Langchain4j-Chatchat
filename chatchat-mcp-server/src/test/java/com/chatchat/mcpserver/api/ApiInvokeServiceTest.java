package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.ApiResponseCacheService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.tools.livedata.LivedataSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiInvokeServiceTest {

    @Test
    void invokeUsesBoundApiGatewayAssetForHttpTransport() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/customers/C001", exchange -> {
            requestedPath.set(exchange.getRequestURI().toString());
            byte[] body = "{\"ok\":true,\"customerId\":\"C001\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            ApiResponseCacheService cacheService = mock(ApiResponseCacheService.class);
            when(cacheService.get(any(), anyMap())).thenReturn(Optional.empty());
            HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
            HttpEndpointConfig gateway = new HttpEndpointConfig();
            gateway.setId("gateway-1");
            gateway.setName("Customer Gateway");
            gateway.setEnabled(true);
            gateway.setMethod("GET");
            gateway.setUrlTemplate("http://localhost:" + server.getAddress().getPort() + "/customers/{{customerId}}");
            gateway.setTimeoutMs(5000);
            when(gatewayConfigService.getById("gateway-1")).thenReturn(gateway);

            ApiInvokeService service = new ApiInvokeService(
                objectMapper,
                mock(InvocationAuditService.class),
                cacheService,
                mockObjectProvider(),
                new TemplateParameterValidator(objectMapper),
                gatewayConfigService
            );
            ApiServiceConfig config = new ApiServiceConfig();
            config.setId("api-1");
            config.setToolName("customer_query");
            config.setGatewayId("gateway-1");
            config.setInputSchemaJson("""
                {
                  "type": "object",
                  "properties": {
                    "customerId": { "type": "string" }
                  },
                  "required": ["customerId"],
                  "additionalProperties": false
                }
                """);

            ApiInvokeResult result = service.invoke(config, Map.of("customerId", "C001"));

            assertThat(result.success()).isTrue();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.rawBody()).contains("\"customerId\":\"C001\"");
            assertThat(requestedPath.get()).isEqualTo("/customers/C001");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void http200UnauthenticatedBusinessResponseIsReportedAsFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/livedata", exchange -> {
            byte[] body = "{\"note\":\"未知异常:UNAUTHENTICATED: 无权限\",\"code\":-10014}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            ApiResponseCacheService cacheService = mock(ApiResponseCacheService.class);
            when(cacheService.get(any(), anyMap())).thenReturn(Optional.empty());
            HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
            HttpEndpointConfig gateway = new HttpEndpointConfig();
            gateway.setId("gateway-1");
            gateway.setEnabled(true);
            gateway.setMethod("POST");
            gateway.setUrlTemplate("http://localhost:" + server.getAddress().getPort() + "/livedata");
            gateway.setBodyTemplate("{}");
            gateway.setTimeoutMs(5000);
            when(gatewayConfigService.getById("gateway-1")).thenReturn(gateway);
            ApiInvokeService service = new ApiInvokeService(
                objectMapper,
                mock(InvocationAuditService.class),
                cacheService,
                mockObjectProvider(),
                new TemplateParameterValidator(objectMapper),
                gatewayConfigService
            );
            ApiServiceConfig config = new ApiServiceConfig();
            config.setId("api-1");
            config.setToolName("livedata_test");
            config.setGatewayId("gateway-1");
            config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");

            ApiInvokeResult result = service.invoke(config, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.errorMessage()).isEqualTo("API authentication failed");
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LivedataSessionService> mockObjectProvider() {
        ObjectProvider<LivedataSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
