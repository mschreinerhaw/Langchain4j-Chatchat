package com.chatchat.knowledgebase.search.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExpanderExactDocumentQueryTest {

    @Test
    void exactDocumentRecallDoesNotAddSemanticOrBilingualTerms() {
        QueryExpander expander = new QueryExpander(new SearchTokenizer(), null, null);

        List<String> terms = QueryExpander.withoutExpansion(() ->
            expander.expandTokens(List.of("livedata", "安装说明"), "HOW_TO", "livedata 安装说明"));
        String query = QueryExpander.withoutExpansion(() ->
            expander.normalizeQuery("livedata 安装说明"));

        assertThat(terms).containsExactly("livedata", "安装说明");
        assertThat(query).isEqualTo("livedata 安装说明");
    }
}
