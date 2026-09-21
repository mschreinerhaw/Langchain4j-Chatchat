package com.chatchat.mcpserver.library;

import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLibraryControllerEditTest {
    private final SearchService search = mock(SearchService.class);
    private final DocumentLibraryController controller = new DocumentLibraryController(
        search, null, null, null, null, null, null, null, new ObjectMapper());

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void ownerCanEditMetadataWithoutChangingStoredDocumentOrFile() {
        MockHttpServletRequest request = principal("owner");
        SearchDocument existing = document();
        when(search.get(eq("doc-1"), any(SearchPermissionContext.class))).thenReturn(Optional.of(existing));
        when(search.createOrUpdate(any(SearchDocument.class))).thenAnswer(call -> call.getArgument(0));

        var result = controller.updateDocument("doc-1",
            new DocumentLibraryController.DocumentUpdateRequest(" New title ", " New source ", "2026-09-21", List.of("category", "tag")),
            null, null, null, request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getTitle()).isEqualTo("New title");
        assertThat(result.getData().getFilePath()).isEqualTo("/stored/file.pdf");
        assertThat(result.getData().getTenantId()).isEqualTo("tenant-1");
        assertThat(existing.getTitle()).isEqualTo("Old title");
        verify(search).createOrUpdate(any(SearchDocument.class));
    }

    @Test
    void anotherUserCannotEditOrDeleteSharedDocument() {
        MockHttpServletRequest request = principal("another-user");
        when(search.get(eq("doc-1"), any(SearchPermissionContext.class))).thenReturn(Optional.of(document()));

        var update = controller.updateDocument("doc-1",
            new DocumentLibraryController.DocumentUpdateRequest("New title", "source", "2026-09-21", List.of()),
            null, null, null, request);
        var delete = controller.deleteDocument("doc-1", null, null, null, request);
        var category = controller.updateDocumentCategory("doc-1",
            new DocumentLibraryController.DocumentCategoryUpdateRequest("private"),
            null, null, null, request);

        assertThat(update.getCode()).isEqualTo(403);
        assertThat(delete.getCode()).isEqualTo(403);
        assertThat(category.getCode()).isEqualTo(403);
        verify(search, never()).createOrUpdate(any(SearchDocument.class));
        verify(search, never()).deleteDocument(eq("doc-1"), any(SearchPermissionContext.class));
        verify(search, never()).updateDocumentCategory(eq("doc-1"), eq("private"), any(SearchPermissionContext.class));
    }

    private MockHttpServletRequest principal(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DocumentPrincipalContext.attach(request, "tenant-1", userId, userId, "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private SearchDocument document() {
        return SearchDocument.builder().docId("doc-1").title("Old title").content("search text")
            .filePath("/stored/file.pdf").tenantId("tenant-1").userId("owner").build();
    }
}
