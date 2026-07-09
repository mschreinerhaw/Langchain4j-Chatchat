package com.chatchat.api.controller;

import com.chatchat.api.config.ApiLimitProperties;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.search.CategoryReindexTaskService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.knowledgebase.search.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.SearchFeedbackService;
import com.chatchat.knowledgebase.search.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.SearchPage;
import com.chatchat.knowledgebase.search.SearchPermissionContext;
import com.chatchat.knowledgebase.search.SearchResult;
import com.chatchat.knowledgebase.search.SearchScoreBreakdown;
import com.chatchat.knowledgebase.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchControllerFrontendContractTest {

    @Test
    void frontendSearchDoesNotExposeRecallDebugTokensAsDisplayFields() {
        SearchService searchService = mock(SearchService.class);
        SearchController controller = new SearchController(
            searchService,
            mock(SearchFeedbackService.class),
            mock(DocumentSearchEvidenceService.class),
            mock(CategoryReindexTaskService.class),
            new ApiLimitProperties()
        );
        when(searchService.frontendQuickSearch(
            eq("大数据平台实时计算"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(6),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "大数据平台实时计算",
            List.of("大数据", "平台", "实时", "计算"),
            List.of(searchResultWithInternalSignals()),
            1,
            6,
            1,
            6,
            1,
            false,
            0L,
            1,
            "ok"
        ));

        ApiResponse<SearchPage> response = controller.frontendSearch(
            "大数据平台实时计算",
            null,
            null,
            null,
            null,
            1,
            6,
            null,
            null,
            null,
            null
        );

        SearchResult result = response.getData().results().get(0);
        assertThat(result.tags()).containsExactly("立项报告", "国都证券");
        assertThat(result.matchedKeywords()).isEmpty();
        assertThat(result.scoreBreakdown()).isNull();
    }

    @Test
    void frontendSearchSupportsUnlimitedConfiguredPreviewLimits() {
        SearchService searchService = mock(SearchService.class);
        ApiLimitProperties limits = new ApiLimitProperties();
        limits.setSearchResultMaxChunks(-1);
        limits.setSearchResultChunkMaxChars(-1);
        limits.setSearchResultSummaryMaxChars(-1);
        SearchController controller = new SearchController(
            searchService,
            mock(SearchFeedbackService.class),
            mock(DocumentSearchEvidenceService.class),
            mock(CategoryReindexTaskService.class),
            limits
        );
        String longSummary = "summary-" + "x".repeat(1200);
        String longChunk = "chunk-" + "y".repeat(1500);
        List<SearchMatchedChunk> chunks = java.util.stream.IntStream.range(0, 4)
            .mapToObj(index -> new SearchMatchedChunk(
                "doc-1",
                "report.txt",
                "section",
                "paragraph",
                "chunk-" + index,
                index,
                0.1F,
                index == 0 ? longChunk : "chunk-" + index,
                index == 0 ? longChunk : "chunk-" + index,
                9.5F,
                SearchPermissionContext.DEFAULT_TENANT,
                SearchPermissionContext.ANONYMOUS_USER,
                "public",
                List.of()
            ))
            .toList();
        SearchResult rawResult = new SearchResult(
            "doc-1",
            "report",
            longSummary,
            "source",
            "2026-07-09",
            "report.txt",
            "text",
            "/api/v1/search/documents/doc-1",
            List.of("tag"),
            List.of(),
            List.of(),
            90,
            null,
            List.of("quota"),
            chunks,
            "vg-1",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
        when(searchService.frontendQuickSearch(
            eq("quota"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(6),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "quota",
            List.of("quota"),
            List.of(rawResult),
            1,
            6,
            1,
            6,
            1,
            false,
            0L,
            1,
            "ok"
        ));

        ApiResponse<SearchPage> response = controller.frontendSearch(
            "quota",
            null,
            null,
            null,
            null,
            1,
            6,
            null,
            null,
            null,
            null
        );

        SearchResult result = response.getData().results().get(0);
        assertThat(result.summary()).isEqualTo(longSummary);
        assertThat(result.matchedChunks()).hasSize(4);
        assertThat(result.matchedChunks().get(0).content()).isEqualTo(longChunk);
        assertThat(result.matchedChunks().get(0).text()).isEqualTo(longChunk);
    }

    @Test
    void batchDeleteDocumentsReturnsDeletedAndNotFoundIds() {
        SearchService searchService = mock(SearchService.class);
        SearchController controller = new SearchController(
            searchService,
            mock(SearchFeedbackService.class),
            mock(DocumentSearchEvidenceService.class),
            mock(CategoryReindexTaskService.class),
            new ApiLimitProperties()
        );
        when(searchService.deleteDocument(eq("doc-1"), any(SearchPermissionContext.class))).thenReturn(true);
        when(searchService.deleteDocument(eq("doc-2"), any(SearchPermissionContext.class))).thenReturn(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME)).thenReturn("admin");

        ApiResponse<SearchController.DocumentBatchDeleteResult> response = controller.deleteDocuments(
            new SearchController.DocumentBatchDeleteRequest(List.of("doc-1", "", "doc-1", "doc-2", " ")),
            "tenant-a",
            "user-a",
            "role-a",
            request
        );

        assertThat(response.getData().requestedCount()).isEqualTo(2);
        assertThat(response.getData().deletedDocIds()).containsExactly("doc-1");
        assertThat(response.getData().notFoundDocIds()).containsExactly("doc-2");
    }

    @Test
    void deleteDocumentsRequiresAdminOperator() {
        SearchController controller = new SearchController(
            mock(SearchService.class),
            mock(SearchFeedbackService.class),
            mock(DocumentSearchEvidenceService.class),
            mock(CategoryReindexTaskService.class),
            new ApiLimitProperties()
        );
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME)).thenReturn("analyst");

        ApiResponse<SearchController.DocumentBatchDeleteResult> response = controller.deleteDocuments(
            new SearchController.DocumentBatchDeleteRequest(List.of("doc-1")),
            "tenant-a",
            "analyst",
            "role-a",
            request
        );

        assertThat(response.getCode()).isEqualTo(403);
        assertThat(response.getMessage()).isEqualTo("only admin can delete documents");
        assertThat(response.getData()).isNull();
    }

    private SearchResult searchResultWithInternalSignals() {
        return new SearchResult(
            "doc-1",
            "国都证券大数据平台实时计算与数据资产管理建设项目立项报告v2",
            "业务摘要 table sync jdbc mysql 这些词不能靠 matchedKeywords 标签区域泄漏",
            "文档库",
            "2026-06-20",
            "report.docx",
            "word",
            "/api/v1/search/documents/doc-1",
            List.of("立项报告", "国都证券"),
            List.of(),
            List.of(),
            96,
            new SearchScoreBreakdown(
                10,
                50,
                0,
                0,
                0,
                0,
                0,
                0,
                30,
                0,
                0.8D,
                Map.of("memoryRecallRaw", 80, "memoryRecall", 40)
            ),
            List.of("大数据", "平台", "实时", "计算", "table", "sync", "jdbc", "mysql"),
            List.of(new SearchMatchedChunk(
                "doc-1",
                "report.docx",
                "项目背景",
                "paragraph",
                "chunk-1",
                0,
                0.1F,
                "国都证券大数据平台实时计算与数据资产管理建设项目。",
                "国都证券大数据平台实时计算与数据资产管理建设项目。",
                9.5F,
                SearchPermissionContext.DEFAULT_TENANT,
                SearchPermissionContext.ANONYMOUS_USER,
                "public",
                List.of()
            )),
            "vg-1",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
    }
}
