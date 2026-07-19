package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LivedataConfigServiceTest {

    @Test
    void requiresDatasourceAssetBeforeLoadingApisWhenFallbackJdbcUrlIsMissing() {
        LivedataConfigRepository repository = mock(LivedataConfigRepository.class);
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
        DynamicJdbcDriverLoader driverLoader = mock(DynamicJdbcDriverLoader.class);
        LivedataAutoRegistrationProperties fallbackProperties = new LivedataAutoRegistrationProperties();
        fallbackProperties.setEnabled(true);

        LivedataConfig config = new LivedataConfig();
        config.setEnabled(true);
        config.setDatasourceId(null);
        when(repository.findById(LivedataConfig.SINGLETON_ID)).thenReturn(Optional.of(config));

        LivedataConfigService service = new LivedataConfigService(
            repository,
            fallbackProperties,
            datasourceConfigService,
            gatewayConfigService,
            driverLoader,
            new ObjectMapper()
        );

        assertThatThrownBy(service::findApis)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("select an enabled SQL datasource asset");
        verifyNoInteractions(driverLoader);
    }

    @Test
    void resolvesServiceBaseUrlFromSelectedEnabledGatewayAsset() {
        LivedataConfigRepository repository = mock(LivedataConfigRepository.class);
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
        DynamicJdbcDriverLoader driverLoader = mock(DynamicJdbcDriverLoader.class);
        LivedataAutoRegistrationProperties fallbackProperties = new LivedataAutoRegistrationProperties();
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        gateway.setEnabled(true);
        gateway.setUrlTemplate("http://192.168.195.221:8090");
        when(gatewayConfigService.getById("gateway-1")).thenReturn(gateway);
        when(repository.findById(LivedataConfig.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(LivedataConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LivedataConfig request = new LivedataConfig();
        request.setEnabled(true);
        request.setDatasourceId("datasource-1");
        request.setGatewayId("gateway-1");
        request.setServiceBaseUrl("http://untrusted.example");
        LivedataConfigService service = new LivedataConfigService(
            repository, fallbackProperties, datasourceConfigService, gatewayConfigService, driverLoader, new ObjectMapper()
        );

        LivedataConfig saved = service.save(request);

        assertThat(saved.getGatewayId()).isEqualTo("gateway-1");
        assertThat(saved.getServiceBaseUrl()).isEqualTo("http://192.168.195.221:8090");
    }

    @Test
    void removesLoginPathFromSelectedGatewayWhenBuildingServiceBaseUrl() {
        LivedataConfigRepository repository = mock(LivedataConfigRepository.class);
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
        DynamicJdbcDriverLoader driverLoader = mock(DynamicJdbcDriverLoader.class);
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        gateway.setEnabled(true);
        gateway.setUrlTemplate("http://192.168.195.224:5006/login");
        when(gatewayConfigService.getById("gateway-1")).thenReturn(gateway);
        when(repository.findById(LivedataConfig.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(LivedataConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LivedataConfig request = new LivedataConfig();
        request.setEnabled(true);
        request.setDatasourceId("datasource-1");
        request.setGatewayId("gateway-1");
        request.setLoginPath("/login");
        LivedataConfigService service = new LivedataConfigService(
            repository, new LivedataAutoRegistrationProperties(), datasourceConfigService, gatewayConfigService, driverLoader,
            new ObjectMapper()
        );

        assertThat(service.save(request).getServiceBaseUrl()).isEqualTo("http://192.168.195.224:5006");
    }

    @Test
    void resolvesLoginCredentialsFromSelectedGatewayWithoutPersistingThemInLivedataConfig() {
        LivedataConfigRepository repository = mock(LivedataConfigRepository.class);
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService gatewayConfigService = mock(HttpEndpointConfigService.class);
        DynamicJdbcDriverLoader driverLoader = mock(DynamicJdbcDriverLoader.class);
        LivedataConfig config = new LivedataConfig();
        config.setGatewayId("gateway-1");
        config.setLoginId(null);
        config.setLoginPwd(null);
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("gateway-1");
        gateway.setEnabled(true);
        gateway.setBodyTemplate("{\"loginId\":\"gateway-account\",\"loginPwd\":\"gateway-secret\"}");
        when(repository.findById(LivedataConfig.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(gatewayConfigService.getById("gateway-1")).thenReturn(gateway);
        LivedataConfigService service = new LivedataConfigService(
            repository, new LivedataAutoRegistrationProperties(), datasourceConfigService, gatewayConfigService, driverLoader,
            new ObjectMapper()
        );

        LivedataAutoRegistrationProperties properties = service.current();

        assertThat(properties.getLoginId()).isEqualTo("gateway-account");
        assertThat(properties.getLoginPwd()).isEqualTo("gateway-secret");
        assertThat(config.getLoginId()).isNull();
        assertThat(config.getLoginPwd()).isNull();
    }
}
