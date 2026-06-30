package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataIndexServiceTest {

    @Test
    void indexForUsesLocalSnapshotOnlyWhenNoPersistedMetadataExists() {
        MetadataIndexService service = new MetadataIndexService();
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-mysql");
        datasource.setDatabaseType("mysql");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/appdb");

        MetadataIndex index = service.indexFor(datasource);

        assertThat(index.datasourceId()).isEqualTo("ds-mysql");
        assertThat(index.databaseType()).isEqualTo("mysql");
        assertThat(index.tables()).isEmpty();
        assertThat(index.error()).isEqualTo("metadata_index_not_refreshed");
    }

    @Test
    void refreshEnabledDatasourcesOnlyRefreshesOptedInDatasources() {
        CountingMetadataIndexService service = new CountingMetadataIndexService();
        SqlDatasourceConfig manual = datasource("manual", false);
        SqlDatasourceConfig automatic = datasource("automatic", true);

        service.refreshEnabledDatasources(java.util.List.of(manual, automatic));

        assertThat(service.refreshCount).isEqualTo(1);
        assertThat(service.refreshedDatasourceId).isEqualTo("automatic");
    }

    @Test
    void refreshDatasourceRejectsUnsupportedDatabaseType() {
        MetadataIndexService service = new MetadataIndexService();
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-hive");
        datasource.setDatabaseType("hive");
        datasource.setJdbcUrl("jdbc:hive2://127.0.0.1:10000/default");

        MetadataIndexService.MetadataRefreshResult result = service.refreshDatasource(datasource);

        assertThat(result.datasourceId()).isEqualTo("ds-hive");
        assertThat(result.databaseType()).isEqualTo("hive");
        assertThat(result.schemaCount()).isZero();
        assertThat(result.tableCount()).isZero();
        assertThat(result.columnCount()).isZero();
        assertThat(result.persistedToRocksDb()).isFalse();
        assertThat(result.persistState().enabled()).isFalse();
        assertThat(result.persistState().status()).isEqualTo("SKIPPED");
        assertThat(result.persistState().message()).isEqualTo("unsupported_database_type");
        assertThat(result.error()).isEqualTo("unsupported_database_type");
    }

    private static SqlDatasourceConfig datasource(String id, boolean autoRefresh) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(id);
        datasource.setDatabaseType("mysql");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/appdb");
        datasource.setMetadataAutoRefreshEnabled(autoRefresh);
        return datasource;
    }

    private static class CountingMetadataIndexService extends MetadataIndexService {
        private int refreshCount;
        private String refreshedDatasourceId;

        @Override
        public MetadataRefreshResult refreshDatasource(SqlDatasourceConfig datasource) {
            refreshCount++;
            refreshedDatasourceId = datasource.getId();
            return null;
        }
    }
}
