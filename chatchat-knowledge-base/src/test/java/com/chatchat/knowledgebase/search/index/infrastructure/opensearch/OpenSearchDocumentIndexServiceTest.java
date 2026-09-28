package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.query.ChunkTypeClassifier;
import com.chatchat.knowledgebase.search.query.KeywordExtractor;
import com.chatchat.knowledgebase.search.query.QueryExpander;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.chatchat.knowledgebase.search.query.TextChunker;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenSearchDocumentIndexServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void databaseDocumentScopeIsIncludedInOpenSearchQuery() {
        OpenSearchDocumentIndexService service = service(new SearchProperties());

        Map<String, Object> query = service.searchQuery("livedata installation",
            List.of("livedata", "installation"), SearchPermissionContext.of(
                "tenant-1", "user-1", List.of()), List.of("allowed-doc"));

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        assertThat((List<Object>) bool.get("filter"))
            .contains(Map.of("terms", Map.of("fileId", List.of("allowed-doc"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void superAdminRoleNameDoesNotBypassDocumentVisibilityFilter() {
        SearchProperties properties = new SearchProperties();
        properties.setTenantIsolationEnabled(true);
        OpenSearchDocumentIndexService service = service(properties);

        Map<String, Object> query = service.searchQuery("private runbook",
            List.of("private", "runbook"), SearchPermissionContext.of(
                "tenant-1", "admin-user", List.of("SUPER_ADMIN")), List.of());

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        assertThat((List<Object>) bool.get("filter"))
            .hasSize(2)
            .contains(Map.of("term", Map.of("tenantId", "tenant-1")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lexicalQueryKeepsExpandedTermsWithinClauseBudget() {
        SearchProperties properties = new SearchProperties();
        properties.setLuceneMaxQueryTerms(80);
        OpenSearchDocumentIndexService service = new OpenSearchDocumentIndexService(
            properties,
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            mock(OpenSearchEmbeddingClient.class),
            new ObjectMapper()
        );
        List<String> expandedTerms = IntStream.range(0, 80)
            .mapToObj(index -> "term" + index)
            .toList();

        Map<String, Object> query = service.searchQuery(
            "分支机构考核 融资融券",
            expandedTerms,
            SearchPermissionContext.system()
        );

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        List<Object> should = (List<Object>) bool.get("should");
        Map<String, Object> tokenMultiMatch = (Map<String, Object>) ((Map<String, Object>) should.get(0)).get("multi_match");
        Map<String, Object> analyzedMultiMatch = (Map<String, Object>) ((Map<String, Object>) should.get(4)).get("multi_match");
        Map<String, Object> pinyinMultiMatch = (Map<String, Object>) ((Map<String, Object>) should.get(5)).get("multi_match");

        assertThat(String.valueOf(tokenMultiMatch.get("query")).split(" ")).hasSize(20);
        assertThat((List<String>) tokenMultiMatch.get("fields")).hasSize(6);
        assertThat(analyzedMultiMatch.get("query")).isEqualTo("分支机构考核 融资融券");
        assertThat((List<String>) analyzedMultiMatch.get("fields")).hasSize(6);
        assertThat(String.valueOf(pinyinMultiMatch.get("query")).split(" ")).hasSize(10);
        assertThat((List<String>) pinyinMultiMatch.get("fields")).hasSize(4);
        assertThat(bool.get("minimum_should_match")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lexicalQueryTruncatesLongModelGeneratedText() {
        SearchProperties properties = new SearchProperties();
        OpenSearchDocumentIndexService service = new OpenSearchDocumentIndexService(
            properties,
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            mock(OpenSearchEmbeddingClient.class),
            new ObjectMapper()
        );

        Map<String, Object> query = service.searchQuery(
            "x".repeat(2_000),
            List.of("asset", "revenue"),
            SearchPermissionContext.system()
        );

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        List<Object> should = (List<Object>) bool.get("should");
        Map<String, Object> analyzedMultiMatch =
            (Map<String, Object>) ((Map<String, Object>) should.get(4)).get("multi_match");
        assertThat(String.valueOf(analyzedMultiMatch.get("query"))).hasSize(500);
    }

    @Test
    void recognizesNestedClauseOverflowForSimplifiedRetry() {
        OpenSearchDocumentIndexService service = new OpenSearchDocumentIndexService(
            new SearchProperties(),
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            mock(OpenSearchEmbeddingClient.class),
            new ObjectMapper()
        );

        assertThat(service.isClauseOverflow(new IllegalStateException(
            "too_many_nested_clauses: maxClauseCount is set to 1024"))).isTrue();
        assertThat(service.isClauseOverflow(new IllegalStateException("connection reset"))).isFalse();
    }

    @Test
    void boundsLocalRerankBatchSizeWithoutReducingCandidateRecall() {
        SearchProperties properties = new SearchProperties();
        properties.getOpenSearch().getEmbedding().setDimension(2560);
        properties.getOpenSearch().setVectorRerankMaxBytes(4 * 1024 * 1024);
        OpenSearchDocumentIndexService service = service(properties);

        assertThat(service.localRerankBatchSize()).isEqualTo(102);
        assertThat(service.resultSourceFilter(false)).doesNotContain("contentVector");
        assertThat(service.resultSourceFilter(false)).contains("fileId", "sectionId", "chunkId", "sourceVersion");
        assertThat(service.resultSourceFilter(true)).contains("contentVector");
    }

    private OpenSearchDocumentIndexService service(SearchProperties properties) {
        return new OpenSearchDocumentIndexService(
            properties,
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            mock(OpenSearchEmbeddingClient.class),
            new ObjectMapper()
        );
    }
}
