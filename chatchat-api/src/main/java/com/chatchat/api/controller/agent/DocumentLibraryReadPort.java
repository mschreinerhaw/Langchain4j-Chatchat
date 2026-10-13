package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.api.library.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import java.util.List;
import java.util.Optional;

/** Read access used by Agent workshop without depending on a local document store. */
public interface DocumentLibraryReadPort {
    List<LibraryDocumentItem> list(SearchPermissionContext context);

    Optional<SearchDocument> get(String documentId, SearchPermissionContext context);

    Optional<byte[]> original(String documentId, SearchPermissionContext context);

    boolean exists(String documentId);
}
