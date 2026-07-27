package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;
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

    private final DataQueryCategoryRepository repository;
    private final DatabaseQueryConfigRepository queryRepository;
    private final ObjectMapper objectMapper;

    public DataQueryCategoryService(DataQueryCategoryRepository repository,
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
        seedDefaults();
        classifyUnassignedQueries();
    }

    @Transactional(readOnly = true)
    public List<DataQueryCategory> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<DataQueryCategory> listEnabled() {
        return repository.findByEnabledTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public DataQueryCategory require(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        return repository.findById(idOrCode)
            .or(() -> repository.findByCodeIgnoreCase(idOrCode))
            .orElseThrow(() -> new IllegalArgumentException("Data query category not found: " + idOrCode));
    }

    @Transactional
    public DataQueryCategory save(DataQueryCategory draft) {
        if (draft == null) throw new IllegalArgumentException("category is required");
        String code = normalizeCode(draft.getCode());
        String name = required(draft.getName(), "name");
        repository.findByCodeIgnoreCase(code)
            .filter(existing -> draft.getId() == null || !existing.getId().equals(draft.getId()))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("category code already exists: " + code);
            });
        DataQueryCategory target = draft.getId() == null || draft.getId().isBlank()
            ? new DataQueryCategory()
            : repository.findById(draft.getId())
                .orElseThrow(() -> new IllegalArgumentException("Data query category not found: " + draft.getId()));
        target.setCode(code);
        target.setName(name);
        target.setDomain(first(draft.getDomain(), "finance"));
        target.setDescription(first(draft.getDescription(), ""));
        target.setKeywordsJson(writeKeywords(readKeywords(draft.getKeywordsJson())));
        target.setSortOrder(draft.getSortOrder());
        target.setEnabled(draft.isEnabled());
        return repository.save(target);
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
        List<DataQueryCategory> categories = listEnabled();
        int changed = 0;
        for (DatabaseQueryConfig query : queryRepository.findAll()) {
            if (query.getCategoryId() != null && !query.getCategoryId().isBlank()
                && query.getCapabilityCategory() != null && !query.getCapabilityCategory().isBlank()) {
                continue;
            }
            DataQueryCategory category = classify(query, categories);
            apply(query, category);
            queryRepository.save(query);
            changed++;
        }
        return changed;
    }

    public void apply(DatabaseQueryConfig config, String categoryIdOrCode) {
        apply(config, require(categoryIdOrCode));
    }

    public void assignBest(DatabaseQueryConfig config) {
        apply(config, classify(config, listEnabled()));
    }

    public void apply(DatabaseQueryConfig config, DataQueryCategory category) {
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

    private DataQueryCategory classify(DatabaseQueryConfig query, List<DataQueryCategory> categories) {
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

    private List<String> matchedKeywords(DatabaseQueryConfig query, DataQueryCategory category) {
        String corpus = (text(query.getToolName()) + " " + text(query.getTitle()) + " "
            + text(query.getDescription()) + " " + text(query.getImplementationSteps()) + " "
            + text(query.getSqlStepsJson())).toLowerCase(Locale.ROOT);
        return readKeywords(category.getKeywordsJson()).stream()
            .filter(keyword -> corpus.contains(keyword.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private void seedDefaults() {
        for (Seed seed : seeds()) {
            if (repository.findByCodeIgnoreCase(seed.code()).isPresent()) continue;
            DataQueryCategory category = new DataQueryCategory();
            category.setCode(seed.code());
            category.setName(seed.name());
            category.setDescription(seed.description());
            category.setDomain("finance");
            category.setKeywordsJson(ModelProtocolJson.compact(seed.keywords()));
            category.setSortOrder(seed.order());
            category.setEnabled(true);
            repository.save(category);
        }
    }

    private List<Seed> seeds() {
        return List.of(
            new Seed("market_data", "市场行情", "股票、债券、基金、指数、ETF及宏观市场行情查询。", 10,
                List.of("行情", "债券", "收益率", "结算统计", "股票", "基金", "指数", "etf", "宏观", "market", "bond")),
            new Seed("product_analysis", "产品分析", "金融产品要素、表现、估值、组合及收益分析。", 20,
                List.of("产品", "估值", "净值", "组合", "基金产品", "理财", "收益分析")),
            new Seed("customer_analysis", "客户分析", "客户画像、客户资产、行为、风险等级和生命周期分析。", 30,
                List.of("客户", "画像", "客户资产", "生命周期", "适当性", "customer")),
            new Seed("trading_analysis", "交易分析", "委托、成交、交易流水、买卖行为和交易结构分析。", 40,
                List.of("交易", "委托", "成交", "买入", "卖出", "流水", "trade", "buy", "sell")),
            new Seed("risk_management", "风险管理", "风险指标、集中度、敞口、预警和异常交易分析。", 50,
                List.of("风险", "集中度", "敞口", "预警", "异常交易", "限额", "risk")),
            new Seed("data_validation", "数据核验", "一致性、完整性、准确性、同步延迟和指标异常专项核验。", 60,
                List.of("核验", "校验", "一致性", "完整性", "准确性", "差异", "数据质量", "同步延迟", "新鲜度", "覆盖", "validation", "check")),
            new Seed("regulatory_reporting", "监管报送", "监管指标、报表口径、报送数据和合规统计查询。", 70,
                List.of("监管", "报送", "报表", "合规", "监管指标", "report")),
            new Seed("data_asset_exploration", "数据资产探索", "元数据、字段分布、数据概览和资产探索查询。", 80,
                List.of("数据资产", "元数据", "字段", "表结构", "概览", "探索", "metadata", "schema"))
        );
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

    private record Match(DataQueryCategory category, int score) {}
    private record Seed(String code, String name, String description, int order, List<String> keywords) {}
}
