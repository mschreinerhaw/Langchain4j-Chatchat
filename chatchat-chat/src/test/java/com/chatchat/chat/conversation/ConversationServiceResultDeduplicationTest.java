package com.chatchat.chat.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceResultDeduplicationTest {

    @Test
    void keepsTheRicherOfTwoAdjacentEquivalentAssistantResults() {
        Conversation.Message user = Conversation.Message.builder()
            .id("user-1")
            .role("user")
            .content("分析最新融资融券数据")
            .build();
        Conversation.Message richAnswer = Conversation.Message.builder()
            .id("assistant-runtime")
            .role("assistant")
            .content("## 分析结论\n\n融资余额上升。")
            .steps(List.of(Map.of("type", "TOOL_RESULT")))
            .traces(List.of(Map.of("toolName", "margin_query")))
            .visualizationSpec(Map.of("type", "table"))
            .taskId("task-1")
            .build();
        Conversation.Message plainDuplicate = Conversation.Message.builder()
            .id("assistant-memory")
            .role("assistant")
            .content("## 分析结论\n融资余额上升。")
            .build();

        List<Conversation.Message> result = ConversationService.collapseDuplicateAssistantResults(
            List.of(user, richAnswer, plainDuplicate)
        );

        assertThat(result).containsExactly(user, richAnswer);
    }

    @Test
    void doesNotCollapseAssistantResultsSeparatedByAUserTurn() {
        Conversation.Message answer1 = message("assistant-1", "assistant", "相同回答");
        Conversation.Message user = message("user-2", "user", "请再确认一次");
        Conversation.Message answer2 = message("assistant-2", "assistant", "相同回答");

        assertThat(ConversationService.collapseDuplicateAssistantResults(List.of(answer1, user, answer2)))
            .containsExactly(answer1, user, answer2);
    }

    private Conversation.Message message(String id, String role, String content) {
        return Conversation.Message.builder().id(id).role(role).content(content).build();
    }
}
