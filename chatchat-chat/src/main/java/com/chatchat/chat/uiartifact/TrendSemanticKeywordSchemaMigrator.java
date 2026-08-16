package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One-time compatibility migration for installations that stored trend keywords
 * in {@code ui_trend_semantic_config.keywords_json}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendSemanticKeywordSchemaMigrator {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TrendSemanticKeywordRepository keywordRepository;

    private volatile boolean checked;

    public synchronized void migrateIfNeeded() {
        if (checked) {
            return;
        }
        if (!legacyColumnExists()) {
            checked = true;
            return;
        }

        List<LegacyKeywordConfig> legacyConfigs = jdbcTemplate.query(
            "select tenant_id, keywords_json from ui_trend_semantic_config",
            (resultSet, rowNumber) -> new LegacyKeywordConfig(resultSet.getString(1), resultSet.getString(2))
        );
        for (LegacyKeywordConfig legacyConfig : legacyConfigs) {
            if (!keywordRepository.findByTenantIdOrderBySortOrderAscKeywordAsc(legacyConfig.tenantId()).isEmpty()) {
                continue;
            }
            saveKeywords(legacyConfig.tenantId(), parseKeywords(legacyConfig.keywordsJson()));
        }

        // Rows are flushed before retiring the old NOT NULL column. This also makes
        // creation of a new tenant configuration independent of the legacy schema.
        keywordRepository.flush();
        jdbcTemplate.execute("alter table ui_trend_semantic_config drop column keywords_json");
        checked = true;
        log.info("Migrated trend semantic keywords from keywords_json to ui_trend_semantic_keyword");
    }

    private boolean legacyColumnExists() {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
                while (columns.next()) {
                    if ("ui_trend_semantic_config".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && "keywords_json".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private List<String> parseKeywords(String json) {
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                String keyword = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
                if (!keyword.isEmpty() && keyword.length() <= 64) {
                    normalized.add(keyword);
                }
                if (normalized.size() == 100) {
                    break;
                }
            }
            return normalized.isEmpty() ? TrendSemanticConfigService.DEFAULT_KEYWORDS : List.copyOf(normalized);
        } catch (Exception ex) {
            log.warn("Invalid legacy trend keyword JSON; finance defaults will be used", ex);
            return TrendSemanticConfigService.DEFAULT_KEYWORDS;
        }
    }

    private void saveKeywords(String tenantId, List<String> keywords) {
        List<TrendSemanticKeywordEntity> rows = new ArrayList<>(keywords.size());
        for (int index = 0; index < keywords.size(); index++) {
            TrendSemanticKeywordEntity row = new TrendSemanticKeywordEntity();
            row.setTenantId(tenantId);
            row.setKeyword(keywords.get(index));
            row.setSortOrder(index);
            rows.add(row);
        }
        keywordRepository.saveAllAndFlush(rows);
    }

    private record LegacyKeywordConfig(String tenantId, String keywordsJson) { }
}
