package com.chatchat.mcpserver.database.category;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigRepository;

import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataQueryCategoryServiceTest {

    @Test
    void createsDefaultCategoryAndAssignsUnclassifiedQueriesOnStartup() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setToolName("query_without_category");
        when(categories.findByCodeIgnoreCase("default")).thenReturn(Optional.empty());
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> {
            BusinessCategory category = invocation.getArgument(0);
            category.setId("default-id");
            return category;
        });
        when(queries.findAll()).thenReturn(List.of(query));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());

        service.initialize();

        assertThat(query.getCategoryId()).isEqualTo("default-id");
        assertThat(query.getCapabilityCategory()).isEqualTo("default");
        verify(categories).save(any(BusinessCategory.class));
        verify(queries).save(query);
    }

    @Test
    void updatesReferencedQueryMetadataWhenUserEditsCategory() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        BusinessCategory category = category(
            "market-id", "market_data", "市场行情",
            "[\"融资融券\",\"行情\"]", 10);
        DatabaseQueryConfig query = categorizedQuery(
            "margin-id", "query_margin_trade_latest", category);
        when(categories.findById("market-id")).thenReturn(Optional.of(category));
        when(categories.findByCodeIgnoreCase("market_data")).thenReturn(Optional.of(category));
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queries.findByCategoryId("market-id")).thenReturn(List.of(query));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());

        BusinessCategory draft = category(
            "market-id", "market_data", "行情与两融",
            "[\"融资融券\",\"两融余额\"]", 20);
        BusinessCategory saved = service.save(draft);

        assertThat(saved.getName()).isEqualTo("行情与两融");
        assertThat(query.getBusinessGroupName()).isEqualTo("行情与两融");
        assertThat(query.getBusinessGroupDescription()).isEqualTo("行情与两融专项");
        verify(queries).save(query);
    }

    @Test
    void resolvesRealMarginTradingRequestToMarketDataCategory() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        BusinessCategory market = category(
            "market-id", "market_data", "\u5e02\u573a\u884c\u60c5",
            "[\"\u878d\u8d44\u878d\u5238\",\"\u4f59\u989d\",\"\u884c\u60c5\",\"margin trade\"]", 10);
        BusinessCategory customer = category(
            "customer-id", "customer_analysis", "\u5ba2\u6237\u5206\u6790",
            "[\"\u5ba2\u6237\",\"\u753b\u50cf\",\"\u5ba2\u6237\u8d44\u4ea7\",\"customer\"]", 30);
        when(categories.findByEnabledTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(market, customer));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());
        DatabaseQueryConfig marginQuery = categorizedQuery(
            "margin-id", "query_margin_trade_latest", market);
        DatabaseQueryConfig customerQuery = categorizedQuery(
            "customer-query-id", "query_customer_profile", customer);
        Map<String, Object> filters = Map.of(
            "intent", "\u5206\u6790\u6700\u65b0\u878d\u8d44\u878d\u5238\u4f59\u989d\u53ca\u8f83\u4e0a\u4e2a\u4ea4\u6613\u65e5\u53d8\u5316");

        DataQueryCategoryService.CategoryResolution resolution = service.resolve(
            mapLike(filters), List.of(marginQuery, customerQuery));

        assertThat(resolution.category()).isSameAs(market);
        assertThat(resolution.categoryRequired()).isFalse();
        assertThat(resolution.candidates()).containsExactly(market, customer);
    }

    @Test
    void resolvesMarginTradingRequestWhenNoExplicitCategoryFilterIsPresent() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        BusinessCategory market = category(
            "market-id", "market_data", "市场行情",
            "[\"融资融券\",\"margin trading\",\"securities lending\"]", 10);
        when(categories.findByEnabledTrueOrderBySortOrderAscNameAsc()).thenReturn(List.of(market));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());

        DataQueryCategoryService.CategoryResolution resolution = service.resolve(
            new DataQueryCategoryService.MapLike() {
                @Override
                public String first(String... keys) {
                    return null;
                }

                @Override
                public String joinedText() {
                    return "查询融资融券最新数据以进行观察分析 margin trading securities lending "
                        + "retrievalSignals=[explicit:false, false]";
                }
            },
            List.of(categorizedQuery("margin-id", "query_margin_trade_latest", market)));

        assertThat(resolution.category()).isSameAs(market);
        assertThat(resolution.categoryRequired()).isFalse();
    }

    @Test
    void assignsDefaultCategoryWhenUserDoesNotChooseOne() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        BusinessCategory fallback = category(
            "default-id", "default", "默认分类", "[\"默认\",\"未分类\"]", 10_000);
        fallback.setDomain("default");
        when(categories.findByCodeIgnoreCase("default")).thenReturn(Optional.of(fallback));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("validate_customer_asset");
        config.setTitle("客户资产一致性核验");
        config.setDescription("检查客户资产在不同系统中的差异");

        service.assignBest(config);

        assertThat(config.getCategoryId()).isEqualTo("default-id");
        assertThat(config.getCapabilityCategory()).isEqualTo("default");
        assertThat(config.getBusinessGroupName()).isEqualTo("默认分类");
    }

    @Test
    void fallsBackToDefaultWhenRequestedCategoryDoesNotExist() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        DatabaseQueryConfigRepository queries = mock(DatabaseQueryConfigRepository.class);
        BusinessCategory fallback = category(
            "default-id", "default", "默认分类", "[\"默认\",\"未分类\"]", 10_000);
        BusinessCategory market = category(
            "market-id", "market_data", "市场行情", "[\"行情\",\"融资融券\"]", 10);
        when(categories.findByEnabledTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(market, fallback));
        DataQueryCategoryService service = new DataQueryCategoryService(
            categories, queries, new ObjectMapper());

        DataQueryCategoryService.CategoryResolution resolution = service.resolve(
            mapLike(Map.of("category", "missing-category")),
            List.of(
                categorizedQuery("default-query", "query_default", fallback),
                categorizedQuery("market-query", "query_market", market)
            ));

        assertThat(resolution.category()).isSameAs(fallback);
        assertThat(resolution.categoryRequired()).isFalse();
        assertThat(resolution.fallbackUsed()).isTrue();
    }

    private BusinessCategory category(String id, String code, String name, String keywords, int order) {
        BusinessCategory category = new BusinessCategory();
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

    private DatabaseQueryConfig categorizedQuery(String id, String toolName, BusinessCategory category) {
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setId(id);
        query.setToolName(toolName);
        query.setCategoryId(category.getId());
        query.setCapabilityCategory(category.getCode());
        query.setBusinessGroup(category.getCode());
        query.setEnabled(true);
        return query;
    }

    private DataQueryCategoryService.MapLike mapLike(Map<String, Object> filters) {
        return new DataQueryCategoryService.MapLike() {
            @Override
            public String first(String... keys) {
                for (String key : keys) {
                    Object value = filters.get(key);
                    if (value != null && !String.valueOf(value).isBlank()) {
                        return String.valueOf(value);
                    }
                }
                return "";
            }

            @Override
            public String joinedText() {
                return String.join(" ", filters.values().stream().map(String::valueOf).toList());
            }
        };
    }
}
