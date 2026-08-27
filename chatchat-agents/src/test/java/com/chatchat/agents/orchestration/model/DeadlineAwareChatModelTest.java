package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.orchestration.model.DeadlineAwareChatModel;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineAwareChatModelTest {

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
}
