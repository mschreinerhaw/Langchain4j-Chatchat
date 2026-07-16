package com.chatchat.mcpserver.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatabaseQueryCacheConfigService {

    private final DatabaseQueryCacheConfigRepository repository;

    public DatabaseQueryCacheConfig current() {
        return normalize(repository.findById(DatabaseQueryCacheConfig.DEFAULT_ID)
            .orElseGet(DatabaseQueryCacheConfig::new));
    }

    @Transactional
    public DatabaseQueryCacheConfig save(DatabaseQueryCacheConfig request) {
        DatabaseQueryCacheConfig current = repository.findById(DatabaseQueryCacheConfig.DEFAULT_ID)
            .orElseGet(DatabaseQueryCacheConfig::new);
        current.setId(DatabaseQueryCacheConfig.DEFAULT_ID);
        current.setEnabled(request.isEnabled());
        current.setDefaultTtlSeconds(request.getDefaultTtlSeconds());
        current.setMaxRows(request.getMaxRows());
        current.setMaxEntryKb(request.getMaxEntryKb());
        current.setKeyStrategy(request.getKeyStrategy());
        current.setCacheEmptyResults(request.isCacheEmptyResults());
        current.setCacheErrorResults(request.isCacheErrorResults());
        return repository.save(normalize(current));
    }

    private DatabaseQueryCacheConfig normalize(DatabaseQueryCacheConfig config) {
        config.setDefaultTtlSeconds(clamp(config.getDefaultTtlSeconds(), 1, 86400, 300));
        config.setMaxRows(clamp(config.getMaxRows(), 1, 100000, 1000));
        config.setMaxEntryKb(clamp(config.getMaxEntryKb(), 1, 102400, 512));
        String keyStrategy = config.getKeyStrategy();
        if (keyStrategy == null || keyStrategy.isBlank()) {
            config.setKeyStrategy("TEMPLATE_ID_PARAMS_DATASOURCE");
        } else {
            String normalized = keyStrategy.trim().toUpperCase();
            // Migrate the former SQL-body strategies to stable template identity.
            if (normalized.equals("SQL_PARAMS_DATASOURCE") || normalized.equals("NORMALIZED_SQL_PARAMS")) {
                normalized = "TEMPLATE_ID_PARAMS_DATASOURCE";
            } else if (normalized.equals("SQL_PARAMS_DATASOURCE_USER")) {
                normalized = "TEMPLATE_ID_PARAMS_DATASOURCE_USER";
            }
            if (!normalized.equals("TEMPLATE_ID_PARAMS_DATASOURCE")
                && !normalized.equals("TEMPLATE_ID_PARAMS_DATASOURCE_USER")) {
                normalized = "TEMPLATE_ID_PARAMS_DATASOURCE";
            }
            config.setKeyStrategy(normalized);
        }
        return config;
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
