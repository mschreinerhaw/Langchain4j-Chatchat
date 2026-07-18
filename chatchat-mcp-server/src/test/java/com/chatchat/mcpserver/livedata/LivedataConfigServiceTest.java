package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LivedataConfigServiceTest {

    @Test
    void requiresDatasourceAssetBeforeLoadingApisWhenFallbackJdbcUrlIsMissing() {
        LivedataConfigRepository repository = mock(LivedataConfigRepository.class);
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
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
            driverLoader
        );

        assertThatThrownBy(service::findApis)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("select an enabled SQL datasource asset");
        verifyNoInteractions(driverLoader);
    }
}
