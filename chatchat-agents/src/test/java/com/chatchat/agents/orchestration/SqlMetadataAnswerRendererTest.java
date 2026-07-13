package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMetadataAnswerRendererTest {

    @Test
    void rendersSqlMetadataSearchColumnsAsStructuredMarkdownTable() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed",
            true,
            false,
            null,
            null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                2,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_metadata_search",
                true,
                sqlMetadataSearchOutput(),
                null,
                null,
                null,
                23
            )),
            Map.of(),
            80
        );

        String markdown = renderer.render(result);
        SqlMetadataAnswerRenderer.RenderedSqlMetadata evidence = renderer.renderEvidence(result);

        assertThat(markdown)
            .contains("## 实际检索到的数据库对象")
            .contains("`livebos.os_historystep`")
            .contains("已返回物理表：`1`")
            .contains("`ID`")
            .contains("bigint")
            .contains("涓婚敭ID")
            .contains("`ENTRY_ID`")
            .contains("流程关联ID")
            .contains("`STATUS`")
            .contains("状态");
        assertThat(evidence.metadata())
            .containsEntry("source", "mcp_sql_metadata_search_catalog")
            .containsEntry("authoritativeOnly", true)
            .containsEntry("semanticGatePassed", true)
            .containsEntry("columnCount", 3)
            .containsEntry("catalogReturnedCount", 1);
    }

    @Test
    void rendersOnlyRetrievedCatalogAndDoesNotAddInferredWarehouseLayers() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        Map<String, Object> output = Map.of(
            "totalMatched", 2,
            "catalogReturnedCount", 2,
            "catalogTruncated", false,
            "tableCatalog", List.of(
                Map.of("database", "finance", "schema", "public", "tableName", "customer_return_fact", "tableType", "BASE TABLE"),
                Map.of("database", "finance", "schema", "public", "tableName", "market_index_quote", "tableType", "BASE TABLE")
            ),
            "topTables", List.of()
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "sql_metadata_search", true, output, null, null, null, 12
            )),
            Map.of(), 12
        );

        String markdown = renderer.render(result);

        assertThat(markdown)
            .contains("`customer_return_fact`")
            .contains("`market_index_quote`")
            .contains("工具未返回上述匹配表的字段明细")
            .doesNotContain("ADS", "DWS", "DWD", "DIM", "可能表名示例", "补充推荐");
    }

    @Test
    void reportsNoVerifiedPhysicalTablesInsteadOfInventingExamples() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "sql_metadata_search", true,
                Map.of("totalMatched", 0, "results", List.of()), null, null, null, 8
            )),
            Map.of(), 8
        );

        assertThat(renderer.render(result))
            .contains("未检索到可验证的物理表")
            .contains("不会提供推测的分层、表名示例或常见表推荐")
            .doesNotContain("ads_", "dws_", "dwd_");
    }

    @Test
    void rendersSqlColumnMetadataAsStructuredMarkdownTable() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed",
            true,
            false,
            null,
            null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                3,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_query_execute",
                true,
                sqlMetadataOutput(),
                null,
                null,
                null,
                173
            )),
            Map.of(),
            200
        );

        String markdown = renderer.render(result);
        SqlMetadataAnswerRenderer.RenderedSqlMetadata evidence = renderer.renderEvidence(result);

        assertThat(markdown)
            .contains("## 元数据依据")
            .contains("`rdsm_ad.t_ad_dict_entr_supn`")
            .contains("`TABLE_ROWS = 0`")
            .contains("## 字段结构")
            .contains("| # | 字段名 | 类型 | 可空 | 默认值 | 键 | 额外信息 | 注释 |")
            .contains("`DICT_ENTR_CODE`")
            .contains("字典条目代码")
            .contains("`SSYS_CODE`")
            .contains("来源系统代码")
            .contains("## 基于结构的统计建议");
        assertThat(evidence.metadata())
            .containsEntry("schemaVersion", SqlMetadataAnswerRenderer.FACT_SCHEMA_VERSION)
            .containsEntry("semanticGatePassed", true)
            .containsEntry("columnCount", 8)
            .containsEntry("schema", "rdsm_ad")
            .containsEntry("table", "t_ad_dict_entr_supn");
    }

    @Test
    void rendersMetadataRowsEvenWhenStepWasRejectedByModelReview() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "failed",
            false,
            false,
            "Tool result rejected by model review: missing indexes",
            null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                3,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_query_execute",
                false,
                sqlMetadataOutput(),
                "Tool result rejected by model review: missing indexes",
                null,
                null,
                173,
                Map.of("toolResultReviewSatisfied", false)
            )),
            Map.of(),
            200
        );

        String markdown = renderer.render(result);

        assertThat(markdown)
            .contains("## 元数据依据")
            .contains("- 结构明细：已从 MCP 结构化输出 `rows` 获取")
            .contains("| 1 | `DICT_ENTR_CODE` | `varchar(8)` | YES")
            .contains("| 8 | `SSYS_CODE` | `varchar(8)` | YES");
        assertThat(renderer.renderEvidence(result).metadata())
            .containsEntry("semanticGatePassed", false)
            .containsEntry("semanticGateReason", "semantic_gate_failed:toolSucceeded,noExecutionFailure");
    }

    @Test
    void rendersFlatSqlMetadataPayload() {
        SqlMetadataAnswerRenderer renderer = new SqlMetadataAnswerRenderer();
        Map<String, Object> output = flatSqlMetadataOutput();
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed",
            true,
            false,
            null,
            null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                3,
                "mcp_tool",
                "sql_query_execute",
                true,
                output,
                null,
                null,
                null,
                173
            )),
            Map.of(),
            200
        );

        String markdown = renderer.render(result);

        assertThat(markdown)
            .contains("`rdsm_ad.t_ad_dict_entr_supn`")
            .contains("- 字段数：`8`")
            .contains("`SSYS_DICT_VAL`")
            .contains("源系统字典值");
    }

    private Map<String, Object> sqlMetadataOutput() {
        return Map.of(
            "kind", "sql_query",
            "target", Map.of(
                "name", "248测试数据库",
                "toolName", "db_query_mysql_248_test_db",
                "environment", "DEV"
            ),
            "operation", Map.of(
                "statement", "SELECT column_name, column_type FROM information_schema.columns WHERE table_schema = 'rdsm_ad'",
                "diagnostics", Map.of(
                    "schemaName", "rdsm_ad",
                    "tableName", "t_ad_dict_entr_supn",
                    "tableResolution", Map.of(
                        "selectedSchema", "rdsm_ad",
                        "selectedTable", "t_ad_dict_entr_supn",
                        "candidates", List.of(Map.of(
                            "schema", "rdsm_ad",
                            "table", "t_ad_dict_entr_supn",
                            "tableType", "BASE TABLE",
                            "tableRows", 0
                        ))
                    )
                )
            ),
            "data", Map.of(
                "columns", List.of("COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE", "COLUMN_DEFAULT", "COLUMN_KEY", "EXTRA", "COLUMN_COMMENT"),
                "rowCount", 8,
                "rows", List.of(
                    column("DICT_ENTR_CODE", "varchar(8)", "YES", "", "", "字典条目代码"),
                    column("BUSI_DATE", "char(8)", "YES", "", "", "业务日期"),
                    column("DEAL_TIME", "char(19)", "YES", "", "", "处理时间"),
                    column("DICT_VAL", "varchar(8)", "YES", "", "", "字典取值"),
                    column("MEMO", "varchar(512)", "YES", "", "", "备注"),
                    column("SSYS_DICT_ENTR_CODE", "varchar(512)", "YES", "", "", "源系统字典条目代码"),
                    column("SSYS_DICT_VAL", "varchar(512)", "YES", "", "", "源系统字典值"),
                    column("SSYS_CODE", "varchar(8)", "YES", "", "", "来源系统代码")
                )
            )
        );
    }

    private Map<String, Object> flatSqlMetadataOutput() {
        return Map.of(
            "columns", List.of("COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE", "COLUMN_DEFAULT", "COLUMN_KEY", "EXTRA", "COLUMN_COMMENT"),
            "rowCount", 8,
            "rows", List.of(
                column("DICT_ENTR_CODE", "varchar(8)", "YES", "", "", "字典条目代码"),
                column("BUSI_DATE", "char(8)", "YES", "", "", "业务日期"),
                column("DEAL_TIME", "char(19)", "YES", "", "", "处理时间"),
                column("DICT_VAL", "varchar(8)", "YES", "", "", "字典取值"),
                column("MEMO", "varchar(512)", "YES", "", "", "备注"),
                column("SSYS_DICT_ENTR_CODE", "varchar(512)", "YES", "", "", "源系统字典条目代码"),
                column("SSYS_DICT_VAL", "varchar(512)", "YES", "", "", "源系统字典值"),
                column("SSYS_CODE", "varchar(8)", "YES", "", "", "来源系统代码")
            ),
            "diagnostics", Map.of(
                "executionContext", Map.of(
                    "schemaName", "rdsm_ad",
                    "tableName", "t_ad_dict_entr_supn"
                ),
                "tableResolution", Map.of(
                    "selectedSchema", "rdsm_ad",
                    "selectedTable", "t_ad_dict_entr_supn",
                    "candidates", List.of(Map.of(
                        "schema", "rdsm_ad",
                        "table", "t_ad_dict_entr_supn",
                        "tableType", "BASE TABLE",
                        "tableRows", 0
                    ))
                ),
                "routedTarget", Map.of(
                    "datasourceName", "248测试数据库",
                    "toolName", "db_query_mysql_248_test_db"
                )
            )
        );
    }

    private Map<String, Object> sqlMetadataSearchOutput() {
        return Map.of(
            "schemaVersion", "sql_metadata_search_result.v1",
            "success", true,
            "count", 1,
            "results", List.of(Map.of(
                "asset", Map.of(
                    "name", "248测试数据库",
                    "toolName", "db_query_mysql_248_test_db"
                ),
                "location", Map.of(
                    "database", "livebos",
                    "schema", "livebos",
                    "table", "os_historystep",
                    "tableName", "os_historystep",
                    "tableType", "BASE TABLE",
                    "tableRows", 0,
                    "fullPath", "livebos.os_historystep"
                ),
                "routingContext", Map.of(
                    "assetName", "248测试数据库",
                    "env", "DEV",
                    "databaseType", "mysql"
                ),
                "sqlExecutionBinding", Map.of(
                    "tool", "sql_query_execute",
                    "executionContext", Map.of(
                        "assetName", "248测试数据库",
                        "env", "DEV",
                        "databaseType", "mysql"
                    ),
                    "parameters", Map.of(
                        "databaseName", "livebos",
                        "schemaName", "livebos",
                        "tableName", "os_historystep"
                    )
                ),
                "columnCount", 3,
                "columns", List.of(
                    metadataSearchColumn("ID", "bigint", "bigint(20)", "PRI", "涓婚敭ID", false),
                    metadataSearchColumn("ENTRY_ID", "varchar", "varchar(64)", "MUL", "流程关联ID", true),
                    metadataSearchColumn("STATUS", "varchar", "varchar(32)", "", "状态", true)
                )
            ))
        );
    }

    private Map<String, Object> metadataSearchColumn(String name,
                                                     String dataType,
                                                     String columnType,
                                                     String columnKey,
                                                     String comment,
                                                     boolean nullable) {
        return Map.of(
            "name", name,
            "dataType", dataType,
            "columnType", columnType,
            "columnKey", columnKey,
            "comment", comment,
            "nullable", nullable
        );
    }

    private Map<String, Object> column(String name, String type, String nullable, String key, String extra, String comment) {
        return Map.of(
            "COLUMN_NAME", name,
            "COLUMN_TYPE", type,
            "IS_NULLABLE", nullable,
            "COLUMN_KEY", key,
            "EXTRA", extra,
            "COLUMN_COMMENT", comment
        );
    }
}
