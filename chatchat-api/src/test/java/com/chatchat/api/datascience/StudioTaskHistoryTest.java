package com.chatchat.api.datascience;

import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StudioTaskHistoryTest {
    @ParameterizedTest @ValueSource(strings={"sql","python"})
    void persistsBothTurnsCodeProvenanceAndChecksWithoutClaimingExecution(String language){
        var conversations=mock(ConversationService.class);
        when(conversations.getConversation(anyString(),anyString())).thenReturn(Optional.empty());
        var history=new StudioTaskHistory(conversations);
        var scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        var outcome=mock(CodingTaskOutcome.class);when(outcome.status()).thenReturn(CodingTaskOutcome.Status.COMPLETED);
        var request=new StudioTaskHistory.Request(null,language,"Generate a report","configured",Map.of());
        String task="python-coding-"+"a".repeat(64);
        history.project(scope,task,request,outcome,Map.of("files",List.of(Map.of("path","main."+language,
            "content","source-from-cline")),"notes","Cline notes"),List.of(Map.of("type","MODEL_CALL_COMPLETED")));
        var turns=ArgumentCaptor.forClass(List.class);
        verify(conversations).mergeMessages(eq("tenant"),anyString(),eq("owner"),turns.capture());
        var messages=(List<Conversation.Message>)turns.getValue();
        assertThat(messages).extracting(Conversation.Message::getRole).containsExactly("user","assistant");
        assertThat(messages.get(1).getContent()).contains("source-from-cline","Cline notes","未执行");
        assertThat(messages.get(1).getTaskId()).isEqualTo(task);
        assertThat(messages).extracting(Conversation.Message::getId).doesNotHaveDuplicates()
            .allSatisfy(id->assertThat(id).hasSize(36));
        assertThat(messages.get(1).getStatus()).isEqualTo("completed");
        assertThat(messages.get(1).getTraces()).hasSize(1);
        verify(conversations).updateConversationSummary(eq("tenant"),anyString(),eq("owner"),contains("Studio"),
            eq("completed"),isNull(),eq("configured"),eq("llm_chat"),contains("Studio"));
    }
    @ParameterizedTest @ValueSource(strings={"sql","python"})
    void existingConversationCannotBeAssignedToAnotherOwner(String language){
        var conversations=mock(ConversationService.class);
        var foreign=Conversation.builder().id("session").userId("other").tenantId("tenant").build();
        when(conversations.getConversation("tenant","session")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(()->new StudioTaskHistory(conversations).project(
            new KernelDataScope("tenant","owner",null,null,"run",null,Map.of()),"task",
            new StudioTaskHistory.Request("session",language,"question","model",Map.of()),null,Map.of(),List.of()))
            .isInstanceOf(SecurityException.class);
        verify(conversations,never()).mergeMessages(anyString(),anyString(),anyString(),anyList());
    }
}
