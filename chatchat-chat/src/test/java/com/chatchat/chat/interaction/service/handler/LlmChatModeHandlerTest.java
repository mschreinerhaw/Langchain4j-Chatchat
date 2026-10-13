package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionRequest;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmChatModeHandlerTest {
    private final ChatModel defaultModel = mock(ChatModel.class);
    private final ConfigurableChatModelFactory models = mock(ConfigurableChatModelFactory.class);
    private final LlmChatModeHandler handler = new LlmChatModeHandler(defaultModel, models);
    private final InteractionContext context = InteractionContext.builder().history(List.of()).build();
    private InteractionRequest request(String selected) {
        var request = new InteractionRequest(); request.setModelName(selected); request.setQuery("Generate SQL");
        request.setSystemPrompt("Return sql-studio.output.v1 JSON"); return request;
    }
    @Test void selectedModelReceivesTheEngineeringContract() {
        var selected = mock(ChatModel.class); when(models.create("chosen")).thenReturn(selected);
        when(selected.chat(anyString())).thenReturn("{\"files\":[]}");
        assertThat(handler.handle(request("chosen"), context).getAnswer()).isEqualTo("{\"files\":[]}");
        verify(selected).chat(contains("sql-studio.output.v1")); verifyNoInteractions(defaultModel);
    }
    @Test void blankModelUsesTheConfiguredDefaultWithoutSelectingAnAgent() {
        when(defaultModel.chat(anyString())).thenReturn("answer");
        assertThat(handler.handle(request(""), context).getAnswer()).isEqualTo("answer");
        verifyNoInteractions(models);
    }
    @Test void cancelledRequestsDoNotCallTheModelOrDeliverLateAnswers() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        var request = request(""); request.setToolInput(Map.of("__agentCancellation", (BooleanSupplier)cancelled::get));
        assertThatThrownBy(() -> handler.handle(request, context)).isInstanceOf(CancellationException.class);
        verifyNoInteractions(defaultModel, models);
        cancelled.set(false);
        when(defaultModel.chat(anyString())).thenAnswer(call -> { cancelled.set(true); return "late answer"; });
        assertThatThrownBy(() -> handler.handle(request, context)).isInstanceOf(CancellationException.class);
    }
}
