package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingSemanticAdmissionTest {
    @Test void validExecutionCannotAdmitAnUnsupportedBroaderClaim() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"status":"UNSUPPORTED","reason":"A single observation does not support a broad conclusion","unsupportedFields":["claim"]}
            """);
        var result = review(model,Map.of("count",0,"claim","none exists"));
        assertEquals("UNSUPPORTED",result.get("status"));
    }
    @Test void legitimateOriginalTextAnalysisIsReviewedInsteadOfRejectedByLength() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"Original analysis scoped to observed text\",\"unsupportedFields\":[]}");
        assertEquals("SUPPORTED",review(model,Map.of("analysis","Original text findings. ".repeat(300))).get("status"));
    }
    @Test void invalidReviewerDecisionFailsClosed() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"yes\",\"unsupportedFields\":[\"claim\"]}");
        assertEquals("INCONCLUSIVE",review(model,Map.of("claim","value")).get("status"));
    }
    private Map<String,Object> review(ChatModel model,Map<String,Object> row) {
        var checkpoints = mock(RuntimeExecutionCheckpointPort.class);
        when(checkpoints.readExecutionCheckpoint(any(),anyString())).thenReturn(Optional.empty());
        when(checkpoints.compareAndSetExecutionCheckpoint(any(),anyString(),isNull(),anyString())).thenReturn(true);
        var scope = new KernelDataScope("tenant","owner","request","conversation","run",null,Map.of());
        return new CodingSemanticAdmission(new ObjectMapper()).review(model,"Compute and explain", "import json\n# executed program", "Worker conclusion", new Dataset("computed",Map.of(),List.of(row)),
            Map.of("schemaJson","{}"),scope,"task",checkpoints,()->{});
    }
}
