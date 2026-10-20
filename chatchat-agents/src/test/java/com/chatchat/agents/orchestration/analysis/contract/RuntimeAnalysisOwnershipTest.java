package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.agents.orchestration.analysis.graph.ModelNativeAnalysisHarness;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RuntimeAnalysisOwnershipTest {
    @Test void compatibilityHarnessCannotReenterDataExplorationAfterCodingHandoff() {
        ChatModel model = mock(ChatModel.class);
        for (Map<String, Object> metadata : List.<Map<String, Object>>of(
                Map.of("workflowFamily", "DATA_ANALYSIS"), Map.of("taskType", "DATA_ANALYSIS"),
                Map.of("codingOwnedAnalysisActive", true),
                Map.of("analysisExecutionMode", "CODING_OWNED_DATA_ANALYSIS"))) {
            assertThatThrownBy(() -> new ModelNativeAnalysisHarness(4).execute("Question", List.of(), model,
                GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
                AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CODING_ANALYSIS_REQUIRED");
        }
        verifyNoInteractions(model);
    }
}
