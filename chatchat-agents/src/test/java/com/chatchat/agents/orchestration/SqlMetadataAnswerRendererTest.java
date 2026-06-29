package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMetadataAnswerRendererTest {

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
