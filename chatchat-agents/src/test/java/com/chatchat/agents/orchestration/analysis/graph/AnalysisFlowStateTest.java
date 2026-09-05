package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AnalysisFlowStateTest {
    @Test void roundTripsControlStateAndRetainsRouting() throws Exception {
        for (Decision decision : Decision.values()) {
            var state = new AnalysisFlowState(decision, 2, false, "");
            var mapper = new ObjectMapper();
            Map<String,Object> restored = mapper.readValue(mapper.writeValueAsString(Map.of(AnalysisFlowState.KEY, state.toMap())), Map.class);
            assertThat(AnalysisFlowState.read(restored)).isEqualTo(state);
            assertThat(AnalysisFlowState.read(restored).admission()).isEqualTo(state.admission());
        }
    }
    @Test void noDefaultSuccessForUnknownStateOrClosedRetrieval() {
        assertThatThrownBy(() -> new AnalysisFlowState(Decision.RETRIEVE_MORE, 1, true, "budget"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisFlowState.read(Map.of(AnalysisFlowState.KEY, Map.of("schemaVersion", "unknown"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(AnalysisFlowState.read(Map.of())).isNull();
    }
}
