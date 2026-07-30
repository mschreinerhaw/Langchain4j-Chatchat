package com.chatchat.mcpserver.database;

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
public class DataQueryCategoryService {

    private final BusinessCategoryRepository repository;
    private final DatabaseQueryConfigRepository queryRepository;
    private final ObjectMapper objectMapper;

    public DataQueryCategoryService(BusinessCategoryRepository repository,
                                    DatabaseQueryConfigRepository queryRepository,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.objectMapper = objectMapper;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        classifyUnassignedQueries();
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
            throw new IllegalArgumentException("category is required");
        }
        return repository.findById(idOrCode)
            .or(() -> repository.findByCodeIgnoreCase(idOrCode))
            .orElseThrow(() -> new IllegalArgumentException("Data query category not found: " + idOrCode));
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
                .orElseThrow(() -> new IllegalArgumentException("Data query category not found: " + draft.getId()));
        target.setCode(code);
        target.setName(name);
        target.setDomain(first(draft.getDomain(), "finance"));
        target.setDescription(first(draft.getDescription(), ""));
        target.setKeywordsJson(writeKeywords(readKeywords(draft.getKeywordsJson())));
        target.setSortOrder(draft.getSortOrder());
        target.setEnabled(draft.isEnabled());
        BusinessCategory saved = repository.save(target);
        List<DatabaseQueryConfig> referenced = queryRepository.findByCategoryId(saved.getId());
        if (referenced != null) {
            referenced.forEach(config -> {
                apply(config, saved);
                queryRepository.save(config);
            });
        }
        return saved;
    }

    @Transactional
    public void delete(String id) {
        if (queryRepository.countByCategoryId(id) > 0) {
            throw new IllegalArgumentException("Category is referenced by database query capabilities");
        }
        repository.deleteById(id);
    }

    @Transactional
    public int classifyUnassignedQueries() {
        BusinessCategory defaultCategory = ensureDefaultCategory();
        int changed = 0;
        for (DatabaseQueryConfig query : queryRepository.findAll()) {
            if (query.getCategoryId() != null && !query.getCategoryId().isBlank()
                && query.getCapabilityCategory() != null && !query.getCapabilityCategory().isBlank()) {
                continue;
            }
            apply(query, defaultCategory);
            queryRepository.save(query);
            changed++;
        }
        return changed;
    }

    public void apply(DatabaseQueryConfig config, String categoryIdOrCode) {
        apply(config, require(categoryIdOrCode));
    }

    public void assignBest(DatabaseQueryConfig config) {
        apply(config, ensureDefaultCategory());
    }

    private BusinessCategory ensureDefaultCategory() {
        return repository.findByCodeIgnoreCase(BusinessCategoryService.DEFAULT_CODE).orElseGet(() -> {
            BusinessCategory category = new BusinessCategory();
            category.setCode(BusinessCategoryService.DEFAULT_CODE);
            category.setName(BusinessCategoryService.DEFAULT_NAME);
            category.setDescription("用户未指定业务分类时自动归入此分类");
            category.setDomain(BusinessCategoryService.DEFAULT_CODE);
            category.setKeywordsJson(writeKeywords(List.of(BusinessCategoryService.DEFAULT_CODE, "默认", "未分类")));
            category.setSortOrder(10_000);
            category.setEnabled(true);
            return repository.save(category);
        });
    }

    public CategoryResolution resolve(MapLike filters, List<DatabaseQueryConfig> configs) {
        List<DatabaseQueryConfig> available = configs == null ? List.of() : configs;
        List<BusinessCategory> enabled = listEnabled();
        List<BusinessCategory> active = enabled.stream()
            .filter(category -> available.stream().anyMatch(config -> belongsTo(config, category)))
            .toList();
        String explicit = filters.first("categoryId", "category_id", "capabilityCategory",
            "capability_category", "businessGroup", "business_group", "group", "groupName",
            "group_name", "category");
        if (!explicit.isBlank()) {
            return active.stream()
                .filter(category -> matches(category, explicit))
                .findFirst()
                .map(category -> new CategoryResolution(category, false, active))
                .orElseGet(() -> fallbackResolution(active, enabled));
        }
        String corpus = filters.joinedText().toLowerCase(Locale.ROOT);
        List<CategoryScore> scores = active.stream()
            .map(category -> new CategoryScore(category, resolutionScore(category, corpus)))
            .filter(item -> item.score() > 0)
            .sorted(Comparator.comparingInt(CategoryScore::score).reversed()
                .thenComparingInt(item -> item.category().getSortOrder()))
            .toList();
        if (!scores.isEmpty() && (scores.size() == 1 || scores.get(0).score() > scores.get(1).score())) {
            return new CategoryResolution(scores.get(0).category(), false, active);
        }
        if (active.size() == 1) {
            return new CategoryResolution(active.get(0), false, active);
        }
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

    public void apply(DatabaseQueryConfig config, BusinessCategory category) {
        config.setCategoryId(category.getId());
        config.setCapabilityCategory(category.getCode());
        config.setDomain(category.getDomain());
        config.setBusinessGroup(category.getCode());
        config.setBusinessGroupName(category.getName());
        config.setBusinessGroupDescription(category.getDescription());
        Set<String> indexTags = new LinkedHashSet<>(readKeywords(config.getIndexTagsJson()));
        indexTags.add(category.getCode());
        indexTags.add(category.getName());
        indexTags.add(category.getDomain());
        indexTags.addAll(matchedKeywords(config, category));
        config.setIndexTagsJson(writeKeywords(new ArrayList<>(indexTags)));
    }

    private boolean belongsTo(DatabaseQueryConfig config, BusinessCategory category) {
        return category.getId().equals(config.getCategoryId())
            || category.getCode().equalsIgnoreCase(text(config.getCapabilityCategory()))
            || category.getCode().equalsIgnoreCase(text(config.getBusinessGroup()));
    }

    private boolean matches(BusinessCategory category, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return category.getId().equals(value)
            || category.getCode().equalsIgnoreCase(value)
            || text(category.getName()).toLowerCase(Locale.ROOT).equals(normalized);
    }

    private int resolutionScore(BusinessCategory category, String corpus) {
        if (corpus.isBlank()) return 0;
        int score = contains(corpus, category.getCode()) ? 8 : 0;
        score += contains(corpus, category.getName()) ? 8 : 0;
        score += contains(corpus, category.getDescription()) ? 3 : 0;
        score += readKeywords(category.getKeywordsJson()).stream()
            .mapToInt(keyword -> contains(corpus, keyword) ? 5 : 0)
            .sum();
        return score;
    }

    private boolean contains(String corpus, String value) {
        return value != null && !value.isBlank() && corpus.contains(value.toLowerCase(Locale.ROOT));
    }

    private BusinessCategory classify(DatabaseQueryConfig query, List<BusinessCategory> categories) {
        String primaryCorpus = String.join(" ", List.of(
            text(query.getTitle()), text(query.getDescription()), text(query.getImplementationSteps()),
            text(query.getTemplateIntent()), text(query.getTagsJson())
        )).toLowerCase(Locale.ROOT);
        String technicalCorpus = String.join(" ", List.of(
            text(query.getToolName()), text(query.getSqlStepsJson()), text(query.getBusinessGroup())
        )).toLowerCase(Locale.ROOT);
        return categories.stream()
            .map(category -> new Match(category, readKeywords(category.getKeywordsJson()).stream()
                .map(String::toLowerCase)
                .mapToInt(keyword -> (primaryCorpus.contains(keyword) ? 4 : 0)
                    + (technicalCorpus.contains(keyword) ? 1 : 0))
                .sum()))
            .filter(match -> match.score() > 0)
            .sorted(Comparator.comparingInt(Match::score).reversed()
                .thenComparingInt(match -> match.category().getSortOrder()))
            .map(Match::category)
            .findFirst()
            .orElseGet(() -> categories.stream()
                .filter(category -> "data_asset_exploration".equals(category.getCode()))
                .findFirst()
                .orElse(categories.get(0)));
    }

    private List<String> matchedKeywords(DatabaseQueryConfig query, BusinessCategory category) {
        String corpus = (text(query.getToolName()) + " " + text(query.getTitle()) + " "
            + text(query.getDescription()) + " " + text(query.getImplementationSteps()) + " "
            + text(query.getSqlStepsJson())).toLowerCase(Locale.ROOT);
        return readKeywords(category.getKeywordsJson()).stream()
            .filter(keyword -> corpus.contains(keyword.toLowerCase(Locale.ROOT)))
            .toList();
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
        String code = required(value, "code").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (code.length() > 128) throw new IllegalArgumentException("category code is too long");
        return code;
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
    private record Match(BusinessCategory category, int score) {}
    private record CategoryScore(BusinessCategory category, int score) {}
}
