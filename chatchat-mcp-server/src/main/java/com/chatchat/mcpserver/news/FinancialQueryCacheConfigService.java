package com.chatchat.mcpserver.news;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FinancialQueryCacheConfigService {
    private final FinancialQueryCacheConfigRepository repository;
    private final MarketModuleProperties properties;
    private volatile FinancialQueryCacheConfig cached;

    public FinancialQueryCacheConfig current() {
        FinancialQueryCacheConfig value = cached;
        if (value != null) return value;
        synchronized (this) {
            if (cached == null) cached = normalize(repository.findById(FinancialQueryCacheConfig.DEFAULT_ID)
                .orElseGet(this::defaults));
            return cached;
        }
    }

    public MarketModuleProperties.QueryCache currentPolicy() {
        FinancialQueryCacheConfig config = current();
        MarketModuleProperties.QueryCache policy = new MarketModuleProperties.QueryCache();
        policy.setEnabled(config.isEnabled());
        policy.setStorage(config.getStorage());
        policy.setTtlSeconds(config.getTtlSeconds());
        policy.setFallbackToRocksDb(config.isFallbackToRocksDb());
        policy.setMaxEntryKb(config.getMaxEntryKb());
        policy.setSingleFlightGraceMs(config.getSingleFlightGraceMs());
        return policy;
    }

    @Transactional
    public FinancialQueryCacheConfig save(FinancialQueryCacheConfig request) {
        FinancialQueryCacheConfig target = repository.findById(FinancialQueryCacheConfig.DEFAULT_ID)
            .orElseGet(FinancialQueryCacheConfig::new);
        target.setId(FinancialQueryCacheConfig.DEFAULT_ID);
        target.setEnabled(request.isEnabled());
        target.setStorage(request.getStorage());
        target.setTtlSeconds(request.getTtlSeconds());
        target.setFallbackToRocksDb(request.isFallbackToRocksDb());
        target.setMaxEntryKb(request.getMaxEntryKb());
        target.setSingleFlightGraceMs(request.getSingleFlightGraceMs());
        FinancialQueryCacheConfig saved = repository.save(normalize(target));
        cached = saved;
        return saved;
    }

    private FinancialQueryCacheConfig defaults() {
        MarketModuleProperties.QueryCache source = properties.getQueryCache();
        FinancialQueryCacheConfig config = new FinancialQueryCacheConfig();
        if (source != null) {
            config.setEnabled(source.isEnabled());
            config.setStorage(source.getStorage());
            config.setTtlSeconds(source.getTtlSeconds());
            config.setFallbackToRocksDb(source.isFallbackToRocksDb());
            config.setMaxEntryKb(source.getMaxEntryKb());
            config.setSingleFlightGraceMs(source.getSingleFlightGraceMs());
        }
        return config;
    }

    private FinancialQueryCacheConfig normalize(FinancialQueryCacheConfig config) {
        config.setStorage("REDIS".equalsIgnoreCase(config.getStorage()) ? "REDIS" : "ROCKSDB");
        config.setTtlSeconds(Math.max(1L, Math.min(config.getTtlSeconds(), 604800L)));
        config.setMaxEntryKb(Math.max(1, Math.min(config.getMaxEntryKb(), 102400)));
        config.setSingleFlightGraceMs(Math.max(0L, Math.min(config.getSingleFlightGraceMs(), 5000L)));
        return config;
    }
}
