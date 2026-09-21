package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import java.util.List;

/** Read access used by Agent workshop without depending on a local document store. */
public interface DocumentLibraryReadPort {
    List<LibraryDocumentItem> list(SearchPermissionContext context);

    boolean exists(String documentId);
}
