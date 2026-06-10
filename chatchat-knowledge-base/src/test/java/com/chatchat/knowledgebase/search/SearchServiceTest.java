package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    private RocksDbSearchStore store;
    private LuceneSearchStore luceneStore;

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
        assertThat(page.documentCount()).isEqualTo(1);
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
        SearchProperties properties = new SearchProperties();
        properties.setStorePath(tempDir.resolve("rocksdb").toString());
        properties.setFilePath(tempDir.resolve("files").toString());
        properties.setLuceneIndexPath(tempDir.resolve("lucene").toString());
        properties.setChunkSize(120);
        properties.setChunkOverlap(0);
        properties.setLuceneChunksPerDocument(3);

        store = new RocksDbSearchStore(properties, new ObjectMapper());
        store.open();
        SearchTokenizer tokenizer = new SearchTokenizer();
        luceneStore = new LuceneSearchStore(properties, tokenizer);
        luceneStore.open();
        return new SearchService(
            store,
            luceneStore,
            tokenizer,
            new DocumentContentExtractor(),
            properties
        );
    }
}
