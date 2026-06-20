package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGroundingGuardTest {

    private final EvidenceContextFormatter formatter = new EvidenceContextFormatter();
    private final AnswerGroundingGuard guard = new AnswerGroundingGuard(new SearchTokenizer(), formatter);

    @Test
    void keepsGroundedAnswerWithKnownCitation() {
        DocumentEvidenceChunk chunk = chunk("token session account status");
        EvidenceAnswer answer = new EvidenceAnswer(
            "Check token, session, and account status.",
            formatter.answerCitations(List.of(chunk)),
            "high",
            List.of()
        );

        EvidenceAnswer guarded = guard.guard(answer, List.of(chunk));

        assertThat(guarded.answer()).isEqualTo(answer.answer());
        assertThat(guarded.confidence()).isEqualTo("high");
        assertThat(guarded.citations()).hasSize(1);
        assertThat(guarded.citations().get(0).fileId()).isEqualTo("file-1");
    }

    @Test
    void downgradesUngroundedAnswer() {
        DocumentEvidenceChunk chunk = chunk("token session account status");
        EvidenceAnswer answer = new EvidenceAnswer(
            "Restart the database cluster.",
            formatter.answerCitations(List.of(chunk)),
            "high",
            List.of()
        );

        EvidenceAnswer guarded = guard.guard(answer, List.of(chunk));

        assertThat(guarded.answer()).contains("证据不足");
        assertThat(guarded.confidence()).isEqualTo("low");
        assertThat(guarded.citations()).isEmpty();
        assertThat(guarded.missingInfo()).contains("answer conclusion is not grounded in evidence content");
    }

    private DocumentEvidenceChunk chunk(String content) {
        return new DocumentEvidenceChunk(
            "doc://file-1#chunk=12",
            "file-1_12",
            "file-1",
            "ops.pdf",
            "Login Troubleshooting",
            12,
            "troubleshooting",
            92.5D,
            content,
            List.of("token", "session"),
            new Citation("ops.pdf", "section: Login Troubleshooting; chunk: 12"),
            null,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "tenant",
            List.of()
        );
    }
}
