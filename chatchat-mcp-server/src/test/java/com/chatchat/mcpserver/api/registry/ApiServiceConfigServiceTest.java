package com.chatchat.mcpserver.api.registry;

import com.chatchat.mcpserver.api.category.ApiServiceCategoryService;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiServiceConfigServiceTest {

    @Test
    void exposesGatewayParameterContractWhenLinkedApiServiceContractIsEmpty() {
        ApiServiceConfigRepository repository = mock(ApiServiceConfigRepository.class);
        HttpEndpointConfigService gateways = mock(HttpEndpointConfigService.class);
        ApiServiceConfig serviceConfig = new ApiServiceConfig();
        serviceConfig.setToolName("orders_api");
        serviceConfig.setGatewayId("gateway-1");
        serviceConfig.setInputSchemaJson(
            "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        gateway.setCategoryId("market-id");
        gateway.setInputSchemaJson("""
            {"type":"object","properties":{"orderId":{"type":"string","default":"A001"}},"required":[]}
            """);
        when(repository.findAll()).thenReturn(List.of(serviceConfig));
        when(gateways.getById("gateway-1")).thenReturn(gateway);
        ApiServiceCategoryService categories = mock(ApiServiceCategoryService.class);
        doAnswer(invocation -> {
            ApiServiceConfig target = invocation.getArgument(0);
            target.setCategoryId(invocation.getArgument(1));
            target.setBusinessGroup("market_data");
            return null;
        }).when(categories).applyExplicit(serviceConfig, "market-id");
        ApiServiceConfigService service = new ApiServiceConfigService(
            repository,
            mock(ToolRegistry.class),
            new ObjectMapper(),
            gateways,
            categories
        );

        ApiServiceConfig listed = service.listAll().get(0);

        assertThat(listed.getInputSchemaJson()).contains("\"orderId\"", "\"default\":\"A001\"");
        assertThat(listed.getCategoryId()).isEqualTo("market-id");
        assertThat(listed.getBusinessGroup()).isEqualTo("market_data");
    }

    @Test
    void synchronizesApiServiceCategoryBackToLinkedGateway() {
        ApiServiceConfigRepository repository = mock(ApiServiceConfigRepository.class);
        HttpEndpointConfigService gateways = mock(HttpEndpointConfigService.class);
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        gateway.setCategoryId("default-id");
        when(gateways.getById(gateway.getId())).thenReturn(gateway);
        when(repository.save(any(ApiServiceConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ApiServiceConfig draft = new ApiServiceConfig();
        draft.setToolName("customer_lookup");
        draft.setTitle("Customer lookup");
        draft.setGatewayId(gateway.getId());
        draft.setCategoryId("customer-id");
        draft.setBusinessGroup("customer_analysis");
        ApiServiceConfigService service = new ApiServiceConfigService(
            repository,
            mock(ToolRegistry.class),
            new ObjectMapper(),
            gateways,
            mock(ApiServiceCategoryService.class)
        );

        service.create(draft);

        verify(gateways).updateBusinessCategory(gateway.getId(), "customer-id");
    }
}
