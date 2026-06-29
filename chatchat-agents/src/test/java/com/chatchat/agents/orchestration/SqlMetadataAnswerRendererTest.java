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
