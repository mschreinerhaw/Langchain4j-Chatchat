package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentSearchControllerAuthorizationTest {
    @Test
    void refusesExpansionBeforeReadingRevokedDocument() {
        DocumentSearchEvidenceService evidence = mock(DocumentSearchEvidenceService.class);
        DocumentEvidenceAuthorizationFilter filter = mock(DocumentEvidenceAuthorizationFilter.class);
        ApiDocumentEvidenceClient api = mock(ApiDocumentEvidenceClient.class);
        when(filter.enabled()).thenReturn(true);
        when(api.allowedDocumentIds("tenant-a", "user-a", Set.of("doc-a"))).thenReturn(Set.of());
        DocumentSearchExpandRequest request = new DocumentSearchExpandRequest("query", "doc-a", List.of(),
            8, 3, 6, 6000, "tenant-a", "user-a", List.of(), false);

        assertThat(new DocumentSearchController(evidence, filter, api).expand(request).getCode())
            .isEqualTo(403);
        verifyNoInteractions(evidence);
    }
}
