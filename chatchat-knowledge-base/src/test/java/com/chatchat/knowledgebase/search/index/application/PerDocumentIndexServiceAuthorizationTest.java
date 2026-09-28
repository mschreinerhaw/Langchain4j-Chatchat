package com.chatchat.knowledgebase.search.index;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PerDocumentIndexServiceAuthorizationTest {
    @Test
    void deniesRocksDbReadBeforeOpeningDocument() {
        DocumentChunkStore source = mock(DocumentChunkStore.class);
        ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
        PerDocumentIndexService service = new PerDocumentIndexService(source);
        ReflectionTestUtils.setField(service, "resourceAuthorization", grants);
        var caller = SearchPermissionContext.of("tenant-1", "user-1", List.of("analyst"));
        when(grants.allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-1", "user-1",
            Set.of("analyst"), Set.of("doc-1"))).thenReturn(Set.of());

        assertThat(service.openDocumentIndex("doc-1", caller)).isEmpty();
        verifyNoInteractions(source);
    }
}
