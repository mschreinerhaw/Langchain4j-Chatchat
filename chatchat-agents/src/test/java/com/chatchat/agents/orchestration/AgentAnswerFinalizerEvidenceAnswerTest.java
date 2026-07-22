package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.interaction.InteractionToolTrace;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerFinalizerEvidenceAnswerTest {

    @Test
    @SuppressWarnings("unchecked")
    void appendsToolResultRowsAndVisualizationSpecForTabularData() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_sql_query_execute")
            .displayName("SQL 查询执行")
            .success(true)
            .output("""
                {
                  "columns": ["etl_date", "pd_code", "pd_name", "last_pric"],
                  "rowCount": 2,
                  "rows": [
                    {"etl_date": "2026-06-01", "pd_code": "000001", "pd_name": "示例标的A", "last_pric": 10.25},
                    {"etl_date": "2026-06-01", "pd_code": "000002", "pd_name": "示例标的B", "last_pric": 20.50}
                  ]
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "## 核心结论\n\n已查询到行情波动异常提醒数据。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("SQL query returned rows.")
        );

        assertThat(result.answer())
            .contains("## 查询结果明细")
            .contains("\u8bc1\u636e\u72b6\u6001\uff1a\u6709\u4e8b\u5b9e\u4f9d\u636e\u7684\u5206\u6790")
            .contains("pd_code")
            .contains("示例标的A");
        assertThat(result.metadata())
            .containsEntry("answerEvidenceStatus", "GROUNDED_ANALYSIS")
            .containsEntry("answerEvidenceLabel", "\u6709\u4e8b\u5b9e\u4f9d\u636e\u7684\u5206\u6790");
        Map<String, Object> visualizationSpec = (Map<String, Object>) result.metadata().get("visualizationSpec");
        assertThat(visualizationSpec)
            .containsEntry("type", "panel")
            .containsEntry("sourceTool", "mcp_chatchat_mcp_server_sql_query_execute");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) visualizationSpec.get("blocks");
        assertThat(blocks)
            .extracting(block -> block.get("type"))
            .containsExactly("chart", "table");
        Map<String, Object> dataset = (Map<String, Object>) visualizationSpec.get("dataset");
        assertThat((List<Map<String, Object>>) dataset.get("rows"))
            .hasSize(2)
            .extracting(row -> row.get("pd_name"))
            .containsExactly("示例标的A", "示例标的B");
    }

    @Test
    void preservesCompleteLongTextCellsOutsideMarkdownTable() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_sql_query_execute")
            .displayName("sql_query_execute")
            .success(true)
            .output("""
                {
                  "columns": ["Type", "Name", "Status"],
                  "rowCount": 1,
                  "rows": [{
                    "Type": "InnoDB",
                    "Name": "",
                    "Status": "BEGIN OF INNODB STATUS\\nBACKGROUND THREAD 012345678901234567890123456789012345678901234567890123456789\\nSEMAPHORES 012345678901234567890123456789012345678901234567890123456789\\nTRANSACTIONS 012345678901234567890123456789012345678901234567890123456789\\nBUFFER POOL AND MEMORY\\nEND OF INNODB MONITOR OUTPUT"
                  }]
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "InnoDB status returned.",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("SQL query returned one complete InnoDB status row.")
        );

        assertThat(result.answer())
            .contains("[完整内容见下方：第 1 行 / Status")
            .contains("### 长文本字段完整内容")
            .contains("BEGIN OF INNODB STATUS")
            .contains("BUFFER POOL AND MEMORY")
            .contains("END OF INNODB MONITOR OUTPUT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoBuildsDistributionChartForCategoricalToolRows() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_sql_query_execute")
            .displayName("SQL 查询执行")
            .success(true)
            .output("""
                {
                  "columns": ["evt_id", "evt_type", "evt_cont"],
                  "rowCount": 4,
                  "rows": [
                    {"evt_id": "A1", "evt_type": "价格异动", "evt_cont": "事件1"},
                    {"evt_id": "A2", "evt_type": "价格异动", "evt_cont": "事件2"},
                    {"evt_id": "A3", "evt_type": "评级调整", "evt_cont": "事件3"},
                    {"evt_id": "A4", "evt_type": "分红", "evt_cont": "事件4"}
                  ]
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "已查询到事件提醒数据。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("SQL query returned categorical rows.")
        );

        Map<String, Object> visualizationSpec = (Map<String, Object>) result.metadata().get("visualizationSpec");
        assertThat(visualizationSpec).containsEntry("type", "panel");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) visualizationSpec.get("blocks");
        Map<String, Object> chartSpec = (Map<String, Object>) blocks.get(0).get("spec");
        assertThat(chartSpec)
            .containsEntry("type", "chart")
            .containsEntry("analysisType", "distribution");
        Map<String, Object> chartDataset = (Map<String, Object>) chartSpec.get("dataset");
        assertThat(chartDataset).containsEntry("xKey", "evt_type");
        assertThat((List<Map<String, Object>>) chartDataset.get("rows"))
            .extracting(row -> row.get("evt_type") + ":" + row.get("count"))
            .contains("价格异动:2", "评级调整:1", "分红:1");

        Map<String, Object> rawDataset = (Map<String, Object>) visualizationSpec.get("dataset");
        assertThat((List<Map<String, Object>>) rawDataset.get("rows")).hasSize(4);
    }

    @Test
    void labelsAnswerWithoutEvidenceAsSpeculation() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u53ef\u80fd\u662f\u6570\u636e\u5e93\u6162\u67e5\u8be2\u5bfc\u81f4\u54cd\u5e94\u5ef6\u8fdf\u3002",
            List.of(),
            new LinkedHashMap<>(),
            List.of()
        );

        assertThat(result.answer())
            .contains("\u8bc1\u636e\u72b6\u6001\uff1a\u8bc1\u636e\u4e0d\u8db3")
            .contains("\u63a8\u6d4b")
            .contains("\u53ef\u80fd\u662f\u6570\u636e\u5e93\u6162\u67e5\u8be2");
        assertThat(result.metadata())
            .containsEntry("answerEvidenceStatus", "EVIDENCE_INSUFFICIENT")
            .containsEntry("answerEvidenceLabel", "\u8bc1\u636e\u4e0d\u8db3/\u63a8\u6d4b");
    }

    @Test
    void reviewerTimeoutDoesNotBlockFinalAnswer() {
        String previous = System.getProperty("chatchat.agent.answer.review.timeout.ms");
        System.setProperty("chatchat.agent.answer.review.timeout.ms", "50");
        try {
            AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) -> {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new AgentAnswerReview(AgentAnswerReview.REJECTED, "", "late reviewer result");
            };
            AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
                reviewer,
                new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agentRunId", "review-timeout-test");
            long startedAt = System.currentTimeMillis();

            AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
                new ChatModel() {
                    @Override
                    public String chat(String message) {
                        return "{\"accepted\":true,\"feedback\":\"ok\",\"revisedAnswer\":\"\"}";
                    }
                },
                "分析行情数据",
                "",
                List.of(),
                metadata,
                List.of("SQL query returned 30 rows."),
                "## 已完成分析\n\n已基于查询结果生成总结。",
                () -> false,
                "completed"
            );

            assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000L);
            assertThat(result.answer()).contains("已基于查询结果生成总结");
            assertThat(result.metadata())
                .containsEntry("answerReviewTimedOut", true)
                .containsEntry("answerReviewFallback", "accepted_current_answer");
        } finally {
            if (previous == null) {
                System.clearProperty("chatchat.agent.answer.review.timeout.ms");
            } else {
                System.setProperty("chatchat.agent.answer.review.timeout.ms", previous);
            }
        }
    }

    @Test
    void reviewerTimeoutUsesConfiguredModelTimeout() {
        ModelsConfig modelsConfig = new ModelsConfig();
        modelsConfig.getOpenai().setTimeout(1);
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new AgentAnswerReview(AgentAnswerReview.REJECTED, "", "late reviewer result");
        };
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt"),
            modelsConfig
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentRunId", "configured-review-timeout-test");
        long startedAt = System.currentTimeMillis();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            new ChatModel() {
                @Override
                public String chat(String message) {
                    return "{\"accepted\":true,\"feedback\":\"ok\",\"revisedAnswer\":\"\"}";
                }
            },
            "分析行情数据",
            "",
            List.of(),
            metadata,
            List.of("SQL query returned 30 rows."),
            "## 已完成分析\n\n已基于查询结果生成总结。",
            () -> false,
            "completed"
        );

        assertThat(System.currentTimeMillis() - startedAt).isLessThan(3_000L);
        assertThat(result.answer()).contains("已基于查询结果生成总结");
        assertThat(result.metadata())
            .containsEntry("answerReviewTimedOut", true)
            .containsEntry("answerReviewTimeoutMs", 1_000L);
    }

    @Test
    void preservesFinalAnswerWhenCancellationArrivesAfterAnswerWasProduced() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            new ChatModel() {
                @Override
                public String chat(String message) {
                    return "{\"accepted\":true,\"feedback\":\"ok\",\"revisedAnswer\":\"\"}";
                }
            },
            "分析行情波动异常提醒数据",
            "",
            List.of(),
            metadata,
            List.of("SQL query returned 30 rows."),
            "## 行情波动异常提醒数据分析\n\n已基于工具结果形成分析结论。",
            () -> {
                throw new CancellationException("Agent run timed out");
            },
            "completed"
        );

        assertThat(result.answer()).contains("行情波动异常提醒数据分析");
        assertThat(result.metadata())
            .containsEntry("answerCompletedAfterCancellation", true)
            .containsEntry("answerCancellationReason", "Agent run timed out");
    }

    @Test
    void labelsFailedToolAnswerAsExecutionBlocked() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_sql_query_execute")
            .success(false)
            .errorMessage("permission denied")
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u67e5\u8be2\u672a\u80fd\u5b8c\u6210\u3002",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("SQL query failed: permission denied.")
        );

        assertThat(result.answer())
            .contains("\u8bc1\u636e\u72b6\u6001\uff1a\u6267\u884c\u963b\u65ad/\u8bc1\u636e\u4e0d\u8db3")
            .contains("permission denied")
            .contains("\u6392\u67e5\u53c2\u8003")
            .contains("\u4e0d\u80fd\u4f5c\u4e3a\u786e\u5b9a\u6027\u4e1a\u52a1\u7ed3\u8bba");
        assertThat(result.metadata())
            .containsEntry("answerEvidenceStatus", "EXECUTION_BLOCKED")
            .containsEntry("answerEvidenceLabel", "\u6267\u884c\u963b\u65ad/\u8bc1\u636e\u4e0d\u8db3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendsLinuxCommandStructuredEvidence() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_linux_command_execute")
            .displayName("Linux 命令执行")
            .success(true)
            .durationMs(35L)
            .output("""
                {
                  "kind": "linux_command",
                  "data": {
                    "exitCode": 0,
                    "commandSuccess": true,
                    "stdout": "load average: 0.12, 0.20, 0.18",
                    "stderr": "",
                    "steps": [{"stepIndex": 1, "exitCode": 0, "success": true}]
                  }
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "系统状态正常。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("Linux command returned structured stdout.")
        );

        assertThat(result.answer())
            .contains("## 工具执行证据")
            .contains("linux_command")
            .contains("退出码=0")
            .contains("命令执行完成")
            .doesNotContain("load average");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.metadata().get("toolResultEvidence");
        assertThat(evidence)
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("evidenceType")).isEqualTo("linux_command");
                assertThat(item.get("exitCode")).isEqualTo(0);
                assertThat(item.get("stdoutPreview")).asString().contains("load average");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendsHttpResponseStructuredEvidence() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_http_request_execute")
            .displayName("HTTP 请求执行")
            .success(true)
            .durationMs(48L)
            .output("""
                {
                  "kind": "http_request",
                  "data": {
                    "statusCode": 200,
                    "body": {"alertCount": 3, "state": "ok"},
                    "rawBody": "{\\"alertCount\\":3,\\"state\\":\\"ok\\"}"
                  }
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "接口返回告警数量为 3。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("HTTP response returned structured body.")
        );

        assertThat(result.answer())
            .contains("## 工具执行证据")
            .contains("http_response")
            .contains("HTTP 状态=200")
            .contains("HTTP 调用完成")
            .doesNotContain("alertCount");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.metadata().get("toolResultEvidence");
        assertThat(evidence)
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("evidenceType")).isEqualTo("http_response");
                assertThat(item.get("statusCode")).isEqualTo(200);
                assertThat(item.get("bodyPreview")).asString().contains("alertCount");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersJsonToolEvidenceAsCompactProofInsteadOfFullPayload() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_business_query_template_search")
            .displayName("business_query_template_search")
            .success(true)
            .durationMs(394L)
            .output("""
                {
                  "schemaVersion": "template_query_result.v1",
                  "querySchemaVersion": "template_query.v1",
                  "success": true,
                  "view": "model",
                  "targetKind": "business_database_query",
                  "assetType": "database_query",
                  "filtersSchemaVersion": "target_filters.v1",
                  "filters": {
                    "intent": "分析行情数据发生较大波动时异常提醒数据",
                    "bilingualIntent": ["行情波动异常提醒", "market volatility anomaly alert"],
                    "keywords": ["行情波动", "异常提醒", "波动率"]
                  },
                  "associatedTemplates": [
                    {"templateId": "query_edayQuqtMoni", "name": "edayQuqtMoni"}
                  ]
                }
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "已找到可执行查询模板。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("Template search returned structured JSON.")
        );

        assertThat(result.answer())
            .contains("## 工具执行证据")
            .contains("mcp_chatchat_mcp_server_business_query_template_search")
            .contains("证据类型 `json`")
            .contains("输出键=schemaVersion, querySchemaVersion")
            .contains("已返回结构化 JSON")
            .doesNotContain("bilingualIntent")
            .doesNotContain("market volatility anomaly alert")
            .doesNotContain("associatedTemplates");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.metadata().get("toolResultEvidence");
        assertThat(evidence)
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("evidenceType")).isEqualTo("json");
                assertThat(item.get("outputPreview")).asString().contains("associatedTemplates");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachesUnifiedEvidenceAnswerMetadata() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "根据证据，配置变更后需要重启服务 doc://file-1#chunk=3。",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://file-1#chunk=3
                content:
                restart service after config change
                """)
        );

        assertThat(result.metadata())
            .containsEntry("answerContractVersion", "evidence_answer_v1")
            .containsEntry("groundingStatus", "grounded")
            .containsEntry("answerAssemblyMode", "FULL");
        Map<String, Object> evidenceAnswer = (Map<String, Object>) result.metadata().get("evidenceAnswer");
        assertThat(evidenceAnswer)
            .containsEntry("confidence", "high");
        assertThat((List<Map<String, Object>>) evidenceAnswer.get("citations"))
            .extracting(item -> item.get("refId"))
            .containsExactly("doc://file-1#chunk=3");
        Map<String, Object> assemblyPolicy = (Map<String, Object>) result.metadata().get("answerAssemblyPolicy");
        assertThat(assemblyPolicy)
            .containsEntry("contractVersion", "answer_assembly_policy_v1")
            .containsEntry("mode", "FULL");
    }

    @Test
    void replacesNoMatchFallbackWhenDocumentEvidenceExists() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u672a\u80fd\u5728\u5f53\u524d\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230\u76f8\u5173\u6587\u6863\u6216\u8bf4\u660e\u6750\u6599\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://livedata-report#chunk=2
                source: \u57fa\u4e8elivedata\u6570\u636e\u7f16\u7ec7\u7684\u62a5\u8868\u5f00\u53d1.docx
                section: SQL\u5f00\u53d1
                content:
                \u5728livedata\u6570\u636e\u7f16\u7ec7\u6a21\u5757\u7ef4\u62a4\u6570\u636e\u6e90\uff0c\u5728\u6570\u636e\u89c6\u7a97\u4e2d\u5f00\u53d1\u62a5\u8868SQL\uff0c\u4fdd\u5b58SQL\u6570\u636e\u96c6\u540e\u8fdb\u884cBI\u62a5\u8868\u5236\u4f5c\u4e0e\u53d1\u5e03\u3002
                """)
        );

        assertThat(result.answer())
            .contains("doc://livedata-report#chunk=2")
            .contains("\u5728livedata\u6570\u636e\u7f16\u7ec7\u6a21\u5757\u7ef4\u62a4\u6570\u636e\u6e90")
            .doesNotContain("\u672a\u80fd\u5728\u5f53\u524d\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230");
        assertThat(result.metadata())
            .containsEntry("evidenceForcedAnswer", true)
            .containsEntry("answerDecisionContractVersion", AnswerDecisionEngine.CONTRACT_VERSION)
            .containsEntry("answerDecision", AnswerDecisionEngine.DOCUMENT_EVIDENCE_REWRITE)
            .containsEntry("answerRewriteSource", "evidence_guard")
            .containsEntry("groundingStatus", "grounded");
    }

    @Test
    void deterministicAnswerLockDoesNotOverrideUsableAnalysisAnswer() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "ADS \u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1\u4e3b\u8981\u68c0\u67e5\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a\u3001\u8d26\u6237\u5f02\u5e38\u660e\u7ec6\u548c\u8bc1\u5238\u7c7b\u522b\u6620\u5c04\uff0c\u53ef\u7528\u6587\u6863 SQL \u4f5c\u4e3a\u6838\u9a8c\u4f9d\u636e\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2):
                decision: ANSWER_ALLOWED
                contractHash: abc123
                graphViewHash: def456
                fromGraphOnly: true
                executable: true
                evidencePath: evidence:1:chunk -> evidence:1:sql_trusted
                sourceRefs: doc://file-1#chunk=0
                lockedAnswer:
                ---BEGIN_LOCKED_ANSWER---
                \u6839\u636e\u53ef\u6267\u884c\u8bc1\u636e\u5951\u7ea6\uff0cSQL\u4e3a\uff1aselect * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i doc://file-1#chunk=0
                ---END_LOCKED_ANSWER---
                """)
        );

        assertThat(result.answer())
            .contains("ADS")
            .contains("\u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1")
            .doesNotContain("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
        assertThat(result.metadata())
            .containsEntry("deterministicAnswerAvailable", true)
            .containsEntry("deterministicAnswerUsedAsEvidence", true)
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "none")
            .containsEntry("deterministicAnswerContractVersion", "evidence_execution_contract_v2_2")
            .containsEntry("deterministicAnswerContractHash", "abc123")
            .containsEntry("deterministicAnswerGraphViewHash", "def456");
    }

    @Test
    void deterministicAnswerLockOnlyReplacesFailureFallback() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u672a\u80fd\u83b7\u53d6SQL\u5185\u5bb9\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2):
                decision: ANSWER_ALLOWED
                contractHash: abc123
                graphViewHash: def456
                lockedAnswer:
                ---BEGIN_LOCKED_ANSWER---
                \u6839\u636e\u53ef\u6267\u884c\u8bc1\u636e\u5951\u7ea6\uff0cSQL\u4e3a\uff1aselect * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i doc://file-1#chunk=0
                ---END_LOCKED_ANSWER---
                """)
        );

        assertThat(result.answer())
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i")
            .contains("doc://file-1#chunk=0")
            .doesNotContain("\u672a\u80fd\u83b7\u53d6SQL\u5185\u5bb9");
        assertThat(result.metadata())
            .containsEntry("deterministicAnswerAvailable", true)
            .containsEntry("deterministicAnswerFallbackApplied", true)
            .containsEntry("answerDecision", AnswerDecisionEngine.DETERMINISTIC_EVIDENCE_REWRITE)
            .containsEntry("answerRewriteSource", "evidence_guard");
    }

    @Test
    void protocolAnswerIsNotReinterpretedBySecondSummaryModel() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "ads\u6570\u636e\u9a8c\u8bc1-\u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1 \u8bf4\u7684\u662f\u4ec0\u4e48\u5185\u5bb9\uff1f",
            null,
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://ads#chunk=2
                content:
                --\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a select data_date, eval_rslt from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i;
                --\u8d26\u6237\u6570\u636e\u8d28\u91cf\u62a5\u544a-\u5f02\u5e38\u5ba2\u6237 select cust_num, ast_nett_amt from gdp_ads.ads_ids_acc_data_qlty_rpt_d_i where ast_nett_amt <> 0;
                """),
            """
                {
                  "uiResponse": {
                    "contractVersion": "ui_response_v1",
                    "answer": "\u7ed3\u8bba\uff1a\u6839\u636e\u5df2\u68c0\u7d22\u6587\u6863...",
                    "citations": [{"sourceRef":"doc://ads#chunk=2","text":"system report sql"}],
                    "evidenceSummary": "\u6587\u6863\u5185\u5bb9\u5305\u542b\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a"
                  },
                  "debug": {"reasoningTrace": {}, "trustedSql": {}}
                }
                """,
            () -> false,
            "final_answer"
        );

        assertThat(result.answer())
            .contains("\u7ed3\u8bba\uff1a\u6839\u636e\u5df2\u68c0\u7d22\u6587\u6863")
            .contains("doc://ads#chunk=2")
            .doesNotContain("uiResponse")
            .doesNotContain("reasoningTrace")
            .doesNotContain("debug");
        assertThat(result.metadata())
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "none")
            .doesNotContainKey("finalMarkdownSummaryApplied")
            .doesNotContainKey("finalMarkdownSummaryReason");
    }

    @Test
    void reviewerRevisionDoesNotOverrideFinalAnswerByDefault() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "Reviewer rewritten answer that should stay diagnostic only.",
                "Reviewer suggested a rewrite"
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "question",
            null,
            List.of(),
            metadata,
            List.of("observation"),
            "Original final answer from the agent.",
            () -> false,
            "final_answer"
        );

        assertThat(result.answer()).contains("Original final answer from the agent.");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", AgentAnswerReview.REVISED)
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "none")
            .containsEntry("answerReviewRewriteSuggested", true)
            .containsEntry("answerReviewRewriteApplied", false)
            .containsEntry("answerReviewRewriteSkippedReason", "reviewer_rewrite_disabled");
    }

    @Test
    void structuredSqlMetadataAnswerSkipsReviewerRewrite() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "## 现象总结\n\n观察中未返回任何关于该表的实际数据。",
                "Reviewer could not see structured step output."
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("structuredSqlMetadataRendered", true);
        metadata.put("sqlMetadataSemanticGatePassed", true);
        metadata.put("executionGraphSemanticPassed", true);

        String structuredAnswer = """
            ## 元数据依据

            - 表定位：`rdsm_ad.t_ad_dict_entr_supn`
            - 字段数：`8`

            ## 字段结构

            | # | 字段名 | 类型 | 可空 | 默认值 | 键 | 额外信息 | 注释 |
            |---:|---|---|---|---|---|---|---|
            | 1 | `DICT_ENTR_CODE` | `varchar(8)` | YES | - | - | - | 字典条目代码 |
            """;

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "分析表 t_ad_dict_entr_supn",
            null,
            List.of(),
            metadata,
            List.of("InterpretationPlan initial step 3 mcp_chatchat_mcp_server_sql_query_execute succeeded."),
            structuredAnswer,
            () -> false,
            "interpretation_plan_completed"
        );

        assertThat(result.answer())
            .contains("## 元数据依据")
            .contains("## 字段结构")
            .contains("`DICT_ENTR_CODE`")
            .doesNotContain("观察中未返回任何关于该表的实际数据");
        assertThat(result.metadata())
            .containsEntry("answerReviewSkipped", true)
            .containsEntry("answerReviewSkippedReason", "sql_metadata_and_execution_graph_semantic_gates_passed")
            .containsEntry("answerReviewStatus", AgentAnswerReview.ACCEPTED)
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "none")
            .doesNotContainKey("answerReviewRewriteSuggested")
            .doesNotContainKey("answerQualityAvailable");
    }

    @Test
    void finalizerMergesStructuredSqlMetadataMarkdownWhenSummaryOmitsColumnDetails() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("structuredSqlMetadataRendered", true);
        metadata.put("sqlMetadataSemanticGatePassed", true);
        metadata.put("executionGraphSemanticPassed", true);
        metadata.put("structuredSqlMetadataMarkdown", """
            ## \u5143\u6570\u636e\u4f9d\u636e

            - \u6570\u636e\u6e90\uff1a248\u6d4b\u8bd5\u6570\u636e\u5e93
            - \u8868\u5b9a\u4f4d\uff1a`livebos.t_dataflow_macro_def`
            - \u5b57\u6bb5\u6570\uff1a`12`

            ## \u5b57\u6bb5\u7ed3\u6784

            | # | \u5b57\u6bb5\u540d | \u7c7b\u578b | \u53ef\u7a7a | \u9ed8\u8ba4\u503c | \u952e | \u989d\u5916\u4fe1\u606f | \u6ce8\u91ca |
            |---:|---|---|---|---|---|---|---|
            | 1 | `ID` | `varchar(64)` | NO | - | PRI | - | \u4e3b\u952e |
            | 2 | `ENTRY_ID` | `varchar(64)` | YES | - | MUL | - | \u6d41\u7a0b\u5173\u8054\u5b57\u6bb5 |
            """);

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u4ec5\u6210\u529f\u83b7\u53d612\u4e2a\u5217\uff0c\u4f46\u672a\u80fd\u5c55\u5f00\u5217\u4fe1\u606f\u3002",
            List.of(),
            metadata,
            List.of("sql_metadata_search returned 12 columns.")
        );

        assertThat(result.answer())
            .contains("## \u5143\u6570\u636e\u4f9d\u636e")
            .contains("## \u5b57\u6bb5\u7ed3\u6784")
            .contains("`ID`")
            .contains("`ENTRY_ID`")
            .contains("\u6d41\u7a0b\u5173\u8054\u5b57\u6bb5");
        assertThat(result.metadata())
            .containsEntry("structuredSqlMetadataMergedInFinalizer", true)
            .containsEntry("structuredSqlMetadataMergeReason", "semantic_gate_passed_preserve_column_metadata");
    }

    @Test
    void structuredSqlMetadataAnswerStillUsesReviewerWhenSemanticGateFails() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "Reviewer diagnostic rewrite.",
                "Semantic gate failed, reviewer still ran."
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("structuredSqlMetadataRendered", true);
        metadata.put("sqlMetadataSemanticGatePassed", true);
        metadata.put("executionGraphSemanticPassed", false);

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "分析表 t_ad_dict_entr_supn",
            null,
            List.of(),
            metadata,
            List.of("InterpretationPlan initial step 3 mcp_chatchat_mcp_server_sql_query_execute succeeded."),
            """
                ## 元数据依据

                ## 字段结构

                | # | 字段名 | 类型 |
                |---:|---|---|
                | 1 | `DICT_ENTR_CODE` | `varchar(8)` |
                """,
            () -> false,
            "interpretation_plan_completed"
        );

        assertThat(result.answer()).contains("## 元数据依据");
        assertThat(result.metadata())
            .doesNotContainKey("answerReviewSkipped")
            .containsEntry("answerReviewStatus", AgentAnswerReview.REVISED)
            .containsEntry("answerReviewRewriteSuggested", true)
            .containsEntry("answerReviewRewriteApplied", false);
    }

    @Test
    void reviewerRevisionCanOverrideFinalAnswerWhenExplicitlyEnabled() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "Reviewer rewritten answer that is explicitly allowed.",
                "Reviewer suggested a rewrite"
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("allowReviewerRewrite", true);

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "question",
            null,
            List.of(),
            metadata,
            List.of("observation"),
            "Original final answer from the agent.",
            () -> false,
            "final_answer"
        );

        assertThat(result.answer()).contains("Reviewer rewritten answer that is explicitly allowed.");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", AgentAnswerReview.REVISED)
            .containsEntry("answerDecision", AnswerDecisionEngine.REVIEWER_REWRITE)
            .containsEntry("answerRewriteSource", "reviewer")
            .containsEntry("answerReviewRewriteSuggested", true)
            .containsEntry("answerReviewRewriteApplied", true);
    }
}
