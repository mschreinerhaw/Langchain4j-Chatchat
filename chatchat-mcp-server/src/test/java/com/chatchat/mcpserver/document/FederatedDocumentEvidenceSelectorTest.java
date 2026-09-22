package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FederatedDocumentEvidenceSelectorTest {
    private final FederatedDocumentEvidenceSelector selector = new FederatedDocumentEvidenceSelector(
        new SearchTokenizer(), new EvidenceContextFormatter());

    @Test
    void excludesGenericInstallationWhenApiHasLivedataEvidence() {
        DocumentSearchResult local = result(chunk("linux", "Linux commands", "FreeIPA installation steps"));
        DocumentSearchResult api = result(chunk("livedata", "livedata installation guide", "livedata deployment checks"));

        DocumentSearchResult selected = selector.select("livedata installation guide", 8, local, api);

        assertThat(selected.results()).extracting(DocumentEvidenceChunk::fileId).containsExactly("livedata");
        assertThat(selected.context()).doesNotContain("FreeIPA");
    }

    @Test
    void mergesSourcesWhenBothContainQueriedSubject() {
        DocumentSearchResult local = result(chunk("local", "livedata operations", "livedata startup checks"));
        DocumentSearchResult api = result(chunk("api", "livedata installation guide", "livedata deployment checks"));

        DocumentSearchResult selected = selector.select("livedata installation guide", 8, local, api);

        assertThat(selected.results()).extracting(DocumentEvidenceChunk::fileId)
            .containsExactly("api", "local");
        assertThat(selected.citations()).hasSize(2);
    }

    private DocumentSearchResult result(DocumentEvidenceChunk chunk) {
        return new DocumentSearchResult("document_evidence_v1", "query", "how_to", 1,
            List.of(chunk), "", List.of());
    }

    private DocumentEvidenceChunk chunk(String id, String fileName, String content) {
        return new DocumentEvidenceChunk("doc://" + id + "#chunk=1", id + "-1", id, fileName,
            "installation", 1, "document", 26.0, content, List.of(), null, null,
            "tenant-1", "user-1", "tenant", List.of());
    }
}
