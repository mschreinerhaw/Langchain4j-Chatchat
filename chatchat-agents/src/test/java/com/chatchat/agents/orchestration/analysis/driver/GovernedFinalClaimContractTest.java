package com.chatchat.agents.orchestration.analysis.driver;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedFinalClaimContractTest {

    private final GovernedFinalClaimContract contract = new GovernedFinalClaimContract();

    @Test
    void publishesOnlySelectedAdmittedClaimText() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains("返回值为 42");
        assertThat(projection.markdown()).doesNotContain("模型新增结论");
    }

    @Test
    void unknownClaimIdFallsBackToAdmittedLedger() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["invented-claim"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("UNKNOWN_FINAL_CLAIM_ID");
        assertThat(projection.markdown()).contains("返回值为 42").doesNotContain("invented-claim");
    }

    @Test
    void rejectedClaimIsNeverAddedToPublicationLedger() {
        AnalysisSummaryResult rejected = summary().withEvidence(Map.of(
            "insights", List.of(insight("claim-2", "不应发布")),
            "claimAdmissionDecisions", List.of(Map.of(
                "claimId", "claim-2", "admitted", false))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(rejected));

        assertThat(compilation.active()).isFalse();
        assertThat(compilation.claimContractObserved()).isTrue();
    }

    @Test
    void preservesModelSelectedBusinessSectionsWithoutAllowingNewClaimText() {
        AnalysisSummaryResult second = summary().withEvidence(Map.of(
            "insights", List.of(insight("claim-2", "存在需要关注的例外")),
            "claimAdmissionDecisions", List.of(Map.of(
                "claimId", "claim-2", "admitted", true))));
        GovernedFinalClaimContract.Compilation compilation = contract.compile(
            List.of(summary(), second));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],
             "sections":[{"sectionType":"EXCEPTIONS","claimIds":["claim-2"]}]}
            """, compilation);

        assertThat(projection.markdown())
            .contains("## 核心结论", "## 异常与边界", "返回值为 42", "存在需要关注的例外")
            .doesNotContain("模型新增结论");
    }

    @Test
    void legacySummaryWithoutClaimContractIsDistinguishedFromRejectedClaims() {
        AnalysisSummaryResult legacy = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-a", "chunkIndex", 1), Map.of(),
            "legacy narrative", "MODEL_SUMMARY", Map.of());

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(legacy));

        assertThat(compilation.active()).isFalse();
        assertThat(compilation.claimContractObserved()).isFalse();
    }

    @Test
    void publishesDemandAnalysisAndClearlyUnverifiedMetricDirections() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[],
             "demandAnalysis":{"decisionGoal":"判断资产增长来源与风险暴露",
               "priorityQuestions":["收益是否集中于少数标的"]},
             "metricAssociations":[{"title":"检验收益贡献与持仓集中度的关系",
               "basisClaimIds":["claim-1"],
               "candidateMetrics":["当日盈亏贡献率","持仓市值占比"],
               "analysisMethod":"按标的计算贡献并对照持仓权重",
               "validationNeeded":["完整持仓范围","指标聚合语义"]}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains(
            "## 需求分析", "判断资产增长来源与风险暴露",
            "## 指标联想与后续分析", "待验证分析方向",
            "当日盈亏贡献率", "完整持仓范围");
    }

    @Test
    void rejectsMetricDirectionWithoutSelectedAdmittedBasis() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[],
             "metricAssociations":[{"title":"越界联想","basisClaimIds":["claim-x"],
               "candidateMetrics":["未知指标"],"analysisMethod":"推断","validationNeeded":[]}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("INVALID_METRIC_ASSOCIATION_BASIS");
        assertThat(projection.markdown()).doesNotContain("越界联想", "未知指标");
    }

    @Test
    void publishesManagementReviewGroundedInWorkerClaims() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[],
             "managementReview":{
               "overallAssessment":{"text":"现有分析确认了当前返回值，但解释链仍不完整",
                 "basisClaimIds":["claim-1"]},
               "identifiedProblems":[{"text":"缺少可用于比较的基准",
                 "basisClaimIds":["claim-1"]}],
               "improvementSuggestions":[{"text":"补充同口径历史基准后再评价偏离程度",
                 "basisClaimIds":["claim-1"]}],
               "nextWorkDirections":[{"text":"优先验证指标变化与业务事件的时间对应关系",
                 "basisClaimIds":["claim-1"]}]}}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains(
            "## 分析复盘与改进方向", "总体评价：现有分析确认了当前返回值",
            "发现的问题：缺少可用于比较的基准",
            "改进建议：补充同口径历史基准",
            "下一步方向：优先验证指标变化");
    }

    @Test
    void rejectsUngroundedManagementReview() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[],
             "managementReview":{"identifiedProblems":[
               {"text":"无依据的问题判断","basisClaimIds":["claim-x"]}]}}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("INVALID_MANAGEMENT_REVIEW_BASIS");
        assertThat(projection.markdown()).doesNotContain("无依据的问题判断");
    }

    @Test
    void publishesValidatedObservedFactsEvenWhenWorkerProducedNoInsight() {
        AnalysisSummaryResult factsOnly = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "account-overview", "chunkIndex", 1), Map.of(),
            "Account metrics were observed.", "MODEL_SUMMARY", Map.of(
                "observedFactClaims", List.of(Map.of(
                    "claimId", "observed-fact:asset",
                    "claim", "Total assets are 847174.25 and current-day profit is 42263.81",
                    "claimClass", "OBSERVED_RETURNED_FACT",
                    "recordRefs", List.of("account-overview.records[1]"),
                    "supportingValues", List.of("847174.25", "42263.81"),
                    "confidence", "HIGH", "caveats", List.of()))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(factsOnly));
        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["observed-fact:asset"],"sections":[]}
            """, compilation);

        assertThat(compilation.active()).isTrue();
        assertThat(compilation.claimContractObserved()).isTrue();
        assertThat(projection.markdown()).contains("847174.25", "42263.81");
    }

    @Test
    void preservesEvidenceBoundDynamicAnalysisAsDriverClaimWithoutDuplicatedInsight() {
        AnalysisSummaryResult analysisOnly = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "engine-metrics", "chunkIndex", 1), Map.of(),
            "Worker analyzed the metric catalog.", "MODEL_SUMMARY", Map.of(
                "analysisItems", List.of(Map.of(
                    "itemId", "buffer-health",
                    "analysisType", "CURRENT_STATE",
                    "status", "SUPPORTED",
                    "finding", "Buffer allocation has no recorded wait",
                    "businessMeaning", "Current allocation pressure is not evident",
                    "basisRecordRefs", List.of("engine-metrics.records[8]"),
                    "supportingValues", List.of("Innodb_buffer_pool_wait_free", "0"),
                    "method", "OBSERVE",
                    "confidence", "HIGH",
                    "limitations", List.of()))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(analysisOnly));
        GovernedFinalClaimContract.Projection projection = contract.project("", compilation);

        assertThat(compilation.active()).isTrue();
        assertThat(compilation.claimContractObserved()).isTrue();
        assertThat(compilation.claims().values().toString())
            .contains("GOVERNED_ANALYSIS_ITEM", "Buffer allocation has no recorded wait",
                "Innodb_buffer_pool_wait_free");
        assertThat(projection.markdown())
            .contains("Buffer allocation has no recorded wait")
            .doesNotContain("NO_ADMITTED_CLAIMS");
    }

    @Test
    void rejectsDriverSelectionThatOmitsAnAnalyzedSourceAndFallsBackToFullCoverage() {
        AnalysisSummaryResult account = factSummary(
            "account-overview", "observed-fact:account", "Total assets are 847174.25", "847174.25");
        AnalysisSummaryResult trades = factSummary(
            "trade-history", "observed-fact:trades", "There are 20 returned trades", "20");

        GovernedFinalClaimContract.Compilation compilation = contract.compile(
            List.of(account, trades));
        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["observed-fact:trades"],"sections":[],
             "managementReview":{"identifiedProblems":[{"text":"Account data is missing",
               "basisClaimIds":["observed-fact:trades"]}]}}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("INCOMPLETE_ANALYSIS_SOURCE_COVERAGE");
        assertThat(projection.markdown()).contains("847174.25", "20")
            .doesNotContain("Account data is missing");
    }

    @Test
    void rejectsSelectionThatOmitsAnObservedFactInsideTheSameReturnedDataset() {
        AnalysisSummaryResult combined = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "combined-result", "chunkIndex", 1), Map.of(),
            "Combined account and trade analysis", "MODEL_SUMMARY",
            Map.of("observedFactClaims", List.of(
                Map.of("claimId", "observed-fact:account", "claim", "Total assets are 847174.25",
                    "recordRefs", List.of("combined-result.records[1]"),
                    "supportingValues", List.of("847174.25")),
                Map.of("claimId", "observed-fact:trades", "claim", "There are 20 trades",
                    "recordRefs", List.of("combined-result.records[2]"),
                    "supportingValues", List.of("20")))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(combined));
        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["observed-fact:trades"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("INCOMPLETE_OBSERVED_FACT_COVERAGE");
        assertThat(projection.markdown()).contains("847174.25", "20");
    }

    @Test
    void admitsProfessionalManagementSynthesisBoundToClaimsWithoutCopyingTheLedger() {
        AnalysisSummaryResult combined = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "customer-analysis", "chunkIndex", 1), Map.of(),
            "Worker completed account and trading analysis", "MODEL_SUMMARY",
            Map.of("observedFactClaims", List.of(
                Map.of("claimId", "fact:assets", "claim",
                    "Total assets are 847174.25 and security value is 846262.20",
                    "recordRefs", List.of("customer-analysis.records[1]"),
                    "supportingValues", List.of("847174.25", "846262.20")),
                Map.of("claimId", "fact:cash", "claim", "Cash balance is 912.05",
                    "recordRefs", List.of("customer-analysis.records[1]"),
                    "supportingValues", List.of("912.05")),
                Map.of("claimId", "fact:trades", "claim", "There are 20 trades",
                    "recordRefs", List.of("customer-analysis.records[2]"),
                    "supportingValues", List.of("20")))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(combined));
        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v2",
             "findings":[
               {"section":"CORE","text":"The account is overwhelmingly invested in securities, with limited cash available while trading remains active.",
                "basisClaimIds":["fact:assets","fact:cash","fact:trades"]},
               {"section":"EVIDENCE","text":"Total assets are 847174.25, security value is 846262.20, cash is 912.05, and 20 trades were returned.",
                "basisClaimIds":["fact:assets","fact:cash","fact:trades"]}],
             "coverage":[
               {"claimId":"fact:assets","disposition":"USED","reason":"answers asset structure"},
               {"claimId":"fact:cash","disposition":"USED","reason":"answers liquidity"},
               {"claimId":"fact:trades","disposition":"USED","reason":"answers activity"}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.reason()).isEqualTo("GROUNDED_MANAGEMENT_SYNTHESIS_ADMITTED");
        assertThat(projection.markdown())
            .contains("overwhelmingly invested in securities", "847174.25", "20 trades")
            .contains("二、关键发现");
        assertThat(projection.selectedClaimIds())
            .containsExactly("fact:assets", "fact:cash", "fact:trades");
    }

    @Test
    void restoresSupportedSourceFindingWhenDriverWritesOnlyItsGap() {
        AnalysisSummaryResult account = factSummary(
            "account-overview", "fact:account",
            "Current total assets are 847174.25", "847174.25");
        AnalysisSummaryResult trades = factSummary(
            "trade-history", "fact:trades", "There are 20 current-day trades", "20");
        GovernedFinalClaimContract.Compilation compilation = contract.compile(
            List.of(account, trades));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v2",
             "findings":[{"section":"CORE","text":"There are 20 current-day trades",
               "basisClaimIds":["fact:trades"]}],
             "coverage":[
               {"claimId":"fact:account","disposition":"SUPPORTING_CONTEXT","reason":"no history"},
               {"claimId":"fact:trades","disposition":"USED","reason":"current activity"}],
             "managementReview":{"identifiedProblems":[{"text":"Historical asset series is unavailable",
               "basisClaimIds":["fact:account"]}]}}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown())
            .contains("20 current-day trades", "Current total assets are 847174.25")
            .contains("Historical asset series is unavailable");
        assertThat(projection.selectedClaimIds()).contains("fact:account", "fact:trades");
    }

    @Test
    void rejectsManagementSynthesisWithInventedValue() {
        AnalysisSummaryResult account = factSummary(
            "account-overview", "fact:account", "Total assets are 847174.25", "847174.25");
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(account));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v2",
             "findings":[{"section":"CORE","text":"Total assets grew by 99.5%.",
               "basisClaimIds":["fact:account"]}],
             "coverage":[{"claimId":"fact:account","disposition":"USED","reason":"asset result"}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("UNGROUNDED_MANAGEMENT_FINDING_VALUE");
        assertThat(projection.markdown()).doesNotContain("99.5%");
    }

    @Test
    void rendersStructuredReasoningAndChecksItsNumericGrounding() {
        var compilation = contract.compile(List.of(factSummary(
            "trades", "fact:trades", "There are 20 trades", "20")));
        String payload = """
            {"schemaVersion":"governed_management_synthesis.v3",
             "demandAnalysis":{"decisionGoal":"How active is trading?"},
             "findings":[
               {"section":"LIMITATION","text":"No historical baseline", "basisClaimIds":["fact:trades"]},
               {"section":"DEEP_DIVE","text":"There are 20 trades", "question":"Trading activity",
                "comparison":"No trend can be established", "implication":"Monitor activity",
                "confidence":"Observed count only", "basisClaimIds":["fact:trades"]},
               {"section":"CORE","text":"Current activity is observable", "basisClaimIds":["fact:trades"]}],
             "coverage":[{"claimId":"fact:trades","disposition":"USED","reason":"activity"}]}
            """;
        var projection = contract.project(payload, compilation);
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains("分析问题：How active is trading?",
            "### Trading activity", "**业务影响**：Monitor activity", "**判断可信度**：Observed count only");
        assertThat(projection.markdown().indexOf("一、分析结论"))
            .isLessThan(projection.markdown().indexOf("二、关键发现"));
        assertThat(projection.markdown().indexOf("二、关键发现"))
            .isLessThan(projection.markdown().indexOf("六、数据边界"));
        var invalid = contract.project(payload.replace("Monitor activity", "Expect 99.5% growth"), compilation);
        assertThat(invalid.modelSelectionAccepted()).isFalse();
        assertThat(invalid.reason()).isEqualTo("UNGROUNDED_MANAGEMENT_FINDING_VALUE");
        assertThat(invalid.markdown()).doesNotContain("99.5%");
    }

    @Test
    void driverRejectedClaimCannotReenterThePublishedDecision() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v3",
             "driverReview":{"claimAssessments":[
               {"claimId":"claim-1","verdict":"REJECT","reason":"unsupported"}]},
             "findings":[{"section":"CORE","text":"Returned value is 42",
               "basisClaimIds":["claim-1"]}],
             "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"selected"}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason())
            .isEqualTo("DRIVER_REJECTED_CLAIM_SELECTED_FOR_PUBLICATION");
    }

    @Test
    void deduplicatesObservedFactAndInsightBoundToTheSameEvidence() {
        AnalysisSummaryResult duplicate = summary().withEvidence(Map.of(
            "observedFactClaims", List.of(Map.of(
                "claimId", "observed-fact:duplicate", "claim", "Returned value is 42",
                "recordRefs", List.of("dataset.records[1]"),
                "supportingValues", List.of("42")))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(duplicate));

        assertThat(compilation.claims()).hasSize(1).containsKey("claim-1");
    }

    @Test
    void composesDataBoundBlockAndIgnoresModelSuppliedChartValues() {
        var finding = new com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine.Finding(
            "sum", "aggregate", "Returned total", new java.math.BigDecimal("42"), "units", "sum(value)",
            List.of("dataset.records[1].value"), Map.of());
        var catalog = com.chatchat.agents.orchestration.analysis.report.VerifiedReportDataCatalog.fromRuntime(
            Map.of("deterministicInsightResults", List.of(Map.of("status", "executed", "findings", List.of(finding)))));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v3",
             "findings":[{"section":"CORE","question":"What is the total?", "text":"Returned value is 42",
               "dataRef":"computed:0:sum", "visualizationIntent":"KPI", "chartData":[99999],
               "basisClaimIds":["claim-1"]}],
             "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"total"}]}
            """, contract.compile(List.of(summary())), catalog);
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.analyticalReport()).containsEntry("executiveSummaryIds", List.of("F1"));
        var blocks = (List<?>) projection.analyticalReport().get("blocks");
        var block = (com.chatchat.agents.orchestration.analysis.report.AnalyticalInsightBlock) blocks.get(0);
        assertThat(block.data()).containsEntry("metric", "42");
        assertThat(block.data().toString()).doesNotContain("99999");
    }

    @Test
    void v4UsesSameMissingDataDecisionInStructuredAndTextReports() {
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "findings":[{"section":"CORE","question":"当前情况", "text":"Returned value is 42",
               "basisClaimIds":["claim-1"]}],
             "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"current result"}]}
            """, contract.compile(List.of(summary())));
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.analyticalReport()).containsEntry("executiveSummaryIds", List.of());
        assertThat(projection.markdown()).contains("暂无同时绑定计算数据与证据的核心结论", "数据状态：待补充可验证数据");
    }

    private AnalysisSummaryResult factSummary(String dataset, String claimId,
                                               String claim, String value) {
        return AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", dataset, "chunkIndex", 1), Map.of(), claim,
            "MODEL_SUMMARY", Map.of("observedFactClaims", List.of(Map.of(
                "claimId", claimId, "claim", claim, "claimClass", "OBSERVED_RETURNED_FACT",
                "recordRefs", List.of(dataset + ".records[1]"),
                "supportingValues", List.of(value), "confidence", "HIGH",
                "caveats", List.of()))));
    }

    private AnalysisSummaryResult summary() {
        return AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-a", "chunkIndex", 1), Map.of(),
            "返回值为 42", "MODEL_SUMMARY", Map.of(
                "insights", List.of(insight("claim-1", "返回值为 42")),
                "claimAdmissionDecisions", List.of(Map.of(
                    "claimId", "claim-1", "admitted", true))));
    }

    private Map<String, Object> insight(String id, String text) {
        return Map.of(
            "claimId", id,
            "claim", text,
            "claimClass", "OBSERVED_RETURNED_FACT",
            "confidence", "HIGH",
            "recordRefs", List.of("dataset.records[1]"),
            "supportingValues", List.of("42"),
            "caveats", List.of());
    }
}
