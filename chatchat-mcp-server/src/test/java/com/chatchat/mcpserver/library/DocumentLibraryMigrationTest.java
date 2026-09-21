package com.chatchat.mcpserver.library;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentLibraryMigrationTest {
    @TempDir Path storage;

    @Test
    void preservesDocumentIdAndStoresOriginalFileOnMcp() throws Exception {
        SearchService search = mock(SearchService.class);
        when(search.createOrUpdate(any(SearchDocument.class))).thenAnswer(call -> call.getArgument(0));
        SearchProperties properties = new SearchProperties();
        properties.setFilePath(storage.toString());
        DocumentLibraryController controller = new DocumentLibraryController(
            search, null, null, null, null, null, null, properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DocumentPrincipalContext.CURRENT_USERNAME, "admin");
        MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "original".getBytes());

        SearchDocument imported = controller.migrateDocument(
            "{\"docId\":\"doc-123\",\"title\":\"Guide\",\"content\":\"searchable\"}", file, request).getData();

        assertThat(imported.getDocId()).isEqualTo("doc-123");
        assertThat(imported.getContent()).isEqualTo("searchable");
        assertThat(Files.readString(Path.of(imported.getFilePath()))).isEqualTo("original");
    }
}
