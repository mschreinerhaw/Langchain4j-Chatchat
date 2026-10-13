package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.api.library.DocumentLifecycleStatus;
import com.chatchat.knowledgebase.search.document.api.library.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.io.IOException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "chatchat.document.gateway", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalDocumentLibraryReadPort implements DocumentLibraryReadPort {
    private final SearchService searchService;

    @Override
    public List<LibraryDocumentItem> list(SearchPermissionContext context) {
        return searchService.listLibrary("all", null, 1, 500, context).documents();
    }

    @Override
    public Optional<SearchDocument> get(String documentId, SearchPermissionContext context) {
        return searchService.get(documentId, context);
    }

    @Override
    public Optional<byte[]> original(String documentId, SearchPermissionContext context) {
        return searchService.getFileResource(documentId, context).map(file -> {
            try (var input = file.resource().getInputStream()) { return input.readNBytes(2 * 1024 * 1024 + 1); }
            catch (IOException exception) { throw new IllegalStateException("Cannot read library document", exception); }
        });
    }

    @Override
    public boolean exists(String documentId) {
        return searchService.get(documentId)
            .filter(document -> !DocumentLifecycleStatus.DELETED.equalsIgnoreCase(document.getLifecycleStatus()))
            .isPresent();
    }
}
