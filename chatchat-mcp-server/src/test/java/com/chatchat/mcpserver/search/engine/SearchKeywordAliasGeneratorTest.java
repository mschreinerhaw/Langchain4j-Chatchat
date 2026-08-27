package com.chatchat.mcpserver.search.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKeywordAliasGeneratorTest {

    @Test
    void derivesChinesePinyinInitialsAndEnglishWordInitials() {
        assertThat(SearchKeywordAliasGenerator.aliases(
            "客户资产中心", "Customer Asset Service", "RealtimeOrderGateway", "银行中心"))
            .contains("khzczx", "cas", "rog", "yhzx");
    }

    @Test
    void ignoresSingleWordAndLongNoisyAliases() {
        assertThat(SearchKeywordAliasGenerator.aliases(
            "database", "A",
            "This Extremely Long English Asset Template Identifier Name Produces More Than Sixteen Initials Today And Must Be Ignored"))
            .isEmpty();
    }
}
