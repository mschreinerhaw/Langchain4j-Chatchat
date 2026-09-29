package com.chatchat.chat.interaction.service;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.WorkflowOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DirectAnswerWorkflowTest {
    @Test void modelResultCannotOverrideCancellation() {
        var model = mock(ChatModel.class);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(model.chat(anyString())).thenAnswer(invocation -> { cancelled.set(true); return "late result"; });
        var request = InteractionRequest.builder().query("hello").toolInput(java.util.Map.of(
            "__agentCancellation", (java.util.function.BooleanSupplier) cancelled::get)).build();
        var workflow = new DirectAnswerWorkflow(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.execute(request,
            InteractionContext.builder().build(), mock(SkillDefinition.class)))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
    @Test void answersWithoutToolRuntimeAndReportsExplicitOutcome() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("你好！");
        var models = mock(ConfigurableChatModelFactory.class);
        var workflow = new DirectAnswerWorkflow(model, models, new ObjectMapper());
        var response = workflow.execute(InteractionRequest.builder().query("你好").build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(response.getAnswer()).isEqualTo("你好！");
        assertThat(response.getToolTraces()).isEmpty();
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(models);
    }

    @Test void emptyModelAnswerIsAFailure() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(" ");
        var response = new DirectAnswerWorkflow(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper())
            .execute(InteractionRequest.builder().query("你好").build(), InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("FAILED");
    }
}
