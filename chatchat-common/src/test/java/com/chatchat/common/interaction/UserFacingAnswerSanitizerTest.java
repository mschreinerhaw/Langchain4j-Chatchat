package com.chatchat.common.interaction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingAnswerSanitizerTest {

    @Test
    void removesReconciliationIndexInternalIdsAndInlineAliases() {
        String answer = """
            证据索引
            SQL-1 =`9001fee4-482b-4851-9eb9-df269765291f:acde92df-f1bb-45a2-91ac-88fd10504f15:mcp_chatchat_mcp_server_sql_schema_context_query#chunk-1`\\
            META-1 =`9001fee4-482b-4851-9eb9-df269765291f:acde92df-f1bb-45a2-91ac-88fd10504f15:mcp_chatchat_mcp_server_enterprise_metadata_search#chunk-1`\\
            STEP-1 =`iteration:1:step:1:tool:mcp_chatchat_mcp_server_sql_schema_context_query`

            ## 限制与待确认事项

            - schema 检索存在截断，因此需要业务确认 [STEP-1]。
            - 当前仅返回五张候选表 [SQL-1] [SQL-2]，标准字段仍待确认 [META-1]。
            """;

        assertThat(UserFacingAnswerSanitizer.sanitize(answer))
            .isEqualTo("""
                ## 限制与待确认事项

                - schema 检索存在截断，因此需要业务确认。
                - 当前仅返回五张候选表，标准字段仍待确认。
                """.trim());
    }

    @Test
    void removesMappingsWhenIndexAndEntriesShareOneLine() {
        String answer = "证据索引 SQL-1 =`9001fee4-482b-4851-9eb9-df269765291f:"
            + "acde92df-f1bb-45a2-91ac-88fd10504f15:mcp_server_sql_schema_context_query#chunk-1`\\ "
            + "STEP-1 =`iteration:1:step:1:tool:mcp_server_sql_schema_context_query`\n\n"
            + "可使用召回结果设计表结构 [SQL-1]。";

        assertThat(UserFacingAnswerSanitizer.sanitize(answer))
            .isEqualTo("可使用召回结果设计表结构。");
    }

    @Test
    void preservesSqlAndUnrelatedBusinessReferences() {
        String answer = "执行 `SELECT * FROM holdings`，流程进入 step-1，规范参见 [ISO-1]。";

        assertThat(UserFacingAnswerSanitizer.sanitize(answer)).isEqualTo(answer);
    }
}
