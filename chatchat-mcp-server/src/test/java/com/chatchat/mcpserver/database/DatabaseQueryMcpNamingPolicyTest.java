package com.chatchat.mcpserver.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseQueryMcpNamingPolicyTest {

    private final DatabaseQueryMcpNamingPolicy policy = new DatabaseQueryMcpNamingPolicy();

    @Test
    void prefixesProtocolNameAndDisplayTitleWithBusinessCategory() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("sample_bond_yield_curve_latest");
        config.setTitle("最新中债国债收益率曲线");
        config.setCapabilityCategory("market_data");
        config.setBusinessGroupName("市场行情");

        assertThat(policy.toolName(config)).isEqualTo("market_data_bond_yield_curve_latest");
        assertThat(policy.title(config)).isEqualTo("【市场行情】最新中债国债收益率曲线");
    }

    @Test
    void doesNotRepeatAnExistingCategoryPrefix() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("data_validation_financial_dataset_freshness");
        config.setTitle("【数据核验】金融数据资产覆盖与新鲜度");
        config.setCapabilityCategory("data_validation");
        config.setBusinessGroupName("数据核验");

        assertThat(policy.toolName(config))
            .isEqualTo("data_validation_financial_dataset_freshness");
        assertThat(policy.title(config))
            .isEqualTo("【数据核验】金融数据资产覆盖与新鲜度");
    }

    @Test
    void removesARepeatedLeadingCategoryWordFromLegacyNames() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("sample_market_latest_movers");
        config.setCapabilityCategory("market_data");

        assertThat(policy.toolName(config)).isEqualTo("market_data_latest_movers");
    }
}
