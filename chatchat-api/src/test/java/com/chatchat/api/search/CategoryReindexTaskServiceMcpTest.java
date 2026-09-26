package com.chatchat.api.search;

import com.chatchat.api.controller.search.LegacyDocumentMcpTransferService;
import com.chatchat.knowledgebase.search.document.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.document.LibraryPage;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryReindexTaskServiceMcpTest {
    @Test
    void categoryReindexTransfersLegacyDocumentsToMcp() throws InterruptedException {
        SearchService search = mock(SearchService.class);
        LegacyDocumentMcpTransferService bridge = mock(LegacyDocumentMcpTransferService.class);
        SearchPermissionContext context = SearchPermissionContext.of("tenant-1", "user-1", null);
        LibraryDocumentItem item = mock(LibraryDocumentItem.class);
        when(item.docId()).thenReturn("legacy-1");
        when(bridge.enabled()).thenReturn(true);
        when(search.listLibrary("all", null, 1, 100, context)).thenReturn(
            new LibraryPage("all", null, List.of(), List.of(item), 1, 1, 100, 1, 1, false, null, null));
        SearchDocument document = SearchDocument.builder().docId("legacy-1").content("text").build();
        when(search.get("legacy-1", context)).thenReturn(Optional.of(document));
        when(search.getFileResource("legacy-1", context)).thenReturn(Optional.empty());
        CategoryReindexTaskService service = new CategoryReindexTaskService(search);
        ReflectionTestUtils.setField(service, "legacyDocumentMcpTransferService", bridge);
        try {
            service.start("all", context, "admin", List.of("workspace:search:delete"));
            verify(bridge, timeout(2000)).transfer(eq(document), eq(null), eq(context), eq("admin"),
                eq(List.of("workspace:search:delete")));
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(service.status().status()).isEqualTo("COMPLETED");
        } finally {
            service.shutdown();
        }
    }
}
