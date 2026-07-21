package com.chatchat.mcpserver.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpEndpointConfigServiceTest {

    @Test
    void addsGatewayRoutingAndCapabilityLabelsWhenCreatingAnUnlabelledAsset() throws Exception {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        when(repository.findByNameIgnoreCase("订单查询网关")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("http_order_query")).thenReturn(Optional.empty());
        when(repository.save(any(HttpEndpointConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper objectMapper = new ObjectMapper();
        HttpEndpointConfigService service = new HttpEndpointConfigService(repository, objectMapper);
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setName("订单查询网关");
        config.setToolName("http_order_query");
        config.setUrlTemplate("https://api.example.com/orders");
        config.setRoutingLabelsJson("[]");
        config.setCapabilitiesJson("[]");

        HttpEndpointConfig saved = service.create(config);

        assertThat(objectMapper.readValue(saved.getRoutingLabelsJson(), String[].class))
            .containsExactly("api_gateway", "http_endpoint");
        assertThat(objectMapper.readValue(saved.getCapabilitiesJson(), String[].class))
            .containsExactly("api_gateway", "http", "http_request");
        assertThat(saved.getTags()).contains("api_gateway", "http_endpoint", "http_request");
    }
}
