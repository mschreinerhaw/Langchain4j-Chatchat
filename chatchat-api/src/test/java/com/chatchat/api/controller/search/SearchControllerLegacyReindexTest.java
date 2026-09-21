package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchControllerLegacyReindexTest {
    @Test
    void reindexActionTransfersLegacyDocumentToMcp() {
        SearchService search = mock(SearchService.class);
        LegacyDocumentMcpTransferService bridge = mock(LegacyDocumentMcpTransferService.class);
        SearchDocument source = SearchDocument.builder().docId("legacy-1").content("searchable").build();
        when(bridge.enabled()).thenReturn(true);
        when(search.get(eq("legacy-1"), any(SearchPermissionContext.class))).thenReturn(Optional.of(source));
        when(search.getFileResource(eq("legacy-1"), any(SearchPermissionContext.class))).thenReturn(Optional.empty());
        when(bridge.transfer(eq(source), eq(null), any(SearchPermissionContext.class), any(HttpServletRequest.class)))
            .thenReturn(source);
        SearchController controller = new SearchController(search, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "legacyDocumentMcpTransferService", bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");

        assertThat(controller.reindexDocument("legacy-1", null, null, null, request).getData().getDocId())
            .isEqualTo("legacy-1");
        verify(bridge).transfer(eq(source), eq(null), any(SearchPermissionContext.class), eq(request));
    }
}
