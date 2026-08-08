package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerFinalizerTaskAssessmentTest {

    @Test
    void reviewerCannotReplaceASelectedBusinessResult() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "## 无法直接完成设计\n\n请补充数据库版本。",
                "Missing optional context"
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protectedCandidateAnswer", true);
        metadata.put("answerOrigin", "planner_generated_recovered");
        metadata.put("answerLifecycleStatus", "SELECTED");
        String design = """
            # 客户信息表设计

            | 字段 | 类型 |
            |---|---|
            | cust_id | BIGINT |
            """;

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "设计客户信息表",
            null,
            List.of(),
            metadata,
            List.of(),
            design,
            () -> false,
            "final_answer"
        );

        assertThat(result.answer())
            .contains("# 客户信息表设计")
            .contains("cust_id")
            .doesNotContain("无法直接完成设计");
        assertThat(result.metadata())
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerDecisionReason", "protected_business_result_retained")
            .containsEntry("answerReviewAuthority", "diagnostic_only")
            .containsEntry("answerReviewRewriteApplied", false);
    }

    @Test
    void appliesReviewerModelReanalysisWhenRuntimeProvidesCompleteEvidenceContext() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) -> {
            assertThat(observations).anyMatch(item -> item.contains("model_analysis_repair_v1"));
            assertThat(observations).anyMatch(item -> item.contains("returned_record_42"));
            return new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "## Revised analysis\n\nThe returned record is 42; no broader conclusion is asserted.",
                "Unsupported candidate claims were removed."
            );
        };
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("modelEvidenceReviewRewriteAllowed", true);
        metadata.put("modelAnalysisReviewContext", "Executed plan attempts: returned_record_42");

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "Analyze the result",
            null,
            List.of(),
            metadata,
            List.of("initial observation"),
            "The object is completely compliant based on its conventional name.",
            () -> false,
            "attempts_exhausted"
        );

        assertThat(result.answer())
            .contains("The returned record is 42")
            .doesNotContain("completely compliant");
        assertThat(result.metadata())
            .containsEntry("answerDecision", AnswerDecisionEngine.REVIEWER_REWRITE)
            .containsEntry("answerDecisionReason", "model_reanalyzed_complete_executed_evidence")
            .containsEntry("answerReviewAuthority", "evidence_analysis_repair")
            .containsEntry("answerReviewRewriteApplied", true)
            .containsEntry("modelAnalysisReviewContextApplied", true)
            .doesNotContainKey("modelAnalysisReviewContext");
    }

    @Test
    void attachesPublicAssessmentContractToFinalExecutionMetadata() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanEvidenceHistory", List.of(Map.of(
            "sufficient", false,
            "evidenceUsed", List.of("已返回的企业标准字段"),
            "missingEvidence", List.of("未召回的核心字段")
        )));

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "已生成基于现有标准字段的初版结构。",
            List.of(InteractionToolTrace.builder()
                .toolName("enterprise_metadata_search")
                .success(true)
                .build()),
            metadata,
            List.of("enterprise_metadata_search returned structured JSON")
        );

        assertThat(result.metadata())
            .containsEntry("taskResultAssessmentContractVersion", "task_result_assessment_v1")
            .containsEntry("taskResultExecutionStatus", "SUCCESS")
            .containsEntry("taskResultEvidenceStatus", "PARTIAL")
            .containsEntry("taskResultFulfillmentStatus", "PARTIAL")
            .containsEntry("taskResultDeliveryDecision", "PARTIAL_ARTIFACT")
            .containsKey("taskResultAssessment");
    }

    @Test
    void blocksInsufficientEvidenceRefusalWhenMcpReturnedQueryRows() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("oracle_tablespace_query")
            .success(true)
            .output("""
                {"success":true,"columns":["tablespace","size_mb"],"rows":[{"tablespace":"USERS","size_mb":102400}],"rowCount":1}
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "关键指标不完整，证据不足，无法分析。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("oracle_tablespace_query returned structured rows")
        );

        assertThat(result.answer())
            .contains("基于 MCP 查询结果的分析")
            .contains("USERS")
            .contains("102400")
            .doesNotContain("证据不足，无法分析");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "AVAILABLE")
            .containsEntry("mcpResultAnswerAllowed", true)
            .containsEntry("evidenceRefusalBlocked", true)
            .containsEntry("taskResultEvidenceStatus", "PARTIAL")
            .containsEntry("taskResultEvidenceAvailability", "AVAILABLE")
            .containsEntry("taskResultAnalysisCapability", "PARTIAL")
            .containsEntry("taskResultAnswerAllowed", true)
            .containsEntry("taskResultDeliveryDecision", "PARTIAL_ARTIFACT")
            .containsEntry("answerAssemblyMode", "PARTIAL");
    }

    @Test
    void preservesSubstantiveMultiSectionAnalysisWhenItAlsoDeclaresEvidenceGaps() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("mcp_runtime_registered_analysis_tool")
            .success(true)
            .output("""
                {"schemaVersion":"tool_result_summary.v1","summaryTruncated":true,
                 "resultPresent":true,"originalType":"LinkedHashMap","originalSummaryChars":20383,
                 "preview":{"results":[{"metric":"runtime-value","value":321}]}}
                """)
            .build();
        String candidate = """
            # Runtime analysis report

            Current evidence is incomplete and several optional signals are missing. This limits
            confidence, but it does not prevent analysis of the observations that were returned.

            ## Observed state

            - The first returned observation supports a measurable baseline.
            - The second signal confirms that the result is not empty.
            - The available sequence supports a cautious directional interpretation.

            ## Drivers

            The returned measurements show a coherent relationship across the available fields.
            That relationship is usable for the requested analysis even though broader context is
            unavailable. Claims here remain bounded to the successful tool result.

            ## Risks and limitations

            Missing external context may change confidence and completeness. It does not erase the
            facts already returned, so the report preserves those facts and labels the boundary.

            ## Recommended follow-up

            Validate the missing optional dimensions in a later query and compare them with this
            baseline. Until then, use the supported observations and avoid extrapolating beyond them.
            """;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stopReason", "evidence_partial_analysis");

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            candidate, List.of(trace), metadata, List.of("Tool analysis_tool succeeded with non-empty data."));

        assertThat(result.answer())
            .startsWith("# Runtime analysis report")
            .contains("## Observed state", "## Drivers", "## Risks and limitations")
            .doesNotContain("## 基于 MCP 查询结果的分析");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "AVAILABLE")
            .doesNotContainEntry("evidenceRefusalBlocked", true);
    }

    @Test
    void malformedToolResultPreservesCandidateAnalysisWithExplicitLimitations() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace malformed = InteractionToolTrace.builder()
            .toolName("mcp_dynamic_web_search")
            .success(true)
            .output("{\"success\":true,\"results\":[")
            .build();
        String candidate = "# 今日市场分析\n\n市场情绪分析框架与风险观察仍可提供，但缺少已验证的实时指数数值。";

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stopReason", "evidence_partial_analysis");
        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            candidate,
            List.of(malformed),
            metadata,
            List.of("web search returned an unparseable payload")
        );

        assertThat(result.answer())
            .contains("# 今日市场分析")
            .contains("市场情绪分析框架")
            .contains("数据覆盖说明")
            .contains("实时事实与数值尚未完成验证")
            .doesNotStartWith("工具调用没有产生可解析、可信的结果");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "UNAVAILABLE")
            .containsEntry("mcpUnavailableResultCount", 1)
            .containsEntry("evidenceLimitedAnalysisPreserved", true);
    }

    @Test
    void finalizerEnforcesNonExecutedDisclosureForRequestedDdlDraft() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("artifactContract", Map.of(
            "artifactType", "DDL",
            "deliveryMode", "DRAFT",
            "executionStatus", "NOT_EXECUTED",
            "authorizationStatus", "NOT_APPLICABLE_TO_DRAFT",
            "humanReviewRequired", true,
            "assumptions", List.of("字段设计需要人工复核"),
            "disclosure", "> 制品状态：以下内容是未执行的 DDL 草稿，仅供人工审核。"
        ));
        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "请根据标准字段设计一份建表 DDL 草稿",
            null,
            List.of(),
            metadata,
            List.of(),
            "```sql\nCREATE TABLE customer (id BIGINT);\n```",
            () -> false,
            "final_answer"
        );

        assertThat(result.answer())
            .contains("> 制品状态：以下内容是未执行的 DDL 草稿")
            .contains("CREATE TABLE customer");
        assertThat(result.metadata())
            .containsEntry("artifactType", "DDL")
            .containsEntry("artifactExecutionStatus", "NOT_EXECUTED")
            .containsEntry("artifactAuthorizationStatus", "NOT_APPLICABLE_TO_DRAFT")
            .containsEntry("artifactHumanReviewRequired", true);
    }
}
