package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolArgumentResolverTest {

    private final AgentToolArgumentResolver resolver = new AgentToolArgumentResolver(new AgentToolNameResolver(), 5);

    @Test
    void documentSearchUsesOpenRecallByDefaultAndPreservesOriginalQuery() {
        Map<String, Object> arguments = Map.of(
            "query", "跨交易日 任务依赖 执行判断 调度方案",
            "document_ids", List.of("20260617_c489d851")
        );

        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_document_search",
            arguments,
            List.of("20260617_c489d851"),
            List.of(),
            "跨交易日任务依赖执行判断与调度方案 说的是什么?",
            5
        );

        assertThat(result)
            .doesNotContainKey("document_ids")
            .doesNotContainKey("documentIds")
            .doesNotContainKey("fileIds")
            .doesNotContainKey("file_ids");
        assertThat(result.get("query").toString())
            .contains("跨交易日任务依赖执行判断与调度方案")
            .contains("跨交易日 任务依赖 执行判断 调度方案");
    }

    @Test
    void documentSearchInjectsBoundDocumentIdsOnlyForStrictScope() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "document_search",
            Map.of("query", "跨交易日任务依赖执行判断与调度方案", "strict_document_scope", true),
            List.of("20260617_c489d851"),
            List.of(),
            "跨交易日任务依赖执行判断与调度方案",
            5
        );

        assertThat(result)
            .containsEntry("document_ids", List.of("20260617_c489d851"))
            .containsEntry("strict_document_scope", true);
    }
}
