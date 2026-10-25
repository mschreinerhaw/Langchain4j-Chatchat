package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.orchestration.model.DeadlineAwareChatModel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineAwareChatModelTest {
    @Test void reviewBudgetBoundsAnOtherwiseUnlimitedTransportAndRestoresCallerScope() {
        DeadlineAwareChatModel model=new DeadlineAwareChatModel(new ChatModel(){
            @Override public String chat(String message) {
                if("slow".equals(message))try{Thread.sleep(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                return "ok";
            }
        },()->-1L);
        try(var budget=ModelInvocationBudget.open(30)) {
            assertThatThrownBy(()->model.chat("slow")).isInstanceOf(ModelPhaseBudgetExceededException.class)
                .hasMessageContaining("MODEL_REVIEW_DEADLINE_EXCEEDED");
        }
        assertThat(model.chat("fast")).isEqualTo("ok");
    }
    @Test void nativeReviewCallsAlsoRespectThePhaseBudget() {
        var model=new DeadlineAwareChatModel(new ChatModel(){
            @Override public ChatResponse doChat(ChatRequest request){
                try{Thread.sleep(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                return ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("late")).build();
            }
        },()->-1L);
        try(var budget=ModelInvocationBudget.open(30)) {
            assertThatThrownBy(()->model.chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("slow")).build()))
                .isInstanceOf(ModelPhaseBudgetExceededException.class);
        }
    }

    @Test
    void interruptsModelInvocationWhenRequestDeadlineExpires() {
        AtomicBoolean interrupted = new AtomicBoolean();
        ChatModel delegate = new ChatModel() {
            @Override
            public String chat(String message) {
                try {
                    Thread.sleep(5_000L);
                    return "late";
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
        };
        DeadlineAwareChatModel model = new DeadlineAwareChatModel(delegate, () -> 25L);

        assertThatThrownBy(() -> model.chat("test"))
            .isInstanceOf(AgentDeadlineExceededException.class)
            .hasMessageContaining("time budget exhausted");
        org.assertj.core.api.Assertions.assertThatCode(() -> Thread.sleep(25L)).doesNotThrowAnyException();
        assertThat(interrupted).isTrue();
    }

    @Test
    void delegatesWithoutExecutorWhenNoDeadlineExists() {
        DeadlineAwareChatModel model = new DeadlineAwareChatModel(new ChatModel() {
            @Override
            public String chat(String message) {
                return "ok";
            }
        }, () -> -1L);
        assertThat(model.chat("test")).isEqualTo("ok");
    }

    @Test
    void forwardsNativeChatRequestThroughDeadlineBoundary() {
        DeadlineAwareChatModel model = new DeadlineAwareChatModel(new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("ok"))
                    .build();
            }
        }, () -> 1_000L);

        ChatResponse response = model.chat(ChatRequest.builder()
            .messages(java.util.List.of(dev.langchain4j.data.message.UserMessage.from("test")))
            .build());

        assertThat(response.aiMessage().text()).isEqualTo("ok");
    }
}
