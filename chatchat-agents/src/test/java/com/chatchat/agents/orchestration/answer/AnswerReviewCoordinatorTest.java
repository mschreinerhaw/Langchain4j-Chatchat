package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AnswerReviewCoordinatorTest {

    @Test
    void governedReportRejectsReviewerRewriteThatDiscardsMostAnalysis() {
        AgentAnswerReviewer reviewer = (model, query, prompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.REVISED,
                "# Short report\n\nA newly asserted asset fact.", "Shortened by reviewer");
        AnswerReviewCoordinator coordinator = new AnswerReviewCoordinator(
            reviewer, new AnswerEvidenceLedgerCompiler(), 0);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentRunId", "run-review-retention");
        metadata.put("analysisReportContract", Map.of("reportType", "DRIVER_REPORT"));
        String original = "# Full report\n\n" + "Evidence, metrics, validation, patterns, risks and actions.\n".repeat(20);

        AgentAnswerReview result = coordinator.review(mock(ChatModel.class), "analyze", null,
            List.of("executed evidence"), original, metadata);

        assertThat(result.status()).isEqualTo(AgentAnswerReview.ACCEPTED);
        assertThat(result.answer()).isEqualTo(original);
        assertThat(metadata)
            .containsEntry("answerReviewRewriteRejected", true)
            .containsEntry("answerReviewRewriteRejectedReason", "destructive_report_shortening")
            .containsEntry("answerReviewFallback", "accepted_current_answer");
    }
}
