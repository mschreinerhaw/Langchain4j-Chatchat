package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutionModelsTest {
    @Test void usesSameProviderWithoutCallerTokenCostAccounting() {
        var provider=mock(ChatModel.class);
        when(provider.chat(anyString())).thenReturn("computed");
        var usage=new LinkedHashMap<String,Object>();
        var parent=new MeteredChatModel(new DeadlineAwareChatModel(provider,()->10_000),usage,1,0.001,1,1,0.8);
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
    @Test void flatteningWrappersCannotRenewParentDeadlineOrRemoveCancellation() {
        var provider=mock(ChatModel.class);
        var expired=new DeadlineAwareChatModel(provider,()->0);
        assertThatThrownBy(()->ToolExecutionModels.independent(expired,()->10_000).chat("code"))
            .isInstanceOf(AgentDeadlineExceededException.class);
        var cancelled=new DeadlineAwareChatModel(provider,()->10_000,()->true,0);
        assertThatThrownBy(()->ToolExecutionModels.independent(cancelled,()->10_000).chat("code"))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(provider);
    }
    @Test void flatteningRetainsTheStricterInheritedPerCallCap() {
        ChatModel provider=new ChatModel(){
            @Override public String chat(String message){
                try { Thread.sleep(5000); return "late"; }
                catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
            }
        };
        var parent=new DeadlineAwareChatModel(provider,()->10_000,()->false,20);
        assertThatThrownBy(()->ToolExecutionModels.independent(parent,()->10_000,()->false,1000).chat("code"))
            .isInstanceOf(AgentDeadlineExceededException.class);
    }
}
