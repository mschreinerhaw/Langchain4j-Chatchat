package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlMetadataAssetRegistryServiceTest {

    @Test
    void syncDefaultForDatasourceSupportsMultipleScopeValues() {
        SqlMetadataAssetRegistryRepository repository = mock(SqlMetadataAssetRegistryRepository.class);
        List<SqlMetadataAssetRegistry> saved = new ArrayList<>();
        when(repository.findByDatasourceIdOrderByDatabaseNameAsc("ds-1")).thenReturn(List.of());
        when(repository.findByDatasourceIdAndDatabaseNameIgnoreCase(eq("ds-1"), any()))
            .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            SqlMetadataAssetRegistry registry = invocation.getArgument(0);
            saved.add(registry);
            return registry;
        });
        SqlMetadataAssetRegistryService service = new SqlMetadataAssetRegistryService(repository);
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/tpcds");
        datasource.setUsername("readonly");
        datasource.setEnabled(true);
        datasource.setMetadataAutoRefreshEnabled(true);
        datasource.setMetadataScopeType("EXPLICIT_SCHEMA");
        datasource.setMetadataScopeValue("rdsm_ad,a2_new\nops_db");

        SqlMetadataAssetRegistry first = service.syncDefaultForDatasource(datasource);

        assertThat(first.getDatabaseName()).isEqualTo("rdsm_ad");
        assertThat(saved).extracting(SqlMetadataAssetRegistry::getDatabaseName)
            .containsExactly("rdsm_ad", "a2_new", "ops_db");
        assertThat(saved).allSatisfy(registry -> {
            assertThat(registry.isEnabled()).isTrue();
            assertThat(registry.getRefreshMode()).isEqualTo("AUTO");
        });
    }
}
