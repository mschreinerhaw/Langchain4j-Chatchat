package com.chatchat.mcpserver.database;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/database-query/categories")
public class DataQueryCategoryAdminController {

    private final BusinessCategoryService service;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<CategoryView>> list() {
        return ApiResponse.success(service.listAll().stream().map(this::view).toList());
    }

    @PostMapping
    public ApiResponse<CategoryView> create(@RequestBody CategoryRequest request) {
        return ApiResponse.success(view(service.save(entity(null, request))));
    }

    @PutMapping("/{id}")
    public ApiResponse<CategoryView> update(@PathVariable String id, @RequestBody CategoryRequest request) {
        return ApiResponse.success(view(service.save(entity(id, request))));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.success(null);
    }

    private BusinessCategory entity(String id, CategoryRequest request) {
        BusinessCategory category = new BusinessCategory();
        category.setId(id);
        category.setCode(request.code());
        category.setName(request.name());
        category.setDescription(request.description());
        category.setDomain(request.domain());
        category.setKeywordsJson(com.chatchat.agents.protocol.ModelProtocolJson.compact(
            request.keywords() == null ? List.of() : request.keywords()));
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setEnabled(request.enabled() == null || request.enabled());
        return category;
    }

    private CategoryView view(BusinessCategory category) {
        List<String> keywords;
        try {
            keywords = objectMapper.readValue(category.getKeywordsJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            keywords = List.of();
        }
        return new CategoryView(category.getId(), category.getCode(), category.getName(),
            category.getDescription(), category.getDomain(), keywords,
            category.getSortOrder(), category.isEnabled());
    }

    public record CategoryRequest(String code, String name, String description, String domain,
                                  List<String> keywords, Integer sortOrder, Boolean enabled) {}
    public record CategoryView(String id, String code, String name, String description, String domain,
                               List<String> keywords, int sortOrder, boolean enabled) {}
}
