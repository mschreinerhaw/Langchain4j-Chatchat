package com.chatchat.mcpserver.metadata.search;

import com.chatchat.mcpserver.sql.metadata.SqlMetadataSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseMetadataRequestAdapterTest {

    private final SqlMetadataSearchService sqlMetadataSearchService =
        mock(SqlMetadataSearchService.class);
    private final EnterpriseMetadataRequestAdapter adapter =
        new EnterpriseMetadataRequestAdapter(sqlMetadataSearchService);

    @Test
    @SuppressWarnings("unchecked")
    void normalizesModelGeneratedCreateTableDraftInsideCapabilityBoundary() {
        Map<String, Object> adapted = adapter.adapt(Map.of(
            "tableName", "customer_profile",
            "types", List.of("metadata_field"),
            "fields", List.of(
                Map.of(
                    "name", "customer_name",
                    "cnName", "客户姓名",
                    "type", "varchar(100)",
                    "isNullable", false,
                    "comment", "客户姓名"
                ),
                Map.of(
                    "columnName", "customer_status",
                    "chineseName", "客户状态",
                    "columnType", "varchar(8)",
                    "businessDomain", "客户"
                )
            )
        ));

        assertThat(adapted)
            .containsEntry("purpose", "CREATE_TABLE_FIELD_MAPPING")
            .containsEntry("matchMode", "FIELD_MAPPING")
            .doesNotContainKeys("types", "tableName", "sourceEvidence");
        assertThat(String.valueOf(adapted.get("query")))
            .contains("customer_profile", "customer_name", "客户姓名",
                "customer_status", "客户状态");
        assertThat((Map<String, Object>) adapted.get("targetObject"))
            .containsEntry("type", "TABLE")
            .containsEntry("name", "customer_profile");
        List<Map<String, Object>> fields =
            (List<Map<String, Object>>) adapted.get("fields");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0))
            .containsEntry("fieldName", "customer_name")
            .containsEntry("fieldCnName", "客户姓名")
            .containsEntry("dataType", "varchar(100)")
            .containsEntry("nullable", false);
        assertThat(fields.get(1))
            .containsEntry("fieldName", "customer_status")
            .containsEntry("fieldCnName", "客户状态")
            .containsEntry("dataType", "varchar(8)")
            .containsEntry("domain", "客户");
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsSqlMetadataOnlyInsideCapabilityBoundary() {
        Map<String, Object> adapted = adapter.adapt(Map.of(
            "query", "table review",
            "sourceEvidence", List.of(Map.of(
                "stepId", 1,
                "toolName", "any_upstream_capability",
                "output", Map.of(
                    "topTables", List.of(Map.of(
                        "asset", Map.of("name", "TDH数据仓库"),
                        "location", Map.of(
                            "database", "gdp_ads",
                            "table", "ads_ids_clr_acc_liab_d_i"
                        ),
                        "columns", List.of(Map.of(
                            "name", "client_id",
                            "comment", "客户编号",
                            "columnType", "varchar(64)",
                            "nullable", false
                        ))
                    ))
                )
            ))
        ));

        assertThat(adapted)
            .containsEntry("purpose", "EXISTING_TABLE_METADATA_ALIGNMENT")
            .containsEntry("matchMode", "FIELD_MAPPING")
            .doesNotContainKey("sourceEvidence");
        assertThat((Map<String, Object>) adapted.get("targetObject"))
            .containsEntry("name", "gdp_ads.ads_ids_clr_acc_liab_d_i")
            .containsEntry("assetName", "TDH数据仓库")
            .containsEntry("database", "gdp_ads")
            .containsEntry("tableName", "ads_ids_clr_acc_liab_d_i")
            .doesNotContainKey("domain");
        assertThat((Map<String, Object>) adapted.get("schemaEvidence"))
            .containsEntry("mode", "DECLARED_DEPENDENCY_EVIDENCE")
            .containsEntry("fieldCount", 1);
        List<Map<String, Object>> fields =
            (List<Map<String, Object>>) adapted.get("fields");
        assertThat(fields).singleElement().satisfies(field ->
            assertThat(field)
                .containsEntry("fieldName", "client_id")
                .containsEntry("fieldCnName", "客户编号")
                .containsEntry("dataType", "varchar(64)")
                .containsEntry("nullable", false)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesCompleteTableSchemaInternallyWhenRuntimeHasNoDependencyEvidence() {
        when(sqlMetadataSearchService.search(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
            "topTables", List.of(Map.of(
                "asset", Map.of("name", "TDH数据仓库"),
                "location", Map.of(
                    "database", "gdp_ads",
                    "tableName", "ads_ids_clr_acc_liab_d_i"
                ),
                "columns", List.of(
                    Map.of(
                        "name", "client_id",
                        "comment", "客户编号",
                        "columnType", "varchar(64)",
                        "nullable", false
                    ),
                    Map.of(
                        "name", "liability_amount",
                        "comment", "负债金额",
                        "columnType", "decimal(18,2)",
                        "nullable", true
                    )
                )
            ))
        ));

        Map<String, Object> adapted = adapter.adapt(Map.of(
            "query", "对比标准字段为我补全这张表的字段注释ads_ids_clr_acc_liab_d_i并说明理由",
            "requestId", "request-1",
            "defaultDataAsset", Map.of(
                "assetName", "TDH数据仓库",
                "assetType", "DATABASE"
            )
        ));

        assertThat(adapted)
            .containsEntry("purpose", "EXISTING_TABLE_METADATA_ALIGNMENT")
            .containsEntry("matchMode", "FIELD_MAPPING");
        assertThat((Map<String, Object>) adapted.get("targetObject"))
            .containsEntry("name", "gdp_ads.ads_ids_clr_acc_liab_d_i")
            .containsEntry("assetName", "TDH数据仓库")
            .containsEntry("database", "gdp_ads")
            .containsEntry("tableName", "ads_ids_clr_acc_liab_d_i")
            .doesNotContainKey("domain");
        assertThat((Map<String, Object>) adapted.get("schemaEvidence"))
            .containsEntry("mode", "INTERNAL_SQL_METADATA_LOOKUP")
            .containsEntry("fieldCount", 2);
        assertThat((List<Map<String, Object>>) adapted.get("fields"))
            .extracting(field -> field.get("fieldName"))
            .containsExactly("client_id", "liability_amount");
        verify(sqlMetadataSearchService).search(argThat(arguments ->
            "ads_ids_clr_acc_liab_d_i".equals(arguments.get("tableName"))
                && Boolean.TRUE.equals(arguments.get("includeColumns"))
                && arguments.get("defaultDataAsset") instanceof Map<?, ?>
        ));
    }
}
