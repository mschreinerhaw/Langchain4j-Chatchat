package com.chatchat.mcpserver.search.index;

import com.chatchat.mcpserver.sql.metadata.MetadataColumn;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetSemanticProfileTest {

    @Test
    void projectsColumnNamesCommentsTypesAndKeysIntoSearchText() {
        TableLocation table = new TableLocation("ds", "catalog", "schema", "position_table",
            "BASE TABLE", 10L, "持仓快照", "交易数据", 0.0D);
        MetadataColumn column = new MetadataColumn("ds", "catalog", "schema", "position_table",
            "market_value", "decimal", "decimal(20,4)", "PRI", "证券市值", false, 1);

        AssetSemanticProfile profile = AssetSemanticProfile.table(table, List.of(column), "数据源说明");

        assertThat(profile.indexText())
            .contains("持仓快照", "交易数据", "market_value", "证券市值", "decimal", "PRI");
        assertThat(profile.fields()).singleElement().satisfies(field ->
            assertThat(field.name()).isEqualTo("market_value"));
    }

    @Test
    void projectsApiInputOutputAndCapabilityContractsWithoutRequestPayloads() {
        AssetSemanticProfile profile = AssetSemanticProfile.api(
            "资产查询", "POST", "{inputSchema}", "{outputSchema}", "{capabilitySpec}");

        assertThat(profile.indexText())
            .contains("资产查询", "inputSchema", "outputSchema", "capabilitySpec");
    }
}
