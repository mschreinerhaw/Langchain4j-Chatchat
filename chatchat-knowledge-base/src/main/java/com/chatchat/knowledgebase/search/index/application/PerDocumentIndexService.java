package com.chatchat.knowledgebase.search.index;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PerDocumentIndexService {

    private final DocumentChunkStore chunkStore;

    @Autowired(required = false)
    private ResourceAuthorizationPort resourceAuthorization;

    public Optional<SearchDocument> openDocumentIndex(String docId, SearchPermissionContext permissionContext) {
        if (docId == null || docId.isBlank()) return Optional.empty();
        if (resourceAuthorization != null && permissionContext != null
            && !resourceAuthorization.allowedIds(ResourceAuthorizationPort.KNOWLEDGE,
                permissionContext.tenantId(), permissionContext.userId(),
                Set.copyOf(permissionContext.roles()), Set.of(docId)).contains(docId)) return Optional.empty();
        return chunkStore.loadDocument(docId, permissionContext);
    }
}
