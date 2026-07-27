package com.chatchat.mcpserver.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataQueryCategoryServiceTest {

    @Test
    void assignsDataValidationAsDedicatedFinancialCapability() {
        DataQueryCategoryRepository categories = mock(DataQueryCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        DataQueryCategory validation = category(
            "validation-id", "data_validation", "数据核验",
            "[\"核验\",\"一致性\",\"完整性\",\"差异\"]", 60);
        DataQueryCategory exploration = category(
            "exploration-id", "data_asset_exploration", "数据资产探索",
            "[\"元数据\",\"字段\"]", 80);
        when(categories.findByEnabledTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(validation, exploration));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("validate_customer_asset");
        config.setTitle("客户资产一致性核验");
        config.setDescription("检查客户资产在不同系统中的差异");

        service.assignBest(config);

        assertThat(config.getCategoryId()).isEqualTo("validation-id");
        assertThat(config.getCapabilityCategory()).isEqualTo("data_validation");
        assertThat(config.getBusinessGroupName()).isEqualTo("数据核验");
        assertThat(config.getIndexTagsJson()).contains("一致性", "核验");
    }

    private DataQueryCategory category(String id, String code, String name, String keywords, int order) {
        DataQueryCategory category = new DataQueryCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setDescription(name + "专项");
        category.setDomain("finance");
        category.setKeywordsJson(keywords);
        category.setSortOrder(order);
        category.setEnabled(true);
        return category;
    }
}
