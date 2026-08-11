package com.chatchat.license.server;

import com.chatchat.license.LicenseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Database-backed source of truth for modules that may be written into a license. */
@Service
public class LicenseModuleCatalogService {
    private static final String DEFAULT_CATALOG = "license-module-catalog.json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LicenseModuleCatalogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Adds newly shipped defaults without overwriting catalog rows maintained by an operator. */
    @PostConstruct
    public void initializeDefaults() {
        try (var input = new ClassPathResource(DEFAULT_CATALOG).getInputStream()) {
            List<MenuModule> defaults = objectMapper.readValue(input, new TypeReference<List<MenuModule>>() { });
            Set<String> existing = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT module_key FROM license_module_catalog", String.class));
            List<MenuModule> missing = defaults.stream()
                .filter(module -> module != null && module.key() != null && !module.key().isBlank())
                .filter(module -> !existing.contains(module.key()))
                .toList();
            if (!missing.isEmpty()) {
                jdbcTemplate.batchUpdate("""
                    INSERT INTO license_module_catalog (
                        module_key, label, icon, navigation, parent_key, description,
                        enabled, catalog_version, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, missing, missing.size(), (statement, module) -> {
                        statement.setString(1, module.key());
                        statement.setString(2, module.label());
                        statement.setString(3, module.icon());
                        statement.setBoolean(4, module.navigation());
                        statement.setString(5, text(module.parentKey()));
                        statement.setString(6, text(module.description()));
                        statement.setBoolean(7, module.enabled());
                        statement.setString(8, text(module.catalogVersion()));
                        statement.setTimestamp(9, java.sql.Timestamp.from(Instant.now()));
                    });
            }
        } catch (Exception ex) {
            throw new LicenseException("Failed to initialize the license module catalog", ex);
        }
    }

    public List<MenuModule> listEnabled() {
        return jdbcTemplate.query("""
            SELECT module_key, label, icon, navigation, parent_key, description,
                   enabled, catalog_version
              FROM license_module_catalog
             WHERE enabled = TRUE
             ORDER BY navigation DESC, module_key ASC
            """, (resultSet, rowNum) -> new MenuModule(
                resultSet.getString("module_key"),
                resultSet.getString("label"),
                resultSet.getString("icon"),
                resultSet.getBoolean("navigation"),
                resultSet.getString("parent_key"),
                resultSet.getString("description"),
                resultSet.getBoolean("enabled"),
                resultSet.getString("catalog_version")
            ));
    }

    public Set<String> enabledKeys() {
        return new HashSet<>(jdbcTemplate.queryForList(
            "SELECT module_key FROM license_module_catalog WHERE enabled = TRUE", String.class));
    }

    @Transactional
    public MenuModule save(MenuModule module) {
        if (module == null || module.key() == null || module.key().isBlank()
            || module.label() == null || module.label().isBlank()) {
            throw new LicenseException("Module key and label are required");
        }
        int changed = jdbcTemplate.update("""
            UPDATE license_module_catalog
               SET label = ?, icon = ?, navigation = ?, parent_key = ?, description = ?,
                   enabled = ?, catalog_version = ?, updated_at = ?
             WHERE module_key = ?
            """, module.label().trim(), text(module.icon()), module.navigation(), text(module.parentKey()),
            text(module.description()), module.enabled(), text(module.catalogVersion()),
            java.sql.Timestamp.from(Instant.now()), module.key().trim());
        if (changed == 0) {
            jdbcTemplate.update("""
                INSERT INTO license_module_catalog (
                    module_key, label, icon, navigation, parent_key, description,
                    enabled, catalog_version, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, module.key().trim(), module.label().trim(), text(module.icon()), module.navigation(),
                text(module.parentKey()), text(module.description()), module.enabled(),
                text(module.catalogVersion()), java.sql.Timestamp.from(Instant.now()));
        }
        return module;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record MenuModule(String key, String label, String icon, boolean navigation,
                             String parentKey, String description, boolean enabled,
                             String catalogVersion) {
        public MenuModule(String key, String label, String icon) {
            this(key, label, icon, true, "", "", true, "v1");
        }
    }
}
