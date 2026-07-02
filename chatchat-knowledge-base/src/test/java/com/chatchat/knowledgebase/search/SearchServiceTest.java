package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    private RocksDbSearchStore store;
    private LuceneDocumentIndexService luceneStore;

    @AfterEach
    void closeStore() {
        if (luceneStore != null) {
            luceneStore.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void indexesDocumentAndReturnsSearchEngineFields() {
        SearchService service = newSearchService();
        saveSemiconductorDocument(service);

        SearchPage page = service.search("semiconductor localization", null, null, null, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.documentCount()).isEqualTo(-1);
        assertThat(page.message()).isEqualTo("ok");
        assertThat(page.results()).hasSize(1);
        assertThat(page.results().get(0).detailPath()).isEqualTo("/api/v1/search/documents/doc-001");
        assertThat(page.results().get(0).matchedKeywords()).contains("semiconductor", "localization");
    }

    @Test
    void listsLibraryByCategoryAndChecksTitleExistence() {
        SearchService service = newSearchService();
        saveSemiconductorDocument(service);
        service.createCategory("custom-research");
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-002")
            .title("Macro Strategy Weekly")
            .content("Policy, liquidity and asset allocation notes.")
            .source("strategy")
            .date("2024-06-05")
            .tags(List.of("macro"))
            .build());

        LibraryPage page = service.listLibrary("semiconductor", "equipment", 20);

        assertThat(page.documentCount()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.titleExists()).isFalse();
        assertThat(page.categories()).extracting(LibraryCategory::name)
            .contains("all", "semiconductor", "localization", "macro", "custom-research");
        assertThat(page.documents()).hasSize(1);
        assertThat(page.documents().get(0).category()).isEqualTo("semiconductor");

        TitleExistsResult exists = service.titleExists("Semiconductor Equipment Localization");
        assertThat(exists.exists()).isTrue();
        assertThat(exists.docId()).isEqualTo("doc-001");
    }

    @Test
    void listsLibraryByPartialChineseTitleOrFileName() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-title-cn")
            .title("大数据平台实时计算与数据资产管理建设项目")
            .content("project overview body")
            .source("document-library")
            .date("2026-06-20")
            .build());
        SearchDocument fileNameDocument = service.upload(
            textFile("大数据平台实时计算与数据资产管理建设项目说明.txt", "project appendix body"),
            "项目说明",
            "document-library",
            "2026-06-20",
            null,
            null,
            null,
            null,
            "text",
            null
        );

        LibraryPage page = service.listLibrary("all", "数据资产管理建设项目", 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.documents()).extracting(LibraryDocumentItem::docId)
            .containsExactlyInAnyOrder("doc-title-cn", fileNameDocument.getDocId());
    }

    @Test
    void searchesByPartialChineseDocumentTitle() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-cn-title")
            .title("大数据平台实时计算与数据资产管理建设项目")
            .content("unrelated project overview body")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.search("数据资产管理建设项目", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-cn-title");
        assertThat(page.results().get(0).scoreBreakdown().phraseScore())
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("title"))
            .isGreaterThan(0);
    }

    @Test
    void frontendQuickSearchMatchesShortChineseTitlePhrase() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-quality-title")
            .title("数据质量核查报告")
            .content("quality report body")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "数据质量",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-quality-title");
        assertThat(page.results().get(0).scoreBreakdown().phraseScore())
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("title"))
            .isGreaterThan(0);
    }

    @Test
    void frontendQuickSearchMatchesRememberedChineseTitleKeywords() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-quality-keywords")
            .title("数据资产质量管理报告")
            .content("quality report body")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "数据质量",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-quality-keywords");
        assertThat(page.results().get(0).scoreBreakdown().coverageRatio())
            .isGreaterThanOrEqualTo(0.6D);
    }

    @Test
    void frontendQuickSearchRoutesShortChineseTitleFragmentToMemoryRecall() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-data-asset-project")
            .title("\u56fd\u90fd\u8bc1\u5238\u5927\u6570\u636e\u5e73\u53f0\u5b9e\u65f6\u8ba1\u7b97\u4e0e\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa\u8bbe\u9879\u76ee\u7acb\u9879\u62a5\u544av2")
            .content("project approval introduction without the user remembered title words")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "\u6570\u636e\u8d44\u4ea7\u7ba1\u7406",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-data-asset-project");
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"))
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecallRaw"))
            .isGreaterThanOrEqualTo(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"));
    }

    @Test
    void frontendQuickSearchRoutesLongChineseTitleFragmentToMemoryRecall() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-realtime-project")
            .title("\u56fd\u90fd\u8bc1\u5238\u5927\u6570\u636e\u5e73\u53f0\u5b9e\u65f6\u8ba1\u7b97\u4e0e\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa\u8bbe\u9879\u76ee\u7acb\u9879\u62a5\u544av2")
            .content("project approval introduction without the user remembered title words")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "\u5927\u6570\u636e\u5e73\u53f0\u5b9e\u65f6\u8ba1\u7b97",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-realtime-project");
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"))
            .isGreaterThan(0);
    }

    @Test
    void frontendQuickSearchKeepsDocumentTagsSeparateFromMatchedKeywords() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-visible-tags")
            .title("\u56fd\u90fd\u8bc1\u5238\u5927\u6570\u636e\u5e73\u53f0\u5b9e\u65f6\u8ba1\u7b97\u4e0e\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa\u8bbe\u9879\u76ee\u7acb\u9879\u62a5\u544av2")
            .content("project approval introduction without the user remembered title words")
            .source("document-library")
            .date("2026-06-20")
            .tags(List.of("\u7acb\u9879\u62a5\u544a", "\u56fd\u90fd\u8bc1\u5238"))
            .build());

        SearchPage page = service.frontendQuickSearch(
            "\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        SearchResult result = page.results().get(0);
        assertThat(result.tags())
            .containsExactly("\u7acb\u9879\u62a5\u544a", "\u56fd\u90fd\u8bc1\u5238");
        assertThat(result.matchedKeywords())
            .contains("\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa")
            .doesNotContain("\u7acb\u9879\u62a5\u544a");
    }

    @Test
    void searchHardIncludesChineseTitleMemoryCandidates() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-title-memory")
            .title("数据资产质量管理报告")
            .content("unrelated body without the query phrase")
            .source("document-library")
            .date("2026-06-20")
            .tags(List.of("quality"))
            .build());

        SearchPage page = service.search("数据质量", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-title-memory");
        assertThat(page.results().get(0).scoreBreakdown().baseTokenScore())
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"))
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecallRaw"))
            .isGreaterThanOrEqualTo(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"));
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("title"))
            .isGreaterThan(0);
    }

    @Test
    void searchKeepsMemoryRecallCandidateThroughRelevanceFilter() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-memory-relevance")
            .title("\u56fd\u90fd\u8bc1\u5238\u5927\u6570\u636e\u5e73\u53f0\u5b9e\u65f6\u8ba1\u7b97\u4e0e\u6570\u636e\u8d44\u4ea7\u7ba1\u7406\u5efa\u8bbe\u9879\u76ee\u7acb\u9879\u62a5\u544av2")
            .content("project approval introduction without the user remembered title words")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.search("\u6570\u636e\u8d44\u4ea7\u7ba1\u7406", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-memory-relevance");
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"))
            .isGreaterThan(0);
    }

    @Test
    void titleMemoryRecallIsCappedBeforeRankingWhenLuceneIsDisabled() {
        SearchService service = newSearchService(properties -> properties.setLuceneEnabled(false));
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-title-memory-cap")
            .title("\u6570\u636e\u8d28\u91cf\u6838\u67e5\u62a5\u544a")
            .content("unrelated body without the query phrase")
            .source("document-library")
            .date("2026-06-20")
            .build());

        SearchPage page = service.search("\u6570\u636e\u8d28\u91cf", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-title-memory-cap");
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecallRaw"))
            .isEqualTo(90);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"))
            .isLessThan(90)
            .isLessThanOrEqualTo(60);
        assertThat(page.results().get(0).scoreBreakdown().baseTokenScore())
            .isGreaterThanOrEqualTo(page.results().get(0).scoreBreakdown().fieldScores().get("memoryRecall"));
    }

    @Test
    void titleMemoryCandidatesStillRespectFilters() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-quality-filtered")
            .title("数据资产质量管理报告")
            .content("quality report body")
            .source("document-library")
            .date("2026-06-20")
            .tags(List.of("quality"))
            .build());

        SearchPage page = service.search("数据质量", "operations", null, null, 10);

        assertThat(page.results()).isEmpty();
    }

    @Test
    void uploadsSameTitleAsDocumentVersionsAndSearchesLatestOnly() {
        SearchService service = newSearchService();

        SearchDocument first = service.upload(
            textFile("report-v1.txt", "first version unique notes"),
            "Versioned Report",
            "research",
            "2024-06-01",
            "reports",
            null,
            null,
            null,
            "text",
            null
        );
        SearchDocument second = service.upload(
            textFile("report-v2.txt", "second version current notes"),
            "Versioned Report",
            "research",
            "2024-06-02",
            "reports",
            null,
            null,
            null,
            "text",
            null
        );

        assertThat(second.getVersionGroupId()).isEqualTo(first.getVersionGroupId());
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(second.getVersion()).isEqualTo(2);

        LibraryPage library = service.listLibrary("all", null, 10);
        assertThat(library.documentCount()).isEqualTo(1);
        assertThat(library.documents()).hasSize(1);
        assertThat(library.documents().get(0).docId()).isEqualTo(second.getDocId());
        assertThat(library.documents().get(0).version()).isEqualTo(2);

        List<SearchDocumentVersionItem> versions = service.listVersions(second.getDocId());
        assertThat(versions).extracting(SearchDocumentVersionItem::version).containsExactly(2, 1);
        assertThat(versions.get(0).latestVersion()).isTrue();
        assertThat(versions.get(1).latestVersion()).isFalse();
        assertThat(service.getVersion(second.getDocId(), 1)).get().extracting(SearchDocument::getContent)
            .isEqualTo("first version unique notes");

        SearchPage oldKeyword = service.search("first unique", null, null, null, 10);
        SearchPage newKeyword = service.search("second current", null, null, null, 10);
        assertThat(oldKeyword.results()).isEmpty();
        assertThat(newKeyword.results()).hasSize(1);
        assertThat(newKeyword.results().get(0).docId()).isEqualTo(second.getDocId());
        assertThat(newKeyword.results().get(0).summary()).contains("second version current");
    }

    @Test
    void filtersWeakSingleTermMatchesForMultiTermQueries() {
        SearchService service = newSearchService();
        saveSemiconductorDocument(service);
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-weak")
            .title("General Equipment Notes")
            .content("Equipment maintenance checklist and order tracking.")
            .source("operations")
            .date("2024-06-06")
            .tags(List.of("operations"))
            .build());

        SearchPage page = service.search("semiconductor equipment localization", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-001");
        assertThat(page.results().get(0).scoreBreakdown().coverageRatio()).isGreaterThanOrEqualTo(0.6D);
    }

    @Test
    void ranksTitleKeywordAndTagMatchesAbovePlainContentMatches() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-content")
            .title("Operations Weekly")
            .content("Cloud database export issues appeared in the release notes.")
            .source("ops")
            .date("2024-06-08")
            .tags(List.of("operations"))
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-field")
            .title("Cloud Database Export")
            .content("Troubleshooting guide for data sync jobs.")
            .source("platform")
            .date("2024-06-01")
            .tags(List.of("cloud", "database"))
            .keywords(List.of("cloud database export"))
            .build());

        SearchPage page = service.search("cloud database export", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-field", "doc-content");
        assertThat(page.results().get(0).scoreBreakdown().titleScore()).isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().keywordScore()).isGreaterThan(0);
    }

    @Test
    void frontendQuickSearchReturnsLightweightDocumentResults() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-spark")
            .title("Spark SQL Performance Guide")
            .content("Spark SQL tuning uses partition pruning, broadcast joins and adaptive execution.")
            .source("docs")
            .date("2024-06-10")
            .tags(List.of("spark", "sql"))
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-other")
            .title("Database Backup Guide")
            .content("Backup and restore checklist.")
            .source("docs")
            .date("2024-06-09")
            .tags(List.of("database"))
            .build());

        SearchPage page = service.frontendQuickSearch(
            "Spark",
            null,
            null,
            null,
            null,
            1,
            6,
            SearchPermissionContext.system()
        );

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-spark");
        assertThat(page.results().get(0).summary()).contains("Spark SQL");
        assertThat(page.results().get(0).matchedChunks()).hasSize(1);
        assertThat(page.results().get(0).matchedChunks().get(0).chunkType()).isEqualTo("frontend");
    }

    @Test
    void frontendQuickSearchRequiresMeaningfulCoverageForMultiTermQueries() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-specific")
            .title("Spark SQL Broadcast Tuning")
            .content("Spark SQL broadcast tuning uses adaptive execution and partition pruning.")
            .source("docs")
            .date("2024-06-10")
            .tags(List.of("spark", "sql"))
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-spark-only")
            .title("Spark Operations")
            .content("Spark cluster checklist and executor sizing guidance.")
            .source("docs")
            .date("2024-06-11")
            .tags(List.of("spark"))
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-sql-only")
            .title("SQL Backup")
            .content("SQL database backup and restore checklist.")
            .source("docs")
            .date("2024-06-12")
            .tags(List.of("sql"))
            .build());

        SearchPage page = service.frontendQuickSearch(
            "spark sql broadcast",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-specific");
        assertThat(page.results().get(0).scoreBreakdown().baseTokenScore()).isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().fieldScores()).containsKey("bm25");
    }

    @Test
    void frontendQuickSearchRanksDenseBm25MatchAboveSparseLongMatch() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-dense")
            .title("Runtime Notes")
            .content("Spark execution improves SQL joins with broadcast tuning.")
            .source("docs")
            .date("2024-06-01")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-sparse")
            .title("Runtime Appendix")
            .content("Spark " + "unrelated operational appendix ".repeat(250)
                + "SQL " + "unrelated operational appendix ".repeat(250)
                + "broadcast")
            .source("docs")
            .date("2024-06-12")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "spark sql broadcast",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-dense", "doc-sparse");
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("bm25"))
            .isGreaterThan(page.results().get(1).scoreBreakdown().fieldScores().get("bm25"));
    }

    @Test
    void semanticLexiconExpandsChineseFinanceTermsForFrontendSearch() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-revenue")
            .title("Revenue Growth Notes")
            .content("Revenue growth improved as customer orders recovered.")
            .source("finance")
            .date("2024-06-12")
            .tags(List.of("finance"))
            .build());

        SearchPage page = service.frontendQuickSearch(
            "营收",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-revenue");
        assertThat(page.queryTokens()).contains("营收", "revenue");
    }

    @Test
    void tikaOcrImageSupportIsControlledBySearchProperties() {
        SearchProperties enabled = new SearchProperties();
        enabled.getOcr().setEnabled(true);
        SearchProperties disabled = new SearchProperties();
        disabled.getOcr().setEnabled(false);

        assertThat(new DocumentTextExtractor(enabled).supports("scan.png")).isTrue();
        assertThat(new DocumentTextExtractor(disabled).supports("scan.png")).isFalse();
        assertThat(new DocumentTextExtractor(disabled).supports("policy.pdf")).isTrue();
    }

    @Test
    void ocrMarkedChunksAreIndexedAsWeakEvidence() {
        SearchService service = newSearchService(properties -> properties.getOcr().setScorePenalty(0.5F));
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-native")
            .title("Native Evidence")
            .content("invoice total alpha amount due")
            .source("native")
            .date("2024-06-11")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-ocr")
            .title("Scanned Evidence")
            .content("# OCR_TEXT\ninvoice total alpha amount due")
            .source("scan")
            .date("2024-06-12")
            .build());

        SearchPage page = service.search("invoice total alpha", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .contains("doc-native", "doc-ocr");
        SearchResult nativeResult = page.results().stream()
            .filter(result -> "doc-native".equals(result.docId()))
            .findFirst()
            .orElseThrow();
        SearchResult ocrResult = page.results().stream()
            .filter(result -> "doc-ocr".equals(result.docId()))
            .findFirst()
            .orElseThrow();
        assertThat(ocrResult.matchedChunks().get(0).chunkType()).isEqualTo("ocr_text");
        assertThat(nativeResult.matchedChunks().get(0).score())
            .isGreaterThan(ocrResult.matchedChunks().get(0).score());
    }

    @Test
    void ignoresGenericSearchWordsBeforeRankingDocuments() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-generic")
            .title("Document Report Summary")
            .content("Please search this document report for related information and notes.")
            .source("library")
            .date("2024-06-07")
            .tags(List.of("reports"))
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-specific")
            .title("Cloud Database Export")
            .content("Cloud database export troubleshooting and backup validation steps.")
            .source("platform")
            .date("2024-06-08")
            .tags(List.of("cloud", "database"))
            .build());

        SearchPage page = service.search("please search document report cloud database export", null, null, null, 10);

        assertThat(page.queryTokens()).containsExactly("cloud", "database", "export");
        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly("doc-specific");
    }

    @Test
    void returnsMultipleMatchedChunksFromLucene() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-chunks")
            .title("Release Investigation")
            .content("""
                # Alpha Incident
                Alpha service latency increased after the first deployment window. The team reviewed gateway metrics, request queues, and cache pressure for the affected tenant. Engineers confirmed that retry pressure stayed localized to the alpha path.

                # Gamma Follow Up
                Gamma export retries recovered after queue throttling was adjusted. Operators compared export backlog, worker concurrency, and downstream database pressure before closing the incident. The gamma path remained stable after the mitigation.

                # Notes
                Routine operational details unrelated to the incident.
                """)
            .source("ops")
            .date("2024-06-09")
            .tags(List.of("release"))
            .build());

        SearchPage page = service.search("alpha gamma", null, null, null, 10);

        assertThat(page.results()).hasSize(1);
        SearchResult result = page.results().get(0);
        assertThat(result.matchedChunks()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.matchedChunks()).extracting(SearchMatchedChunk::text)
            .anySatisfy(text -> assertThat(text).contains("Alpha Incident"))
            .anySatisfy(text -> assertThat(text).contains("Gamma Follow Up"));
        assertThat(result.matchedChunks()).extracting(SearchMatchedChunk::section)
            .anySatisfy(section -> assertThat(section).contains("Alpha Incident"))
            .anySatisfy(section -> assertThat(section).contains("Gamma Follow Up"));
        assertThat(result.matchedChunks()).extracting(SearchMatchedChunk::chunkType)
            .contains("troubleshooting");
    }

    @Test
    void expandsSynonymsForLuceneSearchWithoutVectorStore() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-error")
            .title("Nightly Job Failure")
            .content("The report export failed because the database queue timed out during authentication.")
            .source("ops")
            .date("2024-06-10")
            .build());

        SearchPage page = service.search("报表异常原因", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .contains("doc-error");
        assertThat(page.queryTokens()).doesNotContain("error", "exception", "failure");
        assertThat(page.results().get(0).matchedChunks().get(0).content())
            .contains("report export failed");
    }

    @Test
    void expandsEnglishQueryToChineseDocumentTermsForRetrieval() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-server-list-zh")
            .title("数据资产管理平台服务器清单-推荐配置及软件部署清单")
            .content("节点物理配置信息，CPU 内存 OS盘大小 数据分区磁盘数量，平台服务器清单。")
            .source("library")
            .date("2026-06-11")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "server inventory recommended configuration",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .contains("doc-server-list-zh");
        assertThat(page.queryTokens())
            .contains("server", "inventory", "服务器", "清单", "推荐配置");
    }

    @Test
    void expandsChineseQueryToEnglishDocumentTermsForRetrieval() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-server-list-en")
            .title("LiveData Server Inventory - Recommended Configuration")
            .content("Server inventory and software deployment list for the LiveData platform.")
            .source("library")
            .date("2026-06-23")
            .build());

        SearchPage page = service.frontendQuickSearch(
            "服务器清单 推荐配置",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .contains("doc-server-list-en");
        assertThat(page.queryTokens())
            .contains("服务器", "清单", "server", "inventory", "recommended", "configuration");
    }

    @Test
    void fallbackCandidateScanIsBoundedWhenKeywordIndexMisses() {
        SearchService service = newSearchService(properties -> {
            properties.setLuceneEnabled(false);
            properties.setFallbackCandidateLimit(2);
        });
        store.put(SearchDocument.builder()
            .docId("doc-001")
            .title("First")
            .content("ordinary first content")
            .source("manual")
            .date("2024-06-01")
            .build(), emptyIndex(), null);
        store.put(SearchDocument.builder()
            .docId("doc-002")
            .title("Second")
            .content("ordinary second content")
            .source("manual")
            .date("2024-06-02")
            .build(), emptyIndex(), null);
        store.put(SearchDocument.builder()
            .docId("doc-003")
            .title("Third")
            .content("boundedneedle appears only after the fallback limit")
            .source("manual")
            .date("2024-06-03")
            .build(), emptyIndex(), null);

        SearchPage page = service.search("boundedneedle", null, null, null, 10);

        assertThat(page.results()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.message()).isEqualTo("no_match");
    }

    @Test
    void extractsKeywordsAutomaticallyForUploadedDocuments() {
        SearchService service = newSearchService();

        SearchDocument document = service.upload(
            textFile("latency-report.txt", "latency latency latency database queue timeout report"),
            "Latency Report",
            "ops",
            "2024-06-11",
            null,
            null,
            null,
            null,
            "text",
            null
        );

        assertThat(document.getKeywords()).contains("latency", "database", "queue");
    }

    @Test
    void searchesUploadedDocumentsByFileNameWhenLuceneIsDisabled() {
        SearchService service = newSearchService(properties -> properties.setLuceneEnabled(false));

        SearchDocument document = service.upload(
            textFile("模型日资产数据核对报告2026-06.txt", "daily reconciliation values only"),
            "资产日报",
            "document-library",
            "2026-06-20",
            null,
            null,
            null,
            null,
            "text",
            null
        );

        SearchPage page = service.search("模型日资产数据核对报告", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly(document.getDocId());
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("fileName"))
            .isGreaterThan(0);

        SearchPage dateTagPage = service.search("202606", null, null, null, 10);

        assertThat(dateTagPage.results()).extracting(SearchResult::docId)
            .containsExactly(document.getDocId());
        assertThat(dateTagPage.results().get(0).scoreBreakdown().fieldScores().get("fileName"))
            .isGreaterThan(0);
    }

    @Test
    void frontendQuickSearchScoresUploadedDocumentsByFileName() {
        SearchService service = newSearchService();

        SearchDocument document = service.upload(
            textFile("model-daily-asset-check-report.txt", "daily reconciliation values only"),
            "Asset Daily",
            "document-library",
            "2026-06-20",
            null,
            null,
            null,
            null,
            "text",
            null
        );

        SearchPage page = service.frontendQuickSearch(
            "model daily asset check report",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.system()
        );

        assertThat(page.results()).extracting(SearchResult::docId)
            .containsExactly(document.getDocId());
        assertThat(page.results().get(0).scoreBreakdown().fieldScores().get("fileName"))
            .isGreaterThan(0);
        assertThat(page.results().get(0).scoreBreakdown().phraseScore())
            .isGreaterThan(0);
    }

    @Test
    void uploadsSqlFileAsSearchableTextDocument() {
        SearchService service = newSearchService();

        SearchDocument document = service.upload(
            textFile("spark-connectors.sql", """
                -- SparkSQL connector example
                CREATE TABLE filesystem_source USING filesystem
                OPTIONS (path '/data/orders', format 'parquet');

                CREATE TABLE mysql_sink USING jdbc
                OPTIONS (url 'jdbc:mysql://mysql-host:3306/demo', dbtable 'orders_sink');
                """),
            "Spark SQL Connectors",
            "document-library",
            "2026-06-19",
            "SQL",
            null,
            null,
            null,
            null,
            null
        );

        SearchPage page = service.search("SparkSQL filesystem MySQL connector", null, null, null, 10);

        assertThat(document.getDocumentType()).isEqualTo("sql");
        assertThat(document.getContent()).contains("CREATE TABLE filesystem_source", "CREATE TABLE mysql_sink");
        assertThat(page.results()).extracting(SearchResult::docId).contains(document.getDocId());
    }

    @Test
    void reextractsUploadedSqlFilesWhenReindexingSqlDocuments() {
        SearchService service = newSearchService();
        SearchDocument document = service.upload(
            textFile("spark-file-mysql.sql", """
                CREATE TABLE fs_source USING filesystem OPTIONS (path '/warehouse/events');
                CREATE TABLE mysql_target USING jdbc OPTIONS (dbtable 'events_target');
                """),
            "Spark File To MySQL",
            "document-library",
            "2026-06-19",
            "SQL",
            null,
            null,
            null,
            null,
            null
        );
        document.setContent("stale placeholder content");
        document.setKeywords(List.of("stale"));
        service.createOrUpdate(document);

        assertThat(service.search("filesystem mysql_target", null, null, null, 10).results()).isEmpty();

        SearchService.ReindexSummary summary = service.reindexUploadedSqlDocuments(SearchPermissionContext.system());
        SearchPage page = service.search("filesystem mysql_target", null, null, null, 10);

        assertThat(summary.matchedDocuments()).isEqualTo(1);
        assertThat(summary.reindexedDocIds()).contains(document.getDocId());
        assertThat(page.results()).extracting(SearchResult::docId).contains(document.getDocId());
    }

    @Test
    void reextractsUploadedDocumentsWhenReindexingCategory() {
        SearchService service = newSearchService();
        SearchDocument first = service.upload(
            textFile("category-one.txt", "alpha category rebuild evidence"),
            "Category One",
            "document-library",
            "2026-06-19",
            "rebuild-test",
            null,
            null,
            null,
            "text",
            null
        );
        SearchDocument second = service.upload(
            textFile("category-two.txt", "beta category rebuild evidence"),
            "Category Two",
            "document-library",
            "2026-06-19",
            "rebuild-test",
            null,
            null,
            null,
            "text",
            null
        );
        first.setContent("stale first");
        second.setContent("stale second");
        service.createOrUpdate(first);
        service.createOrUpdate(second);

        SearchService.ReindexSummary summary = service.reindexDocumentsByCategory("rebuild-test", SearchPermissionContext.system());
        SearchPage page = service.search("alpha beta rebuild evidence", null, null, null, 10);

        assertThat(summary.matchedDocuments()).isEqualTo(2);
        assertThat(summary.reindexedDocIds()).contains(first.getDocId(), second.getDocId());
        assertThat(page.results()).extracting(SearchResult::docId).contains(first.getDocId(), second.getDocId());
    }

    @Test
    void intentExpansionAndChunkTypePreferHowToSteps() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-step")
            .title("Login Configuration Guide")
            .content("""
                # Setup Steps
                Step 1 configure the login provider.
                Step 2 enable authentication callback.
                Step 3 verify users can sign in.
                """)
            .source("guide")
            .date("2024-06-12")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-general")
            .title("Login Overview")
            .content("Login overview explains authentication concepts and account security.")
            .source("guide")
            .date("2024-06-12")
            .build());

        SearchPage page = service.search("how to login", null, null, null, 10);

        assertThat(page.results()).extracting(SearchResult::docId)
            .startsWith("doc-step");
        assertThat(page.results().get(0).matchedChunks().get(0).chunkType())
            .isEqualTo("step");
        assertThat(page.results().get(0).matchedChunks().get(0).positionRatio())
            .isEqualTo(0.0F);
    }

    @Test
    void isolatesDocumentsByTenantUserAndRolePermissions() {
        SearchService service = newSearchService();
        service.createOrUpdate(SearchDocument.builder()
            .docId("tenant-a-public")
            .title("Tenant A Runbook")
            .content("shared deployment rollback checklist")
            .source("ops")
            .date("2024-06-13")
            .tenantId("tenant-a")
            .userId("owner-a")
            .visibility("tenant")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("tenant-b-public")
            .title("Tenant B Runbook")
            .content("shared deployment rollback checklist")
            .source("ops")
            .date("2024-06-13")
            .tenantId("tenant-b")
            .userId("owner-b")
            .visibility("tenant")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("tenant-a-private")
            .title("Tenant A Private Runbook")
            .content("private deployment rollback checklist")
            .source("ops")
            .date("2024-06-13")
            .tenantId("tenant-a")
            .userId("alice")
            .visibility("private")
            .build());
        service.createOrUpdate(SearchDocument.builder()
            .docId("tenant-a-role")
            .title("Tenant A SecOps Runbook")
            .content("role deployment rollback checklist")
            .source("ops")
            .date("2024-06-13")
            .tenantId("tenant-a")
            .userId("owner-a")
            .visibility("role")
            .permissionRoles(List.of("secops"))
            .build());

        SearchPage bobPage = service.search(
            "deployment rollback checklist",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.of("tenant-a", "bob", List.of())
        );
        SearchPage alicePage = service.search(
            "deployment rollback checklist",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.of("tenant-a", "alice", List.of())
        );
        SearchPage secopsPage = service.search(
            "deployment rollback checklist",
            null,
            null,
            null,
            null,
            1,
            10,
            SearchPermissionContext.of("tenant-a", "bob", List.of("secops"))
        );

        assertThat(bobPage.results()).extracting(SearchResult::docId)
            .containsExactly("tenant-a-public");
        assertThat(alicePage.results()).extracting(SearchResult::docId)
            .containsExactlyInAnyOrder("tenant-a-private", "tenant-a-public");
        assertThat(secopsPage.results()).extracting(SearchResult::docId)
            .containsExactlyInAnyOrder("tenant-a-role", "tenant-a-public");
        assertThat(secopsPage.results()).allSatisfy(result -> assertThat(result.tenantId()).isEqualTo("tenant-a"));
    }

    private void saveSemiconductorDocument(SearchService service) {
        service.createOrUpdate(SearchDocument.builder()
            .docId("doc-001")
            .title("Semiconductor Equipment Localization")
            .content("Track semiconductor equipment orders, capex and policy support.")
            .source("industry-chain")
            .date("2024-06-04")
            .tags(List.of("semiconductor", "localization"))
            .build());
    }

    private MockMultipartFile textFile(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private SearchService newSearchService() {
        return newSearchService(properties -> {
        });
    }

    private SearchService newSearchService(java.util.function.Consumer<SearchProperties> customizer) {
        SearchProperties properties = new SearchProperties();
        properties.setStorePath(tempDir.resolve("rocksdb").toString());
        properties.setFilePath(tempDir.resolve("files").toString());
        properties.setLuceneIndexPath(tempDir.resolve("lucene").toString());
        properties.setChunkSize(120);
        properties.setChunkOverlap(0);
        properties.setLuceneChunksPerDocument(3);
        customizer.accept(properties);

        store = new RocksDbSearchStore(properties, new ObjectMapper());
        store.open();
        SearchTokenizer tokenizer = new SearchTokenizer();
        KeywordExtractor keywordExtractor = new KeywordExtractor(tokenizer);
        RetrievalRuleService ruleService = retrievalRuleService();
        QueryIntentClassifier intentClassifier = new QueryIntentClassifier(tokenizer, ruleService);
        QueryExpander queryExpander = new QueryExpander(tokenizer, intentClassifier, ruleService);
        luceneStore = new LuceneDocumentIndexService(
            properties,
            tokenizer,
            new TextChunker(),
            keywordExtractor,
            queryExpander,
            new ChunkTypeClassifier(ruleService),
            new ChunkReranker(properties),
            mock(SearchFeedbackService.class)
        );
        luceneStore.open();
        return new SearchService(
            store,
            luceneStore,
            tokenizer,
            new DocumentTextExtractor(properties),
            keywordExtractor,
            queryExpander,
            properties
        );
    }

    private SearchIndexData emptyIndex() {
        return new SearchIndexData(List.of(), List.of(), List.of(), List.of());
    }

    private RetrievalRuleService retrievalRuleService() {
        RetrievalRuleService ruleService = mock(RetrievalRuleService.class);
        when(ruleService.snapshot()).thenReturn(new RetrievalRuleService.RuleSnapshot(
            intentRules(),
            chunkRules(),
            expandRules(),
            semanticLexiconEntries(),
            System.currentTimeMillis()
        ));
        return ruleService;
    }

    private List<RetrievalRuleService.IntentRule> intentRules() {
        return List.of(
            new RetrievalRuleService.IntentRule(
                "troubleshooting",
                List.of("error", "exception", "failure", "failed", "timeout", "\u5f02\u5e38", "\u539f\u56e0"),
                null,
                2,
                10
            ),
            new RetrievalRuleService.IntentRule(
                "how_to",
                List.of("how", "setup", "configure", "steps", "step", "guide"),
                null,
                2,
                8
            ),
            new RetrievalRuleService.IntentRule(
                "data_issue",
                List.of("data", "report", "export", "\u62a5\u8868", "\u6570\u636e"),
                null,
                1,
                6
            )
        );
    }

    private List<RetrievalRuleService.ChunkRule> chunkRules() {
        return List.of(
            new RetrievalRuleService.ChunkRule(
                "troubleshooting",
                List.of("incident", "failure", "failed", "timeout", "retry", "retries", "root cause"),
                null,
                2,
                10
            ),
            new RetrievalRuleService.ChunkRule(
                "step",
                List.of("step", "steps", "configure", "setup", "how to"),
                null,
                2,
                8
            )
        );
    }

    private List<RetrievalRuleService.ExpandRule> expandRules() {
        return List.of(
            new RetrievalRuleService.ExpandRule(
                "troubleshooting",
                "",
                List.of("error", "exception", "failure", "failed", "root cause", "reason"),
                1,
                10
            ),
            new RetrievalRuleService.ExpandRule(
                "how_to",
                "",
                List.of("how", "steps", "guide", "setup", "configure", "signin", "auth"),
                1,
                8
            ),
            new RetrievalRuleService.ExpandRule(
                "",
                "\u62a5\u8868",
                List.of("report", "export", "data"),
                1,
                6
            ),
            new RetrievalRuleService.ExpandRule(
                "",
                "\u5f02\u5e38",
                List.of("error", "exception", "failure", "failed"),
                1,
                6
            ),
            new RetrievalRuleService.ExpandRule(
                "",
                "\u539f\u56e0",
                List.of("reason", "root", "cause"),
                1,
                6
            )
        );
    }

    private List<RetrievalRuleService.SemanticLexiconEntry> semanticLexiconEntries() {
        return List.of(
            new RetrievalRuleService.SemanticLexiconEntry(
                "营收",
                "zh",
                "revenue",
                List.of("收入", "营业收入", "sales", "turnover"),
                "metric",
                "finance",
                2,
                10,
                true
            )
        );
    }
}
