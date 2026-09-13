package com.chatchat.agents.runtime.observation;


import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeFactGroundingContractTest {

    @Test
    void exposesCanonicalDataSupplyBoundaryWithoutAnswerEnforcement() {
        Map<String, Object> contract = AgentRuntimeFactGroundingContract.metadata();

        assertThat(contract)
            .containsEntry("contractVersion", "agent_runtime_fact_grounding_v1")
            .containsEntry("factAuthority", "TOOL_STRUCTURED_OUTPUT")
            .containsEntry("modelRole", "OWNS_ANALYSIS_REASONING_AND_REPORT")
            .containsEntry("runtimeRole", "SUPPLY_SCOPED_COMPLETE_TRACEABLE_DATA")
            .containsEntry("onViolation", "REPORT_DATA_SUPPLY_OR_PROVENANCE_FAILURE");
        assertThat(contract.get("enforcementStages")).isEqualTo(List.of(
            "planning", "tool_execution", "evidence_delivery"
        ));
        assertThat(AgentRuntimeFactGroundingContract.promptSection())
            .contains("immutable boundary for claims presented as retrieved facts")
            .contains("must not add, rename, replace")
            .contains("not a task checklist")
            .contains("never expand the answer, hypothesis set, or follow-up plan")
            .contains("Never present illustrative/manual SQL")
            .contains("non-executed draft for human review")
            .contains("Never relabel toolName as displayName")
            .contains("Partial-result presentation contract")
            .contains("Truncated-preview contract")
            .contains("DATA_RETURNED_PARTIAL")
            .contains("Never replace visible-data analysis")
            .contains("Current-turn evidence contract")
            .contains("Template child failures are isolated execution results")
            .contains("Preserve failure identity exactly")
            .contains("DATA_RETURNED, EMPTY_RESULT, NOT_EXECUTED, BLOCKED, and FAILED")
            .contains("Convert YYYYMMDD to YYYY-MM-DD without changing any digit")
            .contains("do not replace the requested report with an API inventory")
            .contains("does not review, score, censor, qualify, supplement or rewrite");
    }
}
