package com.chatchat.chat.interaction.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionOrchestrationServiceTest {

    @Test
    void recognizesUserQuestionAlreadyPersistedByConversationSnapshot() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("assistant", "上一轮回答", 1L, Map.of()),
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 2L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "  分析客户年化收益率  "
        )).isTrue();
    }

    @Test
    void allowsNewQuestionWhenLatestPersistedMessageIsAnAssistantAnswer() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 1L, Map.of()),
            new ConversationMemoryService.MessageSnapshot("assistant", "分析结果", 2L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "分析客户年化收益率"
        )).isFalse();
    }

    @Test
    void allowsDifferentQuestion() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 1L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "分析客户累计收益率"
        )).isFalse();
    }
}
