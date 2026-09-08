package com.chatchat.mcpserver.search.query;

import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTermNormalizerTest {

    @Test
    void expandsConfiguredTermsBidirectionallyWithoutEmbeddedVocabulary() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.getBusinessTerms().setSynonymGroups(Map.of(
            "完整术语", List.of("简称", "替代称呼")));
        BusinessTermNormalizer normalizer = new BusinessTermNormalizer(properties);

        assertThat(normalizer.expansions("简称余额"))
            .contains("简称余额", "完整术语", "完整术语余额", "替代称呼", "替代称呼余额");
        assertThat(normalizer.enrichIndexText("完整术语明细"))
            .contains("简称明细", "替代称呼明细");
        assertThat(normalizer.metadata())
            .containsEntry("source", "operator_configuration")
            .containsEntry("embeddedBusinessTerms", false);
    }

    @Test
    void keepsQueriesUnchangedWhenNoOperatorDictionaryIsConfigured() {
        BusinessTermNormalizer normalizer = new BusinessTermNormalizer(new LuceneSearchProperties());

        assertThat(normalizer.expansions("任意查询")).containsExactly("任意查询");
        assertThat(normalizer.expandQueries(List.of("任意查询"))).isEmpty();
    }
}
