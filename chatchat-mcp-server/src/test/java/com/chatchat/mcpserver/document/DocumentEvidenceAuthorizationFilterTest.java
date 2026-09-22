package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEvidenceAuthorizationFilterTest {
    @Test
    void removesDeniedEvidenceAndRebuildsContextAndCitations() {
        ApiDocumentEvidenceClient api = mock(ApiDocumentEvidenceClient.class);
        when(api.allowedDocumentIds("tenant-a", "user-a", Set.of("doc-a", "doc-b")))
            .thenReturn(Set.of("doc-a"));
        DocumentEvidenceChunk allowed = chunk("doc-a", "allowed text");
        DocumentEvidenceChunk denied = chunk("doc-b", "secret text");
        DocumentSearchResult result = new DocumentSearchResult("document_evidence_v1", "query", "", 2,
            List.of(allowed, denied), "secret text", List.of());
        DocumentSearchRequest request = new DocumentSearchRequest("query", 8, List.of(), List.of(), List.of(),
            null, null, "tenant-a", "user-a", List.of(), false);

        DocumentSearchResult filtered = new DocumentEvidenceAuthorizationFilter(api,
            new EvidenceContextFormatter(), new MockEnvironment()).filter(request, result);

        assertThat(filtered.results()).containsExactly(allowed);
        assertThat(filtered.context()).contains("allowed text").doesNotContain("secret text");
        assertThat(String.valueOf(filtered.reasoning())).doesNotContain("secret text");
        assertThat(filtered.total()).isEqualTo(1);
    }

    @Test
    void rejectsMissingCallerContext() {
        DocumentEvidenceAuthorizationFilter filter = new DocumentEvidenceAuthorizationFilter(
            mock(ApiDocumentEvidenceClient.class), new EvidenceContextFormatter(), new MockEnvironment());
        DocumentSearchResult result = new DocumentSearchResult("document_evidence_v1", "query", "", 1,
            List.of(chunk("doc-a", "text")), "text", List.of());
        assertThatThrownBy(() -> filter.filter(null, result))
            .isInstanceOf(IllegalStateException.class);
    }

    private DocumentEvidenceChunk chunk(String id, String content) {
        return new DocumentEvidenceChunk("ref-" + id, "chunk-" + id, id, id + ".txt", "section", 0,
            "TEXT", 50.0, content, List.of(), null, null, "tenant-a", "user-a", "PRIVATE", List.of());
    }
}
