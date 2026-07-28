package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.ChatChatMcpServerApplication;
import com.chatchat.mcpserver.metadata.EnterpriseMetadataRequestAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in business regression for a maintained datasource.
 *
 * <p>The target identifiers are supplied by the test invocation so this test
 * remains reusable and does not encode a table, tenant, or environment in the
 * runtime implementation.</p>
 */
@SpringBootTest(
    classes = ChatChatMcpServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "spring.task.scheduling.enabled=false"
    }
)
@EnabledIfSystemProperty(named = "chatchat.live.metadata.test", matches = "true")
class SqlMetadataLiveRegressionTest {

    @Autowired
    private SqlMetadataSearchService searchService;

    @Autowired
    private EnterpriseMetadataRequestAdapter enterpriseMetadataRequestAdapter;

    @Test
    @SuppressWarnings("unchecked")
    void exactMaintainedTableReturnsItsCompleteFieldMetadata() {
        String assetName = requiredProperty("chatchat.live.metadata.asset-name");
        String database = requiredProperty("chatchat.live.metadata.database");
        String table = requiredProperty("chatchat.live.metadata.table");

        Map<String, Object> result = searchService.search(Map.of(
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
        String assetName = requiredProperty("chatchat.live.metadata.asset-name");
        String table = requiredProperty("chatchat.live.metadata.table");

        Map<String, Object> adapted = enterpriseMetadataRequestAdapter.adapt(Map.of(
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
}
