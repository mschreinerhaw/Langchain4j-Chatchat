package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.resolution.TableLocation;

import com.chatchat.mcpserver.ChatChatMcpServerApplication;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataRequestAdapter;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in business regression for a maintained datasource.
 *
 * <p>The target identifiers are supplied by the test invocation so this test
 * remains reusable and does not encode a table, tenant, or environment in the
 * runtime implementation.</p>
 */
class SqlMetadataLiveRegressionTest {

    private ConfigurableApplicationContext liveContext;

    @AfterEach
    void closeLiveContext() {
        if (liveContext != null) {
            liveContext.close();
            liveContext = null;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactMaintainedTableReturnsItsCompleteFieldMetadata() {
        String assetName = propertyOrFixture("chatchat.live.metadata.asset-name", "release-metadata-asset");
        String database = propertyOrFixture("chatchat.live.metadata.database", "release_catalog");
        String table = propertyOrFixture("chatchat.live.metadata.table", "release_customer");

        Map<String, Object> result = activeSearchService(assetName, database, table).search(Map.of(
            "defaultDataAsset", Map.of("assetName", assetName),
            "database", database,
            "tableName", table,
            "includeColumns", true,
            "detailLimit", 1,
            "catalogLimit", 10
        ));

        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("topTables");
        assertThat(tables).hasSize(1);
        assertThat((Map<String, Object>) tables.get(0).get("location"))
            .containsEntry("database", database)
            .containsEntry("tableName", table);
        assertThat((Number) tables.get(0).get("columnCount"))
            .matches(count -> count.intValue() > 0, "target table must expose at least one column");
        assertThat((List<Map<String, Object>>) tables.get(0).get("columns")).isNotEmpty();
        assertThat((Map<String, Object>) result.get("diagnostics"))
            .containsEntry("tableNameFilterApplied", true)
            .containsEntry("tableNameFilterMode", "exact_table_match");
    }

    @Test
    @SuppressWarnings("unchecked")
    void naturalLanguageTableReviewBecomesUnifiedFieldRequest() {
        String assetName = propertyOrFixture("chatchat.live.metadata.asset-name", "release-metadata-asset");
        String database = propertyOrFixture("chatchat.live.metadata.database", "release_catalog");
        String table = propertyOrFixture("chatchat.live.metadata.table", "release_customer");

        EnterpriseMetadataRequestAdapter adapter = liveRequested()
            ? liveContext().getBean(EnterpriseMetadataRequestAdapter.class)
            : new EnterpriseMetadataRequestAdapter(activeSearchService(assetName, database, table));
        Map<String, Object> adapted = adapter.adapt(Map.of(
            "query", "Review the complete indexed table schema " + table,
            "requestId", "live-enterprise-metadata-adapter",
            "defaultDataAsset", Map.of("assetName", assetName)
        ));

        assertThat(adapted)
            .containsEntry("purpose", "EXISTING_TABLE_METADATA_ALIGNMENT")
            .containsEntry("matchMode", "FIELD_MAPPING");
        assertThat((List<Map<String, Object>>) adapted.get("fields")).isNotEmpty();
        assertThat((Map<String, Object>) adapted.get("schemaEvidence"))
            .containsEntry("mode", "INTERNAL_SQL_METADATA_LOOKUP")
            .containsEntry("tableName", table);
        assertThat((Map<String, Object>) adapted.get("targetObject"))
            .containsEntry("assetName", assetName)
            .containsEntry("tableName", table)
            .doesNotContainKey("domain");
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required live test property %s", name).isNotBlank();
        return value.trim();
    }

    private String propertyOrFixture(String name, String fixture) {
        return liveRequested() ? requiredProperty(name) : fixture;
    }

    private boolean liveRequested() {
        return Boolean.parseBoolean(System.getProperty("chatchat.live.metadata.test", "false"));
    }

    private SqlMetadataSearchService activeSearchService(String assetName, String database, String tableName) {
        if (liveRequested()) {
            return liveContext().getBean(SqlMetadataSearchService.class);
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("release-metadata-datasource");
        datasource.setName(assetName);
        datasource.setTitle(assetName);
        datasource.setToolName("release_metadata_query");
        datasource.setEnvironment("TEST");
        datasource.setDatabaseType("h2");

        TableLocation table = new TableLocation(
            datasource.getId(), database, database, tableName, "BASE TABLE", 1L,
            "Release customer metadata", "Release catalog", 1.0d);
        List<MetadataColumn> columns = List.of(
            new MetadataColumn(datasource.getId(), database, database, tableName,
                "customer_id", "varchar", "varchar(64)", "PRI", "Customer identifier", false, 1),
            new MetadataColumn(datasource.getId(), database, database, tableName,
                "customer_name", "varchar", "varchar(128)", "", "Customer name", true, 2)
        );
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        MetadataIndexService metadataIndex = mock(MetadataIndexService.class);
        when(metadataIndex.allTables(datasource)).thenReturn(List.of(table));
        when(metadataIndex.columns(datasource, table)).thenReturn(columns);
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(false);
        return new SqlMetadataSearchService(lucene, datasourceService, metadataIndex);
    }

    private ConfigurableApplicationContext liveContext() {
        if (liveContext == null) {
            liveContext = new SpringApplicationBuilder(ChatChatMcpServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                    "spring.main.web-application-type=none",
                    "spring.task.scheduling.enabled=false"
                )
                .run();
        }
        return liveContext;
    }
}
