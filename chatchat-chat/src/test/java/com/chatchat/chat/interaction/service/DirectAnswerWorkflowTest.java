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
