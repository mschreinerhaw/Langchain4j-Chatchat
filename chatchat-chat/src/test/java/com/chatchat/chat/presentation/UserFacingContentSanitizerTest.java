package com.chatchat.chat.presentation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingContentSanitizerTest {

    @Test
    void removesReconciliationIndexFromArtifactMarkdown() {
        String markdown = """
            # 证券持仓信息表设计方案（证据校准版）

            本方案为人工设计草案。

            ## 证据索引

            SQL-1 = `9001fee4-482b-4851-9eb9-df269765291f:acde92df-f1bb-45a2-91ac-88fd10504f15:mcp_chatchat_mcp_server_sql_schema_context_query#chunk-1`
            META-1 = `9001fee4-482b-4851-9eb9-df269765291f:acde92df-f1bb-45a2-91ac-88fd10504f15:mcp_chatchat_mcp_server_enterprise_metadata_search#chunk-1`
            STEP-1 = `iteration:1:step:1:tool:mcp_chatchat_mcp_server_sql_schema_context_query`
            """;

        assertThat(UserFacingContentSanitizer.removeInternalEvidenceMarkers(markdown))
            .isEqualTo("""
                # 证券持仓信息表设计方案（证据校准版）

                本方案为人工设计草案。
                """.trim());
    }

    @Test
    void sanitizesEveryUserFacingTextFieldInUiResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", "结论 [SQL-1]。");
        response.put("reportHtml", "报告 [META-1]。");
        response.put("debug", Map.of("evidenceId", "keep-for-audit"));

        assertThat(UserFacingContentSanitizer.sanitizeUiResponse(response))
            .containsEntry("answer", "结论。")
            .containsEntry("reportHtml", "报告。")
            .containsEntry("debug", Map.of("evidenceId", "keep-for-audit"));
    }
}
