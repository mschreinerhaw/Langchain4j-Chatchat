package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutionModelsTest {
    @Test void usesSameProviderWithoutCallerTokenCostOrDeadlineAccounting() {
        var provider=mock(ChatModel.class);
        when(provider.chat(anyString())).thenReturn("computed");
        var usage=new LinkedHashMap<String,Object>();
        var parent=new MeteredChatModel(new DeadlineAwareChatModel(provider,()->0),usage,1,0.001,1,1,0.8);
        assertThat(ToolExecutionModels.independent(parent,()->10_000).chat("long coding prompt")).isEqualTo("computed");
        assertThat(usage.get("invocations")).isEqualTo(0L);
        assertThatThrownBy(()->parent.chat("long coding prompt")).isInstanceOf(AgentBudgetExceededException.class);
        verify(provider,times(1)).chat(anyString());
    }
    @Test void retainsIndependentToolDeadline() {
        var provider=mock(ChatModel.class);
        assertThatThrownBy(()->ToolExecutionModels.independent(provider,()->0).chat("code"))
            .isInstanceOf(AgentDeadlineExceededException.class);
        verifyNoInteractions(provider);
    }
}
