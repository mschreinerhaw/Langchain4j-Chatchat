package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.embedding.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    private RocksDbSearchStore store;

    @AfterEach
    void closeStore() {
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

    private SearchService newSearchService() {
        SearchProperties properties = new SearchProperties();
        properties.setStorePath(tempDir.resolve("rocksdb").toString());
        properties.setFilePath(tempDir.resolve("files").toString());
        properties.setEmbeddingEnabled(false);

        store = new RocksDbSearchStore(properties, new ObjectMapper());
        store.open();
        return new SearchService(
            store,
            new SearchTokenizer(),
            new DocumentContentExtractor(),
            properties,
            emptyEmbeddingProvider()
        );
    }

    private ObjectProvider<EmbeddingService> emptyEmbeddingProvider() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingService getObject(Object... args) {
                return null;
            }

            @Override
            public EmbeddingService getObject() {
                return null;
            }

            @Override
            public EmbeddingService getIfAvailable() {
                return null;
            }
        };
    }
}
