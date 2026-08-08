package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCompressionGateTest {

    @Test
    void boundsAndDeduplicatesOversizedEvidenceWithoutLosingRepairSignals() {
        EvidenceCompressionGate gate = new EvidenceCompressionGate(new ObjectMapper());
        String repeatedObservation = "stepId=3 tool=enterprise_metadata_search success=true "
            + "provider-payload-".repeat(100_000)
            + " missingEvidence=physical metadata nextAction=sql_metadata_search";
        List<String> observations = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            observations.add(repeatedObservation);
        }
        Map<String, Object> historyItem = new LinkedHashMap<>();
        historyItem.put("evidenceId", "iteration:1:step:3");
        historyItem.put("stepId", 3);
        historyItem.put("toolName", "future_metadata_search");
        historyItem.put("success", true);
        historyItem.put("missingEvidence", List.of("physical metadata", "partition definition"));
        historyItem.put("conflicts", List.of("coverage incomplete"));
        historyItem.put("nextActions", List.of(Map.of(
            "tool", "future_schema_search",
            "reason", "close recorded evidence gap"
        )));
        historyItem.put("rawPayload", "raw-history-".repeat(100_000));

        EvidenceCompressionGate.CompressionResult result = gate.compress(
            observations,
            List.of(historyItem)
        );

        assertThat(result.observations()).hasSizeLessThanOrEqualTo(EvidenceCompressionGate.OBSERVATION_BUDGET_CHARS);
        assertThat(result.evidenceHistory()).hasSizeLessThanOrEqualTo(EvidenceCompressionGate.HISTORY_BUDGET_CHARS);
        assertThat(result.observations()).contains("evidenceRef", "missingEvidence=physical metadata", "nextAction=sql_metadata_search");
        assertThat(result.evidenceHistory()).contains(
            "iteration:1:step:3", "future_metadata_search", "missingEvidence", "conflicts", "nextActions");
        assertThat(result.metadata())
            .containsEntry("contractVersion", EvidenceCompressionGate.CONTRACT_VERSION)
            .containsEntry("applied", true)
            .containsEntry("observationCount", 40)
            .containsEntry("selectedObservationCount", 1)
            .containsEntry("omittedObservationCount", 39)
            .containsEntry("fullEvidenceRetainedByRuntime", true);
        assertThat((Double) result.metadata().get("compressionRatio")).isLessThan(0.01);
    }
}
