package com.chatchat.chat.uiartifact;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TrendSemanticConfigService {

    static final String GLOBAL_TENANT_ID = "__global__";
    static final int FINANCE_RULESET_VERSION = 2;
    static final List<String> DEFAULT_KEYWORDS = List.of(
        "涨跌", "涨幅", "跌幅", "涨跌额", "日涨跌", "区间涨跌",
        "盈亏", "盈亏率", "浮动盈亏", "已实现盈亏", "未实现盈亏", "损益",
        "收益", "收益率", "净收益", "累计收益", "持有收益", "年化收益",
        "超额收益", "绝对收益", "回报", "回报率",
        "增长", "增长率", "增长额", "增幅", "增速", "净增长", "净增",
        "同比", "环比", "变化", "变动", "净变化", "净变动",
        "净流入", "净流出", "资金净流入", "资金净流出",
        "估值变动", "市值变动", "净值增长", "利润增长", "利润增速",
        "收入增长", "收入增速", "成本变动", "费用变动", "风险变化",
        "回撤变化", "波动率变化",
        "change", "profit", "return", "growth", "delta", "pnl", "p&l",
        "gain", "loss", "performance", "roi", "roe", "yoy", "mom",
        "net inflow", "net outflow", "drawdown change", "volatility change"
    );
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final TrendSemanticConfigRepository repository;
    private final TrendSemanticKeywordRepository keywordRepository;
    private final TrendSemanticKeywordSchemaMigrator keywordSchemaMigrator;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Transactional
    public TrendSemanticConfig get(String tenantId) {
        keywordSchemaMigrator.migrateIfNeeded();
        String normalizedTenantId = normalizeTenantId(tenantId);
        CacheEntry cached = cache.get(normalizedTenantId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.config();
        }

        TrendSemanticConfigEntity entity = repository.findById(normalizedTenantId)
            .orElseGet(this::globalConfigEntity);
        TrendSemanticConfig result = toView(
            entity,
            normalizedTenantId.equals(entity.getTenantId()) ? "TENANT" : "GLOBAL",
            keywords(entity.getTenantId())
        );
        cache.put(normalizedTenantId, new CacheEntry(result, Instant.now().plus(CACHE_TTL)));
        return result;
    }

    @Transactional
    public TrendSemanticConfig update(String tenantId, UpdateRequest request) {
        keywordSchemaMigrator.migrateIfNeeded();
        if (request == null) {
            throw new IllegalArgumentException("趋势语义配置不能为空");
        }
        String normalizedTenantId = normalizeTenantId(tenantId);
        List<String> keywords = normalizeKeywords(request.keywords());
        String upColor = normalizeColor(request.upColor(), "上涨颜色");
        String downColor = normalizeColor(request.downColor(), "下跌颜色");
        String neutralColor = normalizeColor(request.neutralColor(), "中性颜色");

        TrendSemanticConfigEntity entity = repository.findById(normalizedTenantId).orElse(null);
        boolean existing = entity != null;
        if (!existing) {
            entity = new TrendSemanticConfigEntity();
        }
        entity.setTenantId(normalizedTenantId);
        entity.setUpColor(upColor);
        entity.setDownColor(downColor);
        entity.setNeutralColor(neutralColor);
        entity.setRulesetVersion(FINANCE_RULESET_VERSION);
        entity.setRevision(existing ? Math.max(1, entity.getRevision() + 1) : 1);
        TrendSemanticConfigEntity saved = repository.save(entity);
        replaceKeywords(normalizedTenantId, keywords);
        cache.remove(normalizedTenantId);
        return toView(saved, "TENANT", keywords);
    }

    @Transactional
    public TrendSemanticConfig reset(String tenantId) {
        keywordSchemaMigrator.migrateIfNeeded();
        String normalizedTenantId = normalizeTenantId(tenantId);
        if (!GLOBAL_TENANT_ID.equals(normalizedTenantId)) {
            keywordRepository.deleteByTenantId(normalizedTenantId);
            repository.deleteById(normalizedTenantId);
        }
        cache.remove(normalizedTenantId);
        return get(normalizedTenantId);
    }

    private TrendSemanticConfigEntity globalConfigEntity() {
        return repository.findById(GLOBAL_TENANT_ID).map(this::upgradeGlobalRuleset).orElseGet(() -> {
            TrendSemanticConfigEntity entity = new TrendSemanticConfigEntity();
            entity.setTenantId(GLOBAL_TENANT_ID);
            entity.setUpColor("#e5484d");
            entity.setDownColor("#16a36a");
            entity.setNeutralColor("#98a2b3");
            entity.setRulesetVersion(FINANCE_RULESET_VERSION);
            entity.setRevision(1);
            TrendSemanticConfigEntity saved = repository.save(entity);
            replaceKeywords(GLOBAL_TENANT_ID, DEFAULT_KEYWORDS);
            return saved;
        });
    }

    private TrendSemanticConfigEntity upgradeGlobalRuleset(TrendSemanticConfigEntity entity) {
        if (entity.getRulesetVersion() >= FINANCE_RULESET_VERSION) {
            return entity;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(keywords(entity.getTenantId()));
        merged.addAll(DEFAULT_KEYWORDS);
        entity.setRulesetVersion(FINANCE_RULESET_VERSION);
        entity.setRevision(Math.max(1, entity.getRevision() + 1));
        TrendSemanticConfigEntity saved = repository.save(entity);
        replaceKeywords(entity.getTenantId(), List.copyOf(merged));
        return saved;
    }

    private TrendSemanticConfig toView(TrendSemanticConfigEntity entity, String scope, List<String> keywords) {
        return new TrendSemanticConfig(
            entity.getRevision(), entity.getRulesetVersion(), scope, keywords,
            entity.getUpColor(), entity.getDownColor(), entity.getNeutralColor(), entity.getUpdatedAt()
        );
    }

    private List<String> keywords(String tenantId) {
        List<String> keywords = keywordRepository.findByTenantIdOrderBySortOrderAscKeywordAsc(tenantId).stream()
            .map(TrendSemanticKeywordEntity::getKeyword)
            .toList();
        if (!keywords.isEmpty()) {
            return keywords;
        }
        replaceKeywords(tenantId, DEFAULT_KEYWORDS);
        return DEFAULT_KEYWORDS;
    }

    private void replaceKeywords(String tenantId, List<String> keywords) {
        keywordRepository.deleteByTenantId(tenantId);
        List<TrendSemanticKeywordEntity> rows = new java.util.ArrayList<>(keywords.size());
        for (int index = 0; index < keywords.size(); index++) {
            TrendSemanticKeywordEntity row = new TrendSemanticKeywordEntity();
            row.setTenantId(tenantId);
            row.setKeyword(keywords.get(index));
            row.setSortOrder(index);
            rows.add(row);
        }
        keywordRepository.saveAll(rows);
    }

    private List<String> normalizeKeywords(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("趋势指标关键词不能为空");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String keyword = value == null ? "" : value.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.length() > 64) {
                throw new IllegalArgumentException("单个趋势指标关键词不能超过 64 个字符");
            }
            result.add(keyword.toLowerCase(Locale.ROOT));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个趋势指标关键词");
        }
        if (result.size() > 100) {
            throw new IllegalArgumentException("趋势指标关键词不能超过 100 个");
        }
        return List.copyOf(result);
    }

    private String normalizeColor(String value, String label) {
        String color = value == null ? "" : value.trim();
        if (!COLOR_PATTERN.matcher(color).matches()) {
            throw new IllegalArgumentException(label + "必须是 #RRGGBB 格式");
        }
        return color.toLowerCase(Locale.ROOT);
    }

    private String normalizeTenantId(String tenantId) {
        String normalized = tenantId == null ? "" : tenantId.trim();
        return normalized.isEmpty() ? "default" : normalized;
    }

    public record UpdateRequest(List<String> keywords, String upColor, String downColor, String neutralColor) { }

    public record TrendSemanticConfig(
        long revision,
        int rulesetVersion,
        String scope,
        List<String> keywords,
        String upColor,
        String downColor,
        String neutralColor,
        Instant updatedAt
    ) { }

    private record CacheEntry(TrendSemanticConfig config, Instant expiresAt) { }
}
