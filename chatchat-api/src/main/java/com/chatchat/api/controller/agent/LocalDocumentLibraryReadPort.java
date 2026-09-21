package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.DocumentLifecycleStatus;
import com.chatchat.knowledgebase.search.document.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public boolean exists(String documentId) {
        return searchService.get(documentId)
            .filter(document -> !DocumentLifecycleStatus.DELETED.equalsIgnoreCase(document.getLifecycleStatus()))
            .isPresent();
    }
}
