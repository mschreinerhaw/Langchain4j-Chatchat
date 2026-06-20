package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceContextFormatterTest {

    private final EvidenceContextFormatter formatter = new EvidenceContextFormatter();

    @Test
    void formatsEvidenceContextAndBindsCitations() {
        DocumentEvidenceChunk chunk = chunk("file-1", 12, 92.5D,
            "Check account status, token expiry, and session validity.");

        DocumentSearchResult result = formatter.toSearchResult(
            "login failed",
            "TROUBLESHOOTING",
            List.of(chunk)
        );
        CitationBoundAnswer answer = formatter.bindAnswer("Check token and session.", result);
        EvidenceAnswer evidenceAnswer = formatter.toEvidenceAnswer("Check token and session.", result.results(), "high", List.of());

        assertThat(result.contractVersion()).isEqualTo("document_evidence_v1");
        assertThat(result.context())
            .contains("[证据1]")
            .contains("引用ID: doc://file-1#chunk=12")
            .contains("文件: ops.pdf")
            .contains("章节: Login Troubleshooting")
            .contains("chunk: 12")
            .contains("类型: troubleshooting")
            .contains("分数: 92.5")
            .contains("内容:")
            .contains("Check account status");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).refId()).isEqualTo("doc://file-1#chunk=12");
        assertThat(result.citations().get(0).fileName()).isEqualTo("ops.pdf");
        assertThat(result.citations().get(0).chunkIndex()).isEqualTo(12);
        assertThat(answer.answer()).isEqualTo("Check token and session.");
        assertThat(answer.citations()).isEqualTo(result.citations());
        assertThat(evidenceAnswer.confidence()).isEqualTo("high");
        assertThat(evidenceAnswer.citations().get(0).refId()).isEqualTo("doc://file-1#chunk=12");
        assertThat(evidenceAnswer.citations().get(0).fileId()).isEqualTo("file-1");
    }

    @Test
    void appliesEvidenceBudgetAndMergesConsecutiveContextChunks() {
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        chunks.add(chunk("file-1", 1, 95.0D, "First login troubleshooting paragraph."));
        chunks.add(chunk("file-1", 2, 94.0D, "Second login troubleshooting paragraph."));
        chunks.add(chunk("file-1", 3, 93.0D, "Third login troubleshooting paragraph."));
        chunks.add(chunk("file-1", 4, 92.0D, "Fourth chunk from same file should be dropped by file budget."));
        chunks.add(chunk("file-2", 1, 10.0D, "Low score chunk should be dropped."));

        List<DocumentEvidenceChunk> selected = formatter.selectEvidence(chunks, 8, 6000, 3, 20.0D);
        String context = formatter.formatContext(selected);

        assertThat(selected).hasSize(3);
        assertThat(selected).extracting(DocumentEvidenceChunk::refId)
            .containsExactly("doc://file-1#chunk=1", "doc://file-1#chunk=2", "doc://file-1#chunk=3");
        assertThat(context)
            .contains("chunk: 1-3")
            .contains("First login troubleshooting paragraph.")
            .contains("Second login troubleshooting paragraph.")
            .doesNotContain("Fourth chunk")
            .doesNotContain("Low score");
    }

    private DocumentEvidenceChunk chunk(String fileId, Integer chunkIndex, Double score, String content) {
        return new DocumentEvidenceChunk(
            "doc://" + fileId + "#chunk=" + chunkIndex,
            fileId + "_" + chunkIndex,
            fileId,
            "ops.pdf",
            "Login Troubleshooting",
            chunkIndex,
            "troubleshooting",
            score,
            content,
            List.of("token", "session"),
            new Citation("ops.pdf", "section: Login Troubleshooting; chunk: " + chunkIndex),
            null,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "tenant",
            List.of()
        );
    }
}
