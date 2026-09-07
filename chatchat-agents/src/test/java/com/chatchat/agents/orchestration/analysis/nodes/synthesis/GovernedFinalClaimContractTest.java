package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedFinalClaimContractTest {

    private final GovernedFinalClaimContract contract = new GovernedFinalClaimContract();

    @Test
    void legacyClaimSelectionRequiresAModelAuthoredBody() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.markdown()).isEmpty();
    }

    @Test
    void unknownClaimIdDoesNotPublishALedgerAsReport() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["invented-claim"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("UNKNOWN_FINAL_CLAIM_ID");
        assertThat(projection.markdown()).isEmpty();
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
    void legacySectionsCannotBecomeReportProse() {
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

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.markdown()).isEmpty();
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
    void legacyDemandAndMetricDirectionsCannotBecomeReportProse() {
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

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.markdown()).isEmpty();
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
    void legacyManagementReviewCannotBecomeReportProse() {
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

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.markdown()).isEmpty();
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
    void validatedFactsDoNotReplaceAMissingModelReport() {
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
        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.markdown()).isEmpty();
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
        assertThat(projection.markdown()).isEmpty();
    }

    @Test
    void rejectsIncompleteSourceCoverageWithoutSynthesizingAReport() {
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
        assertThat(projection.markdown()).isEmpty();
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
        assertThat(projection.markdown()).isEmpty();
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
             "reportMarkdown":"The account is overwhelmingly invested in securities, with limited cash available while trading remains active.\\n\\nTotal assets are 847174.25, security value is 846262.20, cash is 912.05, and 20 trades were returned.",
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
        assertThat(projection.reason()).isEqualTo("MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT");
        assertThat(projection.markdown())
            .contains("overwhelmingly invested in securities", "847174.25", "20 trades")
            .doesNotContain("二、关键发现", "# 数据分析报告");
        assertThat(projection.selectedClaimIds())
            .containsExactly("fact:assets", "fact:cash", "fact:trades");
    }

    @Test
    void runtimeComputedMetricCanGroundDerivedNumberThroughDataRef() {
        AnalysisSummaryResult source = factSummary(
            "calculation", "source-value", "Returned source value is 40", "40");
        var finding = new com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine.Finding(
            "derived-total", "aggregate", "Calculated total", new java.math.BigDecimal("99"),
            "units", "runtime calculation", List.of("calculation.records[1]"), Map.of());
        var catalog = com.chatchat.agents.orchestration.analysis.report.VerifiedReportDataCatalog.fromRuntime(
            Map.of("deterministicInsightResults", List.of(
                Map.of("status", "executed", "findings", List.of(finding)))));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Calculated total is 99",
             "findings":[{"section":"CORE","text":"Calculated total is 99",
               "dataRef":"computed:0:derived-total", "basisClaimIds":["source-value"]}],
             "coverage":[{"claimId":"source-value","disposition":"USED","reason":"calculation"}]}
            """, contract.compile(List.of(source)), catalog);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains("Calculated total is 99");
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
             "reportMarkdown":"Returned value is 42",
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
    void v4RetainsEvidenceMetadataAlongsideTheModelReport() {
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Returned value is 42",
             "findings":[{"section":"CORE","question":"当前情况", "text":"Returned value is 42",
               "basisClaimIds":["claim-1"]}],
             "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"current result"}]}
            """, contract.compile(List.of(summary())));
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.analyticalReport()).containsEntry("executiveSummaryIds", List.of("F1"));
        assertThat(projection.markdown()).contains("Returned value is 42")
            .doesNotContain("暂无同时绑定计算数据与证据的核心结论", "数据状态：待补充可验证数据");
    }

    @Test
    void runtimeDoesNotPretendToJudgeHabitualMeaningWithoutModelReview() {
        var compilation = contract.compile(List.of(factSummary("closed", "sample",
            "抽样清仓记录的持仓天数为2天", "2")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"客户通常持仓2天，习惯快进快出。当前仅为样本。",
             "findings":[{"section":"CORE","text":"客户通常持仓2天，习惯快进快出。当前仅为样本。",
               "basisClaimIds":["sample"]}],
             "coverage":[{"claimId":"sample","disposition":"USED","reason":"sample"}]}
            """, compilation);
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).isNotBlank();
    }

    @Test
    void preservesScopedSampleAnalysis() {
        var compilation = contract.compile(List.of(factSummary("closed", "sample",
            "抽样清仓记录的持仓天数为2天", "2")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"本次抽样记录持仓2天，无法据此判断其通常持仓周期。",
             "findings":[{"section":"CORE","text":"本次抽样记录持仓2天，无法据此判断其通常持仓周期。",
               "basisClaimIds":["sample"]}],
             "coverage":[{"claimId":"sample","disposition":"USED","reason":"sample"}]}
            """, compilation);
        assertThat(projection.modelSelectionAccepted()).isTrue();
    }

    @Test
    void runtimeLeavesCrossChapterMeaningToModelReview() {
        var compilation = contract.compile(List.of(factSummary("holdings", "holding-count",
            "返回20条持仓记录", "20")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"客户共持有20只证券\\n\\n本次仅为持仓样本，不能确认全部持仓",
             "findings":[{"section":"CORE","text":"客户共持有20只证券",
               "basisClaimIds":["holding-count"]},
               {"section":"LIMITATION","text":"本次仅为持仓样本，不能确认全部持仓",
               "basisClaimIds":["holding-count"]}],
             "coverage":[{"claimId":"holding-count","disposition":"USED","reason":"observed"}]}
            """, compilation);
        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).isNotBlank();
    }

    @Test
    void semanticRepairCannotRemoveAnExactAdmittedEvidenceClaim() {
        var model = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"schemaVersion":"semantic_claim_review.v1","reviews":[{"claimId":"F1","decision":"REPAIR",
             "issue":"reviewer requested a narrower statement","evidenceIds":["asset"],
             "repairAction":"NARROW_SCOPE"}]}
            """);
        var reviewed = new GovernedFinalClaimContract(
            com.chatchat.common.runtime.summary.analysis.contract.AnalysisAcceptanceContract.standard(),
            new SemanticClaimReviewer(model), "What is the returned asset value?");
        var compilation = reviewed.compile(List.of(factSummary("account", "asset", "Returned asset value is 42", "42")));

        var projection = reviewed.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Returned asset value is 42","findings":[
             {"section":"CORE","text":"Returned asset value is 42","basisClaimIds":["asset"]}]}
            """, compilation);

        assertThat(projection.markdown()).contains("Returned asset value is 42")
            .doesNotContain("No verified evidence");
        assertThat(projection.selectedClaimIds()).contains("asset");
    }

    @Test
    void exactAdmittedFactSurvivesEquivalentScientificNotationInSupportingValue() {
        var compilation = contract.compile(List.of(factSummary("etf", "market-total",
            "Returned ETF total is 259,106,965.42", "2.5910696542E8")));

        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Returned ETF total is 259,106,965.42","findings":[
             {"section":"CORE","text":"Returned ETF total is 259,106,965.42",
              "basisClaimIds":["market-total"]}]}
            """, compilation);

        assertThat(projection.markdown()).contains("Returned ETF total is 259,106,965.42");
        assertThat(projection.selectedClaimIds()).containsExactly("market-total");
    }

    @Test
    void preservesModelDerivedMetricAndRecordsEvidenceBindingInsteadOfVetoingIt() {
        var compilation = contract.compile(List.of(factSummary(
            "etf", "market", "Returned market total is 100", "100")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"The selected products represent 68.65% of the market total.","findings":[
             {"section":"CORE","text":"The selected products represent 68.65% of the market total.",
              "basisClaimIds":["market"]}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.reason()).isEqualTo("MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT");
        assertThat(projection.markdown()).contains("68.65%");
        assertThat(projection.analyticalReport()).containsKey("evidenceBindingAudit");
    }

    @Test
    void unknownEvidenceIdIsAuditedWithoutDeletingModelFinding() {
        var compilation = contract.compile(List.of(factSummary(
            "etf", "market", "Returned market total is 100", "100")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Model analysis remains readable.","findings":[
             {"section":"CORE","text":"Model analysis remains readable.",
              "basisClaimIds":["missing"]}]}
            """, compilation);

        assertThat(projection.markdown()).contains("Model analysis remains readable.");
        assertThat(projection.selectedClaimIds()).isEmpty();
        assertThat(projection.analyticalReport().get("evidenceBindingAudit").toString())
            .contains("UNBOUND", "missing");
    }

    @Test
    void publicationDoesNotInvokeSemanticReviewer() {
        var model = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        var reviewed = new GovernedFinalClaimContract(
            com.chatchat.common.runtime.summary.analysis.contract.AnalysisAcceptanceContract.standard(),
            new SemanticClaimReviewer(model), "ETF analysis");
        var projection = reviewed.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"Returned market total is 100","findings":[
             {"section":"CORE","text":"Returned market total is 100","basisClaimIds":["market"]}]}
            """, reviewed.compile(List.of(factSummary(
                "etf", "market", "Returned market total is 100", "100"))));

        assertThat(projection.markdown()).contains("Returned market total is 100");
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void publishesTheModelAuthoredReportBodyInsteadOfRecomposingItFromFindingFields() {
        var compilation = contract.compile(List.of(factSummary(
            "account", "asset", "Returned asset value is 847174.25", "847174.25")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"# 客户交易与资产分析报告\\n\\n## 一、核心结论\\n\\n总资产为 847174.25 元，呈现满仓短线特征。\\n\\n| 指标 | 数值 |\\n| --- | --- |\\n| 总资产 | 847174.25 |",
             "findings":[{"section":"CORE","text":"Returned asset value is 847174.25",
              "basisClaimIds":["asset"]}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.reason()).isEqualTo("MODEL_ANALYSIS_PUBLISHED_WITH_EVIDENCE_AUDIT");
        assertThat(projection.markdown())
            .startsWith("# 客户交易与资产分析报告")
            .contains("## 一、核心结论", "| 总资产 | 847174.25 |")
            .doesNotContain("# 数据分析报告", "解释与判断");
        assertThat(projection.analyticalReport())
            .containsEntry("publicationMode", "MODEL_REPORT_MARKDOWN")
            .containsKey("evidenceBindingAudit");
        assertThat(projection.selectedClaimIds()).contains("asset");
    }

    @Test
    void requiresModelReportInsteadOfComposingFindingFields() {
        var compilation = contract.compile(List.of(factSummary(
            "account", "asset", "Returned asset value is 847174.25", "847174.25")));
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "findings":[
             {"section":"CORE","text":"Returned asset value is 847174.25",
              "basisClaimIds":["asset"]}]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.markdown()).isEmpty();
        assertThat(projection.reason()).isEqualTo("MODEL_REPORT_MARKDOWN_REQUIRED");
        assertThat(projection.analyticalReport()).isEmpty();
    }

    @Test
    void rejectsBlankNonTextAndOversizedBodiesWithoutTruncatingOrComposing() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var compilation = contract.compile(List.of(summary()));
        for (Object body : List.of(" ", Map.of("text", "not a report"), "x".repeat(60_001))) {
            String payload = mapper.writeValueAsString(Map.of(
                "schemaVersion", "governed_management_synthesis.v4", "reportMarkdown", body,
                "findings", List.of(Map.of("section", "CORE", "text", "Source value is 42",
                    "basisClaimIds", List.of("claim-1")))));
            var projection = contract.project(payload, compilation);
            assertThat(projection.modelSelectionAccepted()).isFalse();
            assertThat(projection.markdown()).isEmpty();
            assertThat(projection.selectedClaimIds()).isEmpty();
        }
    }

    @Test
    void preservesTheExactModelBodyIncludingWhitespaceAndTheTail() throws Exception {
        String body = "  # Model report\n\n| metric | value |\n|---|---|\n| asset | 42 |\n"
            + "Bounded model interpretation.\n".repeat(1500) + "\nMODEL_TAIL\n ";
        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "schemaVersion", "governed_management_synthesis.v4", "reportMarkdown", body,
            "findings", List.of(Map.of("section", "CORE", "text", "Source value is 42",
                "basisClaimIds", List.of("claim-1")))));
        var projection = contract.project(payload, contract.compile(List.of(summary())));
        assertThat(projection.markdown()).isEqualTo(body);
        assertThat(projection.analyticalReport()).containsKey("evidenceBindingAudit");
    }

    @Test
    void aBodyWithoutFindingsStillRequiresProtocolRepair() {
        var projection = contract.project("""
            {"schemaVersion":"governed_management_synthesis.v4",
             "reportMarkdown":"# Model report","findings":[]}
            """, contract.compile(List.of(summary())));
        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("EMPTY_MANAGEMENT_FINDINGS");
        assertThat(projection.markdown()).isEmpty();
    }

    @Test
    void incompleteReviewReportsTheMissingClaimIdsWithoutGuessingVerdicts() throws Exception {
        var review = new java.util.LinkedHashMap<String, Object>();
        review.put("status", "PASS");
        for (String field : List.of("requirementCoverage", "claimConsistency", "crossWorkerConflicts",
                "duplicateEvidence", "unsupportedInferences", "missingCriticalDimensions", "claimAssessments", "challenges")) {
            review.put(field, List.of());
        }
        review.put("evidenceSufficiency", Map.of());
        String output = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "schemaVersion", "governed_management_synthesis.v4", "driverReview", review,
            "driverReasoning", Map.of("derivedClaims", List.of())));
        var audit = contract.inspectDriverAudit(output, contract.compile(List.of(summary())), List.of());
        assertThat(audit.valid()).isFalse();
        assertThat(audit.reason()).isEqualTo("DRIVER_REVIEW_CLAIM_COVERAGE_INCOMPLETE");
        assertThat(audit.review()).containsEntry("missingClaimIds", List.of("claim-1"))
            .containsEntry("expectedClaimCount", 1).containsEntry("assessmentCount", 0);
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
