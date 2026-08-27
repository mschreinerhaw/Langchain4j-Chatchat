package com.chatchat.mcpserver.api.category;

import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigRepository;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryRepository;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ApiServiceCategoryService {

    private final BusinessCategoryRepository repository;
    private final ApiServiceConfigRepository apiRepository;
    private final ObjectMapper objectMapper;

    public ApiServiceCategoryService(BusinessCategoryRepository repository,
                                     ApiServiceConfigRepository apiRepository,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.apiRepository = apiRepository;
        this.objectMapper = objectMapper;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 200)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        for (ApiServiceConfig config : apiRepository.findAll()) {
            BusinessCategory category = existingExplicitCategory(config)
                .orElseGet(() -> ensureCategory(BusinessCategoryService.DEFAULT_CODE,
                    BusinessCategoryService.DEFAULT_NAME, "用户未指定业务分类时自动归入此分类", 10_000));
            apply(config, category);
            apiRepository.save(config);
        }
    }

    @Transactional(readOnly = true)
    public List<BusinessCategory> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<BusinessCategory> listEnabled() {
        return repository.findByEnabledTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public BusinessCategory require(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new IllegalArgumentException("API service category is required");
        }
        return repository.findById(idOrCode)
            .or(() -> repository.findByCodeIgnoreCase(idOrCode))
            .orElseThrow(() -> new IllegalArgumentException("API service category not found: " + idOrCode));
    }

    @Transactional
    public BusinessCategory save(BusinessCategory draft) {
        if (draft == null) throw new IllegalArgumentException("category is required");
        String code = normalizeCode(draft.getCode());
        String name = required(draft.getName(), "name");
        repository.findByCodeIgnoreCase(code)
            .filter(existing -> draft.getId() == null || !existing.getId().equals(draft.getId()))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("category code already exists: " + code);
            });
        BusinessCategory target = draft.getId() == null || draft.getId().isBlank()
            ? new BusinessCategory()
            : repository.findById(draft.getId())
                .orElseThrow(() -> new IllegalArgumentException("API service category not found: " + draft.getId()));
        target.setCode(code);
        target.setName(name);
        target.setDescription(text(draft.getDescription()).trim());
        target.setDomain(first(draft.getDomain(), "finance"));
        target.setKeywordsJson(writeKeywords(readKeywords(draft.getKeywordsJson())));
        target.setSortOrder(draft.getSortOrder());
        target.setEnabled(draft.isEnabled());
        BusinessCategory saved = repository.save(target);
        List<ApiServiceConfig> referenced = apiRepository.findByCategoryId(saved.getId());
        if (referenced != null) {
            referenced.forEach(config -> {
                apply(config, saved);
                apiRepository.save(config);
            });
        }
        return saved;
    }

    @Transactional
    public void delete(String id) {
        if (apiRepository.countByCategoryId(id) > 0) {
            throw new IllegalArgumentException("Category is referenced by API services");
        }
        repository.deleteById(id);
    }

    @Transactional
    public void assign(ApiServiceConfig config) {
        BusinessCategory category;
        if (config.getCategoryId() != null && !config.getCategoryId().isBlank()) {
            category = require(config.getCategoryId());
        } else {
            category = existingExplicitCategory(config)
                .orElseGet(() -> ensureCategory(BusinessCategoryService.DEFAULT_CODE,
                    BusinessCategoryService.DEFAULT_NAME, "用户未指定业务分类时自动归入此分类", 10_000));
        }
        if (!category.isEnabled()) {
            throw new IllegalArgumentException("API service category is disabled: " + category.getCode());
        }
        apply(config, category);
    }

    public void applyExplicit(ApiServiceConfig config, String categoryIdOrCode) {
        BusinessCategory category = require(categoryIdOrCode);
        if (!category.isEnabled()) {
            throw new IllegalArgumentException("API service category is disabled: " + category.getCode());
        }
        apply(config, category);
    }

    public CategoryResolution resolve(MapLike filters, List<ApiServiceConfig> configs) {
        List<BusinessCategory> enabled = listEnabled();
        List<BusinessCategory> active = enabled.stream()
            .filter(category -> configs.stream().anyMatch(config -> category.getId().equals(config.getCategoryId())
                || category.getCode().equalsIgnoreCase(text(config.getBusinessGroup()))))
            .toList();
        String explicit = text(filters.first("categoryId", "category_id", "businessGroup",
            "business_group", "group", "groupName", "group_name", "category")).trim();
        if (!explicit.isBlank()) {
            return active.stream()
                .filter(category -> matches(category, explicit))
                .findFirst()
                .map(category -> new CategoryResolution(category, false, active))
                .orElseGet(() -> fallbackResolution(active, enabled));
        }
        String corpus = filters.joinedText().toLowerCase(Locale.ROOT);
        List<CategoryScore> scores = active.stream()
            .map(category -> new CategoryScore(category, score(category, corpus)))
            .filter(item -> item.score() > 0)
            .sorted(Comparator.comparingInt(CategoryScore::score).reversed()
                .thenComparingInt(item -> item.category().getSortOrder()))
            .toList();
        if (!scores.isEmpty() && (scores.size() == 1 || scores.get(0).score() > scores.get(1).score())) {
            return new CategoryResolution(scores.get(0).category(), false, active);
        }
        if (active.size() == 1) return new CategoryResolution(active.get(0), false, active);
        return fallbackResolution(active, enabled);
    }

    private CategoryResolution fallbackResolution(List<BusinessCategory> active,
                                                  List<BusinessCategory> enabled) {
        return enabled.stream()
            .filter(category -> BusinessCategoryService.DEFAULT_CODE.equalsIgnoreCase(category.getCode()))
            .findFirst()
            .map(category -> new CategoryResolution(category, false, active, true))
            .orElse(new CategoryResolution(null, true, active));
    }

    public List<String> keywords(BusinessCategory category) {
        return category == null ? List.of() : readKeywords(category.getKeywordsJson());
    }

    private int score(BusinessCategory category, String corpus) {
        if (corpus.isBlank()) return 0;
        int score = contains(corpus, category.getCode()) ? 8 : 0;
        score += contains(corpus, category.getName()) ? 8 : 0;
        score += contains(corpus, category.getDescription()) ? 3 : 0;
        score += readKeywords(category.getKeywordsJson()).stream()
            .mapToInt(keyword -> contains(corpus, keyword) ? 5 : 0).sum();
        return score;
    }

    private java.util.Optional<BusinessCategory> existingExplicitCategory(ApiServiceConfig config) {
        if (config.getCategoryId() != null && !config.getCategoryId().isBlank()) {
            return repository.findById(config.getCategoryId());
        }
        String group = text(config.getBusinessGroup());
        if (group.isBlank() || "default".equalsIgnoreCase(group)) return java.util.Optional.empty();
        return repository.findByCodeIgnoreCase(group);
    }

    private java.util.Optional<BusinessCategory> classify(ApiServiceConfig config,
                                                          List<BusinessCategory> categories) {
        String corpus = String.join(" ", List.of(
            text(config.getToolName()), text(config.getTitle()), text(config.getDescription()),
            text(config.getCapabilitySpecJson()), text(config.getDependencySpecJson())
        )).toLowerCase(Locale.ROOT);
        return categories.stream()
            .filter(category -> !"default".equalsIgnoreCase(category.getCode()))
            .map(category -> new CategoryScore(category, score(category, corpus)))
            .filter(item -> item.score() > 0)
            .sorted(Comparator.comparingInt(CategoryScore::score).reversed()
                .thenComparingInt(item -> item.category().getSortOrder()))
            .map(CategoryScore::category)
            .findFirst();
    }

    private boolean matches(BusinessCategory category, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return category.getId().equals(value)
            || category.getCode().equalsIgnoreCase(value)
            || text(category.getName()).toLowerCase(Locale.ROOT).equals(normalized);
    }

    private boolean contains(String corpus, String value) {
        return value != null && !value.isBlank() && corpus.contains(value.toLowerCase(Locale.ROOT));
    }

    private BusinessCategory ensureCategory(String code, String name, String description, int sortOrder) {
        String normalizedCode = normalizeCode(code);
        return repository.findByCodeIgnoreCase(normalizedCode).orElseGet(() -> {
            BusinessCategory category = new BusinessCategory();
            category.setCode(normalizedCode);
            category.setName(first(name, normalizedCode));
            category.setDescription(text(description).trim());
            Set<String> keywords = new LinkedHashSet<>();
            keywords.add(normalizedCode);
            keywords.add(first(name, normalizedCode));
            category.setKeywordsJson(writeKeywords(new ArrayList<>(keywords)));
            category.setSortOrder(sortOrder);
            category.setEnabled(true);
            return repository.save(category);
        });
    }

    private void apply(ApiServiceConfig config, BusinessCategory category) {
        config.setCategoryId(category.getId());
        config.setBusinessGroup(category.getCode());
        config.setBusinessGroupName(category.getName());
        config.setBusinessGroupDescription(category.getDescription());
    }

    private List<String> readKeywords(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeKeywords(List<String> values) {
        return ModelProtocolJson.compact(values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList());
    }

    private String normalizeCode(String value) {
        String code = first(value, "default").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_")
            .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return code.isBlank() ? "default" : code;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public interface MapLike {
        String first(String... keys);
        String joinedText();
    }

    public record CategoryResolution(BusinessCategory category, boolean categoryRequired,
                                     List<BusinessCategory> candidates, boolean fallbackUsed) {
        public CategoryResolution(BusinessCategory category, boolean categoryRequired,
                                  List<BusinessCategory> candidates) {
            this(category, categoryRequired, candidates, false);
        }
    }
    private record CategoryScore(BusinessCategory category, int score) {}
}
