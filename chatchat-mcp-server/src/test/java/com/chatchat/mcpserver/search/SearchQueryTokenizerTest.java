package com.chatchat.mcpserver.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryTokenizerTest {

    @Test
    void segmentsMixedChineseAndEnglishWithoutBusinessDictionary() {
        assertThat(SearchQueryTokenizer.terms("融资融券 margin-trade"))
            .contains("融资融券 margin trade", "融资", "资融", "融券", "margin", "trade");
    }

    @Test
    void appliesUnicodeCompatibilityNormalization() {
        assertThat(SearchQueryTokenizer.terms("ＭｙＳＱＬ＿STATUS"))
            .contains("mysql status", "mysql", "status");
    }
}
