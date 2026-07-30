package com.chatchat.mcpserver.api;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiServiceCategoryServiceTest {

    @Test
    void assignsDefaultCategoryWhenUserDoesNotChooseOne() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        ApiServiceConfigRepository services = mock(ApiServiceConfigRepository.class);
        when(categories.findByCodeIgnoreCase("default")).thenReturn(Optional.empty());
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> {
            BusinessCategory category = invocation.getArgument(0);
            category.setId("default-id");
            return category;
        });
        ApiServiceCategoryService service = new ApiServiceCategoryService(
            categories, services, new ObjectMapper());
        ApiServiceConfig config = new ApiServiceConfig();
        config.setBusinessGroup(null);

        service.assign(config);

        assertThat(config.getCategoryId()).isEqualTo("default-id");
        assertThat(config.getBusinessGroup()).isEqualTo("default");
        assertThat(config.getBusinessGroupName()).isEqualTo("默认分类");
    }

    @Test
    void updatesReferencedApiMetadataWhenUserEditsCategory() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        ApiServiceConfigRepository services = mock(ApiServiceConfigRepository.class);
        BusinessCategory category = category(
            "customer-id", "customer_service", "客户服务", "客户服务接口", "[\"客户\",\"画像\"]");
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("profile-id");
        config.setCategoryId(category.getId());
        config.setBusinessGroup(category.getCode());
        when(categories.findById("customer-id")).thenReturn(Optional.of(category));
        when(categories.findByCodeIgnoreCase("customer_service")).thenReturn(Optional.of(category));
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(services.findByCategoryId("customer-id")).thenReturn(List.of(config));
        ApiServiceCategoryService service = new ApiServiceCategoryService(
            categories, services, new ObjectMapper());

        BusinessCategory draft = category(
            "customer-id", "customer_service", "客户经营", "客户经营与画像接口", "[\"客户\",\"经营\"]");
        BusinessCategory saved = service.save(draft);

        assertThat(saved.getName()).isEqualTo("客户经营");
        assertThat(config.getBusinessGroupName()).isEqualTo("客户经营");
        assertThat(config.getBusinessGroupDescription()).isEqualTo("客户经营与画像接口");
        verify(services).save(config);
    }

    @Test
    void fallsBackToDefaultWhenRequestedCategoryDoesNotExist() {
        BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
        ApiServiceConfigRepository services = mock(ApiServiceConfigRepository.class);
        BusinessCategory fallback = category(
            "default-id", "default", "默认分类", "未匹配分类的接口", "[\"默认\",\"未分类\"]");
        BusinessCategory orders = category(
            "orders-id", "orders", "订单服务", "订单查询接口", "[\"订单\"]");
        when(categories.findByEnabledTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(orders, fallback));
        ApiServiceCategoryService service = new ApiServiceCategoryService(
            categories, services, new ObjectMapper());
        ApiServiceConfig fallbackApi = new ApiServiceConfig();
        fallbackApi.setCategoryId(fallback.getId());
        fallbackApi.setBusinessGroup(fallback.getCode());
        ApiServiceConfig ordersApi = new ApiServiceConfig();
        ordersApi.setCategoryId(orders.getId());
        ordersApi.setBusinessGroup(orders.getCode());

        ApiServiceCategoryService.CategoryResolution resolution = service.resolve(
            mapLike(Map.of("category", "missing-category")), List.of(fallbackApi, ordersApi));

        assertThat(resolution.category()).isSameAs(fallback);
        assertThat(resolution.categoryRequired()).isFalse();
        assertThat(resolution.fallbackUsed()).isTrue();
    }

    private BusinessCategory category(String id,
                                        String code,
                                        String name,
                                        String description,
                                        String keywords) {
        BusinessCategory category = new BusinessCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setDescription(description);
        category.setKeywordsJson(keywords);
        category.setSortOrder(10);
        category.setEnabled(true);
        return category;
    }

    private ApiServiceCategoryService.MapLike mapLike(Map<String, Object> filters) {
        return new ApiServiceCategoryService.MapLike() {
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
