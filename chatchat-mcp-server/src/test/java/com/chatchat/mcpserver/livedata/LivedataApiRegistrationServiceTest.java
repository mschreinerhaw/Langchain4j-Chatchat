package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiInvokeService;
import com.chatchat.mcpserver.api.ApiMcpToolPublisher;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.tools.livedata.LivedataApiDefinition;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LivedataApiRegistrationServiceTest {

    private final LivedataConfigService configService = mock(LivedataConfigService.class);
    private final LivedataApiConfigMapper mapper = mock(LivedataApiConfigMapper.class);
    private final ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
    private final ApiInvokeService apiInvokeService = mock(ApiInvokeService.class);
    private final HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
    private final ApiMcpToolPublisher publisher = mock(ApiMcpToolPublisher.class);
    private final LivedataApiDefinition definition = new LivedataApiDefinition(
        "source-1", "orders", "订单查询", "{}", "查询订单", "demo", "OrderService", "query", 0, "1", "1");
    private final LivedataApiRegistrationService service = new LivedataApiRegistrationService(
        configService, mapper, apiServiceConfigService, apiInvokeService, gatewayConfigService, publisher);

    @BeforeEach
    void enableLivedata() {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setEnabled(true);
        when(configService.current()).thenReturn(properties);
        when(configService.findApis()).thenReturn(List.of(definition));
    }

    @Test
    void testsRegisteredApiWithProvidedArguments() {
        ApiServiceConfig mapped = apiService("", "livedata_orders", "");
        ApiServiceConfig registered = apiService("service-1", "livedata_orders", "gateway-1");
        ApiInvokeResult expected = new ApiInvokeResult(true, 200, Map.of(), Map.of("ok", true), "{}", null);
        Map<String, Object> arguments = Map.of("orderId", "A001");
        when(mapper.toApiServiceConfig(definition)).thenReturn(mapped);
        when(apiServiceConfigService.findByToolName("livedata_orders")).thenReturn(Optional.of(registered));
        when(apiInvokeService.invoke(registered, arguments)).thenReturn(expected);

        assertThat(service.test("source-1", arguments)).isSameAs(expected);
        verify(apiInvokeService).invoke(registered, arguments);
    }

    @Test
    void deletesRegisteredServiceAndItsDedicatedGateway() {
        ApiServiceConfig mapped = apiService("", "livedata_orders", "");
        ApiServiceConfig registered = apiService("service-1", "livedata_orders", "gateway-1");
        HttpEndpointConfig mappedGateway = gateway("", "http_livedata_orders");
        HttpEndpointConfig registeredGateway = gateway("gateway-1", "http_livedata_orders");
        when(mapper.toApiServiceConfig(definition)).thenReturn(mapped);
        when(mapper.toGatewayConfig(definition)).thenReturn(mappedGateway);
        when(apiServiceConfigService.findByToolName("livedata_orders")).thenReturn(Optional.of(registered));
        when(gatewayConfigService.findByToolName("http_livedata_orders")).thenReturn(Optional.of(registeredGateway));
        when(apiServiceConfigService.listAll()).thenReturn(List.of(registered));

        LivedataApiRegistrationService.LivedataDeletionResult result = service.deleteRegistration("source-1");

        assertThat(result.gatewayDeleted()).isTrue();
        verify(apiServiceConfigService).delete("service-1");
        verify(gatewayConfigService).delete("gateway-1");
        verify(publisher).refresh();
    }

    private ApiServiceConfig apiService(String id, String toolName, String gatewayId) {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId(id);
        config.setToolName(toolName);
        config.setGatewayId(gatewayId);
        return config;
    }

    private HttpEndpointConfig gateway(String id, String toolName) {
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setId(id);
        config.setToolName(toolName);
        return config;
    }
}
