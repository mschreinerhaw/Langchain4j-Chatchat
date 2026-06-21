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

    @Test
    void blocksReviewerDowngradeWhenCanonicalEvidenceHasContent() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            """
                {"accepted":false,
                 "feedback":"Observations do not contain actual content",
                 "revisedAnswer":"Unable to get the SQL content from tool output."}
                """
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "Show the SQL",
            null,
            List.of("""
                Canonical evidence store (contractVersion=evidence_canonical_v1):
                [CanonicalEvidence 1]
                evidenceId: evidence:1
                type: SQL
                sourceRef: doc://file-1#chunk=0
                trustLevel: high
                rawContent:
                select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                normalizedContent:
                select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                """),
            "SQL: select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i"
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.ACCEPTED);
        assertThat(review.answer()).contains("select * from gdp_ads");
        assertThat(review.feedback()).contains("Reviewer downgrade blocked");
    }

    @Test
    void blocksReviewerDowngradeWhenEvidenceGraphHasTrustedSqlPath() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            """
                {"accepted":false,
                 "feedback":"Observations do not contain actual content",
                 "revisedAnswer":"Unable to get the SQL content from tool output."}
                """
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "Show the SQL lineage",
            null,
            List.of("""
                Evidence graph execution (contractVersion=evidence_graph_v1):
                queryId: tool:document_search
                nodeCount: 4
                edgeCount: 3
                sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                Nodes:
                [Node evidence:1:sql_trusted]
                type: TRUSTED_SQL
                sourceRef: doc://file-1#chunk=0
                confidence: 0.99
                contentPreview: select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                Valid evidence paths:
                [Path 1]
                nodes: evidence:1:chunk -> evidence:1:sql_fragment -> evidence:1:sql_normalized -> evidence:1:sql_trusted
                score: 0.88
                sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                """),
            "SQL lineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i"
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.ACCEPTED);
        assertThat(review.answer()).contains("gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void blocksReviewerDowngradeWhenEvidenceOsAllowsAnswer() {
        DefaultAgentAnswerReviewer reviewer = new DefaultAgentAnswerReviewer(new ObjectMapper());
        QueueChatModel chatModel = new QueueChatModel(
            """
                {"accepted":false,
                 "feedback":"Observations do not contain actual content",
                 "revisedAnswer":"Unable to get the SQL content from tool output."}
                """
        );

        AgentAnswerReview review = reviewer.review(
            chatModel,
            "Show the SQL lineage",
            null,
            List.of("""
                Evidence OS execution (contractVersion=evidence_os_execution_v2):
                decision: ANSWER_ALLOWED
                answerContract: evidence_answer_contract_v2
                fromGraphOnly: true
                executable: true
                evidencePath: evidence:1:chunk -> evidence:1:sql_fragment -> evidence:1:sql_normalized -> evidence:1:sql_trusted
                sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i
                runtimeRules: answer must be derived from evidencePath; no external generation; no SQL answer unless EXECUTION_VERIFIED.
                """),
            "SQL lineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i"
        );

        assertThat(review.status()).isEqualTo(AgentAnswerReview.ACCEPTED);
        assertThat(review.answer()).contains("gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
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
