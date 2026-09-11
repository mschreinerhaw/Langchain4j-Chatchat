package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisObjectiveContractCompilerTest {

    @Test
    void derivesAgendaFromMaintainedIntentWithoutInstallingRuntimeMethodology() {
        Map<String, Object> intent = Map.of(
            "metrics", List.of("asset scale", "profit and loss"),
            "dimensions", List.of("security", "date"),
            "analysisFocus", List.of("trading behavior"),
            "expectedRelationships", List.of("asset structure and trading behavior"));
        Map<String, Object> context = Map.of("workerAnalysisContext", Map.of(
            "businessIntent", intent,
            "currentTemplate", Map.of(
                "templateId", "dynamic-template",
                "analysisRole", "provide one part of the customer analysis",
                "matchedQuestionAspects", List.of("current assets and profit"))));

        Map<String, Object> contract = new AnalysisObjectiveContractCompiler().compile(
            "analyze customer assets, profit and trading preference",
            new DataAnalysisPosition("dataset-a", 1, 1, 1, 20, 20), context);

        assertThat(contract.get("analysisAgenda").toString())
            .contains("dynamic_analysis_agenda.v1", "AGENT_AND_REQUEST_CONTEXT")
            .contains("USER_OBJECTIVE", "DECLARED_FOCUS", "DECLARED_RELATIONSHIP")
            .contains("asset scale", "trading behavior", "asset structure and trading behavior");
        assertThat(contract.get("workerObligations").toString())
            .contains("BIND_ANALYSIS_ARTIFACTS_TO_SOURCE_REFERENCES")
            .contains("APPLY_ONLY_CONFIGURED_AGENT_ANALYSIS_POLICY");
        assertThat(contract).doesNotContainKeys(
            "analysisMethodologyContract", "professionalAnalysisContract",
            "professionalAnalysisDepthContract");
        assertThat(contract.get("analysisTree").toString())
            .contains("analysis_tree.v1", "Q0")
            .doesNotContain("MECE_WHERE_POSSIBLE", "TOTAL", "COMPONENT", "CONTRIBUTION");
    }

    @Test
    void passesThroughExplicitAgentMethodology() {
        Map<String, Object> configured = Map.of("schemaVersion", "agent_methodology.v1",
            "style", "scenario exploration");
        Map<String, Object> context = Map.of("workerAnalysisContext", Map.of(
            "analysisMethodologyContract", configured));

        Map<String, Object> contract = new AnalysisObjectiveContractCompiler().compile(
            "analyze the returned evidence",
            new DataAnalysisPosition("dataset-a", 1, 1, 1, 1, 1), context);

        assertThat(contract.get("analysisMethodologyContract")).isEqualTo(configured);
    }
}
