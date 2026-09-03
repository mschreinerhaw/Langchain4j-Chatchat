package com.chatchat.agents.orchestration.answer;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultPresentationServiceTest {

    @Test
    void batchEvidenceAllowsResultWithoutTemplateIdentity() throws Exception {
        Map<String, Object> output = Map.of(
            "batchId", "batch-1",
            "status", "SUCCESS",
            "results", List.of(Map.of(
                "callId", "call-1",
                "status", "SUCCESS",
                "evidenceUsable", true,
                "output", Map.of("data", Map.of(
                    "columns", List.of("Variable_name", "Value"),
                    "rows", List.of(Map.of(
                        "Variable_name", "Innodb_buffer_pool_pages_free",
                        "Value", "8192")))))));
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("sql_query_execute")
            .displayName("InnoDB diagnostics")
            .success(true)
            .output(new ObjectMapper().writeValueAsString(output))
            .build();

        List<Map<String, Object>> evidence =
            new AgentResultPresentationService(new ObjectMapper())
                .toolResultEvidence(List.of(trace));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0))
            .containsEntry("evidenceType", "result_set_batch")
            .containsEntry("resultSetCount", 1)
            .containsEntry("usableResultSetCount", 1);
        assertThat(evidence.get(0)).doesNotContainKey("templateIds");
    }
}
