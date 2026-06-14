package com.chatchat.agents.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentAnswerReviewerTest {

    @Test
    void revisesAnswerWhenReviewerReturnsRejectedPayload() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            "```json\n{\"accepted\":false,\"feedback\":\"Missing concrete steps\",\"revisedAnswer\":\"Create the database, import schema.sql, update config, then restart.\"}\n```"
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "How do I initialize the database?",
            "Be direct.",
            List.of("Document evidence snippets: schema.sql is required."),
            "Please check the deployment document."
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.REVISED);
        assertThat(review.answer()).contains("schema.sql");
        assertThat(review.feedback()).isEqualTo("Missing concrete steps");
        assertThat(chatModel.messages().get(0))
            .contains("final answer quality reviewer")
            .contains("Document evidence snippets");
    }

    @Test
    void acceptsAnswerWhenReviewerPayloadAcceptsIt() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            "{\"accepted\":true,\"feedback\":\"Direct answer\",\"revisedAnswer\":\"\"}"
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "What happened?",
            null,
            List.of(),
            "The operation completed successfully."
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.ACCEPTED);
        assertThat(review.answer()).isEqualTo("The operation completed successfully.");
        assertThat(review.feedback()).isEqualTo("Direct answer");
    }

    @Test
    void rejectsAnswerWhenReviewerRejectsWithoutRevision() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            "{\"accepted\":false,\"feedback\":\"No concrete evidence was produced\",\"revisedAnswer\":\"\"}"
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "Find the latest company update.",
            null,
            List.of("web_search failed"),
            "Unable to provide the latest update."
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.REJECTED);
        assertThat(review.answer()).isBlank();
        assertThat(review.feedback()).isEqualTo("No concrete evidence was produced");
    }

    private static final class QueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<String> messages = new java.util.ArrayList<>();

        private QueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            messages.add(message);
            return responses.remove();
        }

        private List<String> messages() {
            return messages;
        }
    }
}
