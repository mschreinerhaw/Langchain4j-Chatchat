package com.chatchat.license.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseModuleCatalogServiceTest {
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:license-catalog;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS license_module_catalog");
        jdbcTemplate.execute("""
            CREATE TABLE license_module_catalog (
                module_key VARCHAR(128) PRIMARY KEY,
                label VARCHAR(256) NOT NULL,
                icon VARCHAR(128),
                navigation BOOLEAN NOT NULL,
                parent_key VARCHAR(128),
                description VARCHAR(1024),
                enabled BOOLEAN NOT NULL,
                catalog_version VARCHAR(64) NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """);
    }

    @Test
    void initializesVersionedDefaultsFromClasspath() {
        LicenseModuleCatalogService service = service();

        service.initializeDefaults();

        Set<String> keys = service.enabledKeys();
        assertTrue(keys.contains("databaseMcp"));
        assertTrue(keys.contains("assetSql"));
        assertTrue(keys.contains("enterpriseMetadata"));
        assertTrue(service.listEnabled().stream()
            .anyMatch(module -> "assetSql".equals(module.key()) && !module.navigation()));
    }

    @Test
    void preservesDatabaseManagedModulesAcrossInitialization() {
        LicenseModuleCatalogService first = service();
        first.initializeDefaults();
        first.save(new LicenseModuleCatalogService.MenuModule(
            "customReport", "自定义报告", "Report", false, "mcpServices",
            "由授权中心管理员维护", true, "customer-v1"));

        LicenseModuleCatalogService restarted = service();
        restarted.initializeDefaults();

        assertTrue(restarted.enabledKeys().contains("customReport"));
        assertEquals("customer-v1", restarted.listEnabled().stream()
            .filter(module -> "customReport".equals(module.key()))
            .findFirst()
            .orElseThrow()
            .catalogVersion());
    }

    private LicenseModuleCatalogService service() {
        return new LicenseModuleCatalogService(jdbcTemplate, new ObjectMapper());
    }
}
