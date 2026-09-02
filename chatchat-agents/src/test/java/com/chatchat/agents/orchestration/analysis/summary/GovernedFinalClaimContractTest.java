package com.chatchat.agents.orchestration.analysis.summary;

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
