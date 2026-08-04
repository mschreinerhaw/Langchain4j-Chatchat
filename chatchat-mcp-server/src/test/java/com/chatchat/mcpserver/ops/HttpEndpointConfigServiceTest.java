package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpEndpointConfigServiceTest {

    @Test
    void addsGatewayRoutingAndCapabilityLabelsWhenCreatingAnUnlabelledAsset() throws Exception {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        when(repository.findByNameIgnoreCase("订单查询网关")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("http_order_query")).thenReturn(Optional.empty());
        when(repository.save(any(HttpEndpointConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper objectMapper = new ObjectMapper();
        BusinessCategoryService categoryService = defaultCategoryService();
        ApiServiceConfigRepository apiServices = mock(ApiServiceConfigRepository.class);
        HttpEndpointConfigService service = new HttpEndpointConfigService(
            repository, objectMapper, categoryService, apiServices);
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
        assertThat(saved.getTechnicalType()).isEqualTo("HTTP");
    }

    @Test
    void normalizesMicroserviceTechnicalType() {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        when(repository.findByNameIgnoreCase("订单微服务")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("http_order_service")).thenReturn(Optional.empty());
        when(repository.save(any(HttpEndpointConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        HttpEndpointConfigService service = new HttpEndpointConfigService(
            repository, new ObjectMapper(), defaultCategoryService(), mock(ApiServiceConfigRepository.class));
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setName("订单微服务");
        config.setToolName("http_order_service");
        config.setUrlTemplate("https://api.example.com/orders");
        config.setTechnicalType("microservice");

        assertThat(service.create(config).getTechnicalType()).isEqualTo("MICROSERVICE");
    }

    @Test
    void treatsLegacyLivedataGatewayAsMicroserviceWhenClassificationIsMissing() {
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setTechnicalType(null);
        config.setTags("livedata,api_gateway,http");

        config.applyTechnicalTypeDefault();

        assertThat(config.getTechnicalType()).isEqualTo("MICROSERVICE");
    }

    @Test
    void appendsDefaultCapabilityWhenCreatingAnAssetWithCustomCapabilities() throws Exception {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        when(repository.findByNameIgnoreCase("行情查询网关")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("http_market_quote")).thenReturn(Optional.empty());
        when(repository.save(any(HttpEndpointConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper objectMapper = new ObjectMapper();
        BusinessCategoryService categoryService = defaultCategoryService();
        ApiServiceConfigRepository apiServices = mock(ApiServiceConfigRepository.class);
        HttpEndpointConfigService service = new HttpEndpointConfigService(
            repository, objectMapper, categoryService, apiServices);
        HttpEndpointConfig config = new HttpEndpointConfig();
        config.setName("行情查询网关");
        config.setToolName("http_market_quote");
        config.setUrlTemplate("https://api.example.com/market/quote");
        config.setCapabilitiesJson("[\"market_quote\"]");

        HttpEndpointConfig saved = service.create(config);

        assertThat(objectMapper.readValue(saved.getCapabilitiesJson(), String[].class))
            .containsExactly("market_quote", "http_request");
        assertThat(saved.getTags()).contains("market_quote", "http_request");
    }

    @Test
    void synchronizesGatewayCategoryToEveryLinkedApiService() {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        ApiServiceConfigRepository apiServices = mock(ApiServiceConfigRepository.class);
        BusinessCategoryService categories = mock(BusinessCategoryService.class);
        BusinessCategory category = new BusinessCategory();
        category.setId("market-id");
        category.setCode("market_data");
        category.setName("市场行情");
        category.setDescription("行情查询");
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        ApiServiceConfig first = new ApiServiceConfig();
        ApiServiceConfig second = new ApiServiceConfig();
        when(repository.findById("gateway-1")).thenReturn(Optional.of(gateway));
        when(repository.save(gateway)).thenReturn(gateway);
        when(categories.resolveOrDefault("market-id")).thenReturn(category);
        when(apiServices.findByGatewayId("gateway-1")).thenReturn(java.util.List.of(first, second));
        HttpEndpointConfigService service = new HttpEndpointConfigService(
            repository, new ObjectMapper(), categories, apiServices);

        service.updateBusinessCategory("gateway-1", "market-id");

        assertThat(first.getCategoryId()).isEqualTo("market-id");
        assertThat(second.getBusinessGroup()).isEqualTo("market_data");
        verify(apiServices).save(first);
        verify(apiServices).save(second);
    }

    private BusinessCategoryService defaultCategoryService() {
        BusinessCategory category = new BusinessCategory();
        category.setId("default-id");
        category.setCode("default");
        category.setName("默认分类");
        BusinessCategoryService service = mock(BusinessCategoryService.class);
        when(service.resolveOrDefault(null)).thenReturn(category);
        return service;
    }
}
