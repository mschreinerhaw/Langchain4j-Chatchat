package com.chatchat.agents.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceNormalizerTest {

    private final EvidenceNormalizer normalizer = new EvidenceNormalizer();

    @Test
    void normalizesDocumentEvidenceContract() {
        Map<String, Object> payload = Map.of(
            "contractVersion", "document_evidence_v1",
            "requestContext", Map.of("tenantId", "tenant-a", "userId", "alice", "roles", List.of("ops")),
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "ops.pdf",
                "section", "login",
                "chunkIndex", 12,
                "content", "check token and session",
                "score", 92.5
            ))
        );

        List<EvidenceChunk> chunks = normalizer.normalize("document_search", payload);

        assertThat(chunks).hasSize(1);
        EvidenceChunk chunk = chunks.get(0);
        assertThat(chunk.evidenceType()).isEqualTo(EvidenceType.DOCUMENT);
        assertThat(chunk.contractVersion()).isEqualTo("evidence_v1");
        assertThat(chunk.source().fileId()).isEqualTo("file-1");
        assertThat(chunk.citation()).containsEntry("refId", "doc://file-1#chunk=12");
        assertThat(chunk.governance().tenantId()).isEqualTo("tenant-a");
        assertThat(chunk.governance().roles()).containsExactly("ops");
    }

    @Test
    void normalizesWebEvidenceContract() {
        Map<String, Object> payload = Map.of(
            "contractVersion", "web_evidence_v1",
            "requestContext", Map.of("tenantId", "tenant-a", "userId", "bob"),
            "results", List.of(Map.of(
                "rank", 1,
                "title", "Example",
                "url", "https://example.com/path",
                "snippet", "web evidence snippet",
                "score", 0.83
            ))
        );

        List<EvidenceChunk> chunks = normalizer.normalize("web_search", payload);

        assertThat(chunks).hasSize(1);
        EvidenceChunk chunk = chunks.get(0);
        assertThat(chunk.evidenceType()).isEqualTo(EvidenceType.WEB);
        assertThat(chunk.source().domain()).isEqualTo("example.com");
        assertThat(chunk.citation()).containsEntry("refId", "web://example.com/path#result=1");
        assertThat(chunk.content()).isEqualTo("web evidence snippet");
        assertThat(chunk.governance().userId()).isEqualTo("bob");
    }

    @Test
    void deduplicatesSameDocumentContentEvenWhenChunkReferencesDiffer() {
        Map<String, Object> payload = Map.of(
            "results", List.of(
                Map.of(
                    "fileId", "file-1",
                    "fileName", "plan.pdf",
                    "section", "targets",
                    "chunkIndex", 3,
                    "content", "\u65b0\u589e\u6709\u6548\u6237\u548c\u65b0\u589e\u8d44\u4ea7\u91cf"
                ),
                Map.of(
                    "fileId", "file-1",
                    "fileName", "plan.pdf",
                    "section", "targets",
                    "chunkIndex", 8,
                    "content", "\u65b0\u589e\u6709\u6548\u6237\u3001\u548c\u65b0\u589e\u8d44\u4ea7\u91cf"
                )
            )
        );

        List<EvidenceChunk> chunks = normalizer.normalize("document_search", payload);

        assertThat(chunks).hasSize(1);
    }
}
