package com.chatchat.knowledgebase.search.index.infrastructure.opensearch;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.query.application.ChunkTypeClassifier;
import com.chatchat.knowledgebase.search.query.application.KeywordExtractor;
import com.chatchat.knowledgebase.search.query.application.QueryExpander;
import com.chatchat.knowledgebase.search.query.application.SearchTokenizer;
import com.chatchat.knowledgebase.search.query.application.TextChunker;
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
    void compactBudgetFallbackPreservesFullIdentifierAndAuthorizationFilters() {
        var properties=new SearchProperties();properties.setTenantIsolationEnabled(true);
        var service=service(properties);
        String identifier="assetknowledgeac348c6b6a34484f97f3e30510de0e54";
        var body=service.compactSearchBody(identifier,10,SearchPermissionContext.of("tenant","owner",List.of()),List.of("allowed-doc"));
        try(var optimizer=new com.chatchat.common.retrieval.query.SearchQueryOptimizer()) {
            var prepared=optimizer.prepare(body,com.chatchat.common.retrieval.query.SearchQueryOptimizer.Mode.TEXT);
            assertThat(prepared.body().toString()).contains(identifier,"allowed-doc","tenant");
            assertThat(prepared.body()).isEqualTo(body);
        }
    }

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
    void superAdminRoleNameDoesNotBypassAuthoritativeDocumentScope() {
        SearchProperties properties = new SearchProperties();
        properties.setTenantIsolationEnabled(true);
        OpenSearchDocumentIndexService service = service(properties);

        Map<String, Object> query = service.searchQuery("private runbook",
            List.of("private", "runbook"), SearchPermissionContext.of(
                "tenant-1", "admin-user", List.of("SUPER_ADMIN")), List.of("authorized-doc"));

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        assertThat((List<Object>) bool.get("filter"))
            .hasSize(2)
            .contains(Map.of("term", Map.of("tenantId", "tenant-1")),
                Map.of("terms", Map.of("fileId", List.of("authorized-doc"))));
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
    void lexicalQueryExtractsFullInputIncludingTrailingEntities() {
        SearchProperties properties = new SearchProperties();
        OpenSearchDocumentIndexService service = new OpenSearchDocumentIndexService(
            properties,
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            new ObjectMapper()
        );

        Map<String, Object> query = service.searchQuery(
            "please analyze revenue and cashflow ".repeat(100) + " 600519",
            List.of("asset", "revenue"),
            SearchPermissionContext.system()
        );

        Map<String, Object> bool = (Map<String, Object>) query.get("bool");
        List<Object> should = (List<Object>) bool.get("should");
        Map<String, Object> analyzedMultiMatch =
            (Map<String, Object>) ((Map<String, Object>) should.get(4)).get("multi_match");
        assertThat(String.valueOf(analyzedMultiMatch.get("query"))).contains("600519").doesNotContain("please analyze revenue and cashflow ".repeat(100));
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
            new ObjectMapper()
        );

        assertThat(service.isClauseOverflow(new IllegalStateException(
            "too_many_nested_clauses: maxClauseCount is set to 1024"))).isTrue();
        assertThat(service.isClauseOverflow(new IllegalStateException("connection reset"))).isFalse();
    }

    @Test
    void documentIndexIsLexicalEvenWhenSharedSkillEmbeddingIsConfigured() {
        SearchProperties properties = new SearchProperties();
        properties.getOpenSearch().getEmbedding().setEnabled(true);
        properties.getOpenSearch().getEmbedding().setDimension(2560);
        OpenSearchDocumentIndexService service = service(properties);
        assertThat(service.indexCreateBody().toString()).doesNotContain("knn", "contentVector");
        assertThat(service.resultSourceFilter()).contains("fileId", "sectionId", "chunkId", "sourceVersion")
            .doesNotContain("contentVector");
    }

    private OpenSearchDocumentIndexService service(SearchProperties properties) {
        return new OpenSearchDocumentIndexService(
            properties,
            mock(SearchTokenizer.class),
            mock(TextChunker.class),
            mock(KeywordExtractor.class),
            mock(QueryExpander.class),
            mock(ChunkTypeClassifier.class),
            new ObjectMapper()
        );
    }
}
