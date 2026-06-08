package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTokenizerTest {

    private final SearchTokenizer tokenizer = new SearchTokenizer();

    @Test
    void tokenizesChineseResearchTermsWithNgrams() {
        assertThat(tokenizer.tokenize("半导体设备国产化进度"))
            .contains("半导体", "国产化", "设备", "进度");
    }

    @Test
    void splitsFilterValuesAsExactTerms() {
        assertThat(tokenizer.splitFilter("半导体 AI服务器；政策"))
            .containsExactly("半导体", "ai服务器", "政策");
    }
}
