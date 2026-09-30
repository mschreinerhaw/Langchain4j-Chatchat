package com.chatchat.agents.runtime.federation;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class EvidenceBoundAgentExecutionTest {
    private final String valid = "{\"status\":\"COMPLETED\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"Observed fact\",\"evidenceIds\":[\"e1\"],\"confidence\":0.8}],\"limitations\":[]}";
    private AgentRunRequest request() {
        return AgentRunRequest.builder().query("Review supplied evidence").modelName("agent-model")
            .availableTools(List.of()).boundDocumentIds(List.of()).attributes(Map.of(
                EvidenceBoundAgentExecution.OUTPUT_CONTRACT, AgentExecutionOutcome.SCHEMA_VERSION,
                "evidenceBundle", new EvidenceBundle(null, List.of(new ProjectedAnalysisEvidence("e1",
                    AnalysisCapability.TOOL_CALL, "Observed fact", Map.of())), List.of(), Map.of()))).build();
    }
    private ChatResponse response(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    @Test void preservesStructuredAnswerWithoutReportSynthesis() {
        var model = mock(ChatModel.class); when(model.chat(any(ChatRequest.class))).thenReturn(response(valid));
        var result = EvidenceBoundAgentExecution.execute(request(), model, () -> false);
        assertThat(result.answer()).isEqualTo(valid);
        assertThat(result.metadata()).containsEntry("modelName", "agent-model").containsEntry("modelCalls", 1);
        verify(model, times(1)).chat(any(ChatRequest.class));
    }
    @Test void repairsFormatInSameConversationAndRejectsInventedReferences() {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response("# Report"), response(valid));
        assertThat(EvidenceBoundAgentExecution.execute(request(), model, () -> false).answer()).isEqualTo(valid);
        var capture = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(capture.capture());
        assertThat(capture.getAllValues().get(1).messages()).hasSize(4);
        reset(model); when(model.chat(any(ChatRequest.class))).thenReturn(response(valid.replace("e1", "forged")));
        assertThatThrownBy(() -> EvidenceBoundAgentExecution.execute(request(), model, () -> false))
            .isInstanceOf(IllegalStateException.class).hasMessage("AGENT_OUTPUT_CONTRACT_INVALID");
    }
    @Test void cancellationAndAcquisitionCapabilitiesCannotEnterChildExecution() {
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> EvidenceBoundAgentExecution.execute(request(), model, () -> true))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        var request = request(); request.setAvailableTools(List.of("unapproved"));
        assertThatThrownBy(() -> EvidenceBoundAgentExecution.execute(request, model, () -> false))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }
}
