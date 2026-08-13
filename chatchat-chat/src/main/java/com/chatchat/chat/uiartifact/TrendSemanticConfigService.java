package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    static final List<String> DEFAULT_KEYWORDS = List.of(
        "涨跌", "涨幅", "跌幅", "盈亏", "收益", "回报", "增长", "增幅",
        "同比", "环比", "变化", "变动", "净增", "change", "profit", "return",
        "growth", "delta", "pnl"
    );
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final TrendSemanticConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Transactional
    public TrendSemanticConfig get(String tenantId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        CacheEntry cached = cache.get(normalizedTenantId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.config();
        }

        TrendSemanticConfigEntity entity = repository.findById(normalizedTenantId)
            .orElseGet(this::globalConfigEntity);
        TrendSemanticConfig result = toView(entity, normalizedTenantId.equals(entity.getTenantId()) ? "TENANT" : "GLOBAL");
        cache.put(normalizedTenantId, new CacheEntry(result, Instant.now().plus(CACHE_TTL)));
        return result;
    }

    @Transactional
    public TrendSemanticConfig update(String tenantId, UpdateRequest request) {
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
        entity.setKeywordsJson(writeKeywords(keywords));
        entity.setUpColor(upColor);
        entity.setDownColor(downColor);
        entity.setNeutralColor(neutralColor);
        entity.setRevision(existing ? Math.max(1, entity.getRevision() + 1) : 1);
        TrendSemanticConfigEntity saved = repository.save(entity);
        cache.remove(normalizedTenantId);
        return toView(saved, "TENANT");
    }

    @Transactional
    public TrendSemanticConfig reset(String tenantId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        if (!GLOBAL_TENANT_ID.equals(normalizedTenantId)) {
            repository.deleteById(normalizedTenantId);
        }
        cache.remove(normalizedTenantId);
        return get(normalizedTenantId);
    }

    private TrendSemanticConfigEntity globalConfigEntity() {
        return repository.findById(GLOBAL_TENANT_ID).orElseGet(() -> {
            TrendSemanticConfigEntity entity = new TrendSemanticConfigEntity();
            entity.setTenantId(GLOBAL_TENANT_ID);
            entity.setKeywordsJson(writeKeywords(DEFAULT_KEYWORDS));
            entity.setUpColor("#e5484d");
            entity.setDownColor("#16a36a");
            entity.setNeutralColor("#98a2b3");
            entity.setRevision(1);
            return repository.save(entity);
        });
    }

    private TrendSemanticConfig toView(TrendSemanticConfigEntity entity, String scope) {
        return new TrendSemanticConfig(
            entity.getRevision(), scope, readKeywords(entity.getKeywordsJson()),
            entity.getUpColor(), entity.getDownColor(), entity.getNeutralColor(), entity.getUpdatedAt()
        );
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

    private List<String> readKeywords(String json) {
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return normalizeKeywords(new ArrayList<>(values));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return DEFAULT_KEYWORDS;
        }
    }

    private String writeKeywords(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法保存趋势语义配置", ex);
        }
    }

    public record UpdateRequest(List<String> keywords, String upColor, String downColor, String neutralColor) { }

    public record TrendSemanticConfig(
        long revision,
        String scope,
        List<String> keywords,
        String upColor,
        String downColor,
        String neutralColor,
        Instant updatedAt
    ) { }

    private record CacheEntry(TrendSemanticConfig config, Instant expiresAt) { }
}
