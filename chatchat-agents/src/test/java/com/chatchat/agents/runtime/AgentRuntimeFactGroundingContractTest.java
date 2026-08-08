package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeFactGroundingContractTest {

    @Test
    void exposesCanonicalFactBoundaryAndAllEnforcementStages() {
        Map<String, Object> contract = AgentRuntimeFactGroundingContract.metadata();

        assertThat(contract)
            .containsEntry("contractVersion", "agent_runtime_fact_grounding_v1")
            .containsEntry("factAuthority", "TOOL_STRUCTURED_OUTPUT")
            .containsEntry("modelRole", "INTERPRET_AND_SUMMARIZE_WITHIN_FACT_BOUNDARY")
            .containsEntry("runtimeRole", "PRESERVE_VALIDATE_AND_REWRITE_ON_FACT_MUTATION");
        assertThat(contract.get("enforcementStages")).isEqualTo(List.of(
            "planning", "tool_result_review", "final_synthesis", "answer_review"
        ));
        assertThat(AgentRuntimeFactGroundingContract.promptSection())
            .contains("immutable fact boundary")
            .contains("must not add, rename, replace")
            .contains("Never present illustrative/manual SQL")
            .contains("non-executed draft for human review")
            .contains("Never relabel toolName as displayName")
            .contains("Partial-result presentation contract")
            .contains("Template child failures are isolated execution results")
            .contains("Preserve failure identity exactly")
            .contains("DATA_RETURNED, EMPTY_RESULT, NOT_EXECUTED, BLOCKED, and FAILED")
            .contains("Convert YYYYMMDD to YYYY-MM-DD without changing any digit")
            .contains("do not replace the requested report with an API inventory")
            .contains("rewrite it from original tool evidence");
    }
}
