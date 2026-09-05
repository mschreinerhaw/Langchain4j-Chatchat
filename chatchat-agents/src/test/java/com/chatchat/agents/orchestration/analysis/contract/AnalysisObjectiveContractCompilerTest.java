package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisObjectiveContractCompilerTest {

    @Test
    void buildsSourceNeutralDynamicAnalysisAgendaFromMaintainedIntent() {
        Map<String, Object> intent = Map.of(
            "metrics", List.of("资产规模", "盈亏"),
            "dimensions", List.of("证券", "日期"),
            "analysisFocus", List.of("交易行为与偏好"),
            "expectedRelationships", List.of("资产结构与交易行为关联"));
        Map<String, Object> context = Map.of("workerAnalysisContext", Map.of(
            "businessIntent", intent,
            "currentTemplate", Map.of(
                "templateId", "dynamic-template",
                "analysisRole", "provide one part of the customer analysis",
                "matchedQuestionAspects", List.of("当前资产与盈亏"))));

        Map<String, Object> contract = new AnalysisObjectiveContractCompiler().compile(
            "分析客户资产、盈亏和交易偏好",
            new DataAnalysisPosition("dataset-a", 1, 1, 1, 20, 20), context);

        assertThat(contract.get("analysisAgenda").toString())
            .contains("dynamic_analysis_agenda.v1", "SUPPORTED_FIRST_ADVISORY_GAPS_LAST")
            .contains("CURRENT_STATE", "STRUCTURE_AND_DISTRIBUTION")
            .contains("PERFORMANCE_AND_CONTRIBUTION", "BEHAVIOR_OR_PATTERN")
            .contains("CROSS_METRIC_OR_DATASET_RELATIONSHIP")
            .contains("资产规模", "交易行为与偏好", "资产结构与交易行为关联");
        assertThat(contract.get("workerObligations").toString())
            .contains("COMPLETE_DYNAMIC_ANALYSIS_AGENDA_BEFORE_REPORTING_GAPS")
            .contains("EXECUTE_THE_ANALYSIS_TREE_USING_TOTAL_TO_COMPONENT_TO_DRIVER_REASONING")
            .contains("DECLARE_THE_BASELINE_OR_LIMIT_ONLY_BASELINE_DEPENDENT_CLAIMS");
        assertThat(contract.get("analysisMethodologyContract").toString())
            .contains("analysis_methodology.v1", "ESTABLISH_BASELINE", "ATTRIBUTE_CONTRIBUTION")
            .contains("OBJECTIVE_RELEVANCE_X_MATERIALITY_X_CONFIDENCE")
            .contains("EXECUTIVE_SUMMARY", "KEY_DRIVERS", "LIMITATIONS");
        assertThat(contract.get("analysisTree").toString())
            .contains("analysis_tree.v1", "Q0", "MECE_WHERE_POSSIBLE")
            .contains("TOTAL", "COMPONENT", "CONTRIBUTION", "DRIVER", "IMPACT");
    }
}
