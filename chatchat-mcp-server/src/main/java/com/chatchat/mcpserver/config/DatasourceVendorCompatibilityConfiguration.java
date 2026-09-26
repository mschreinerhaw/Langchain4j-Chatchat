package com.chatchat.mcpserver.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * Keeps vendor-specific Hikari initialization SQL aligned with the configured JDBC URL.
 *
 * <p>External deployments sometimes switch only the datasource URL, driver and Hibernate
 * dialect while retaining the imported datasource file. In that situation Hikari would send
 * PostgreSQL's {@code SET lock_timeout} to MySQL (or MySQL's lock variables to PostgreSQL)
 * before Hibernate can even inspect the database. Repairing only the known built-in lock
 * timeout statements makes that transition safe without rewriting custom initialization SQL.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DatasourceVendorCompatibilityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatasourceVendorCompatibilityConfiguration.class);

    @Bean
    static BeanPostProcessor datasourceVendorCompatibilityPostProcessor(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource) {
                    alignConnectionInitSql(dataSource, beanName, environment);
                }
                return bean;
            }
        };
    }

    static void alignConnectionInitSql(HikariDataSource dataSource,
                                       String beanName,
                                       Environment environment) {
        String jdbcUrl = normalized(dataSource.getJdbcUrl());
        String initSql = dataSource.getConnectionInitSql();
        if (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) {
            if (isPostgresqlLockTimeout(initSql)) {
                String replacement = "SET SESSION lock_wait_timeout="
                    + positiveInteger(environment, "CHATCHAT_MCP_DB_LOCK_WAIT_SECONDS", 5)
                    + ", SESSION innodb_lock_wait_timeout="
                    + positiveInteger(environment, "CHATCHAT_MCP_DB_ROW_LOCK_WAIT_SECONDS", 5);
                dataSource.setConnectionInitSql(replacement);
                log.warn("Corrected PostgreSQL Hikari connection-init-sql for MySQL datasource bean '{}'. "
                    + "Set CHATCHAT_DATASOURCE_CONFIG=datasource-mysql.yml to switch all datasource settings.",
                    beanName);
            }
            return;
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:") && isMysqlLockTimeout(initSql)) {
            String replacement = "SET lock_timeout = '"
                + positiveInteger(environment, "CHATCHAT_MCP_DB_LOCK_WAIT_SECONDS", 5) + "s'";
            dataSource.setConnectionInitSql(replacement);
            log.warn("Corrected MySQL Hikari connection-init-sql for PostgreSQL datasource bean '{}'. "
                + "Set CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml to switch all datasource settings.",
                beanName);
            return;
        }
        if (jdbcUrl.startsWith("jdbc:h2:")
            && (isPostgresqlLockTimeout(initSql) || isMysqlLockTimeout(initSql))) {
            dataSource.setConnectionInitSql(null);
            log.warn("Removed vendor-specific Hikari connection-init-sql from H2 datasource bean '{}'. "
                + "Set CHATCHAT_DATASOURCE_CONFIG=datasource-h2.yml to switch all datasource settings.", beanName);
        }
    }

    private static boolean isPostgresqlLockTimeout(String sql) {
        return normalized(sql).matches("set\\s+lock_timeout\\b.*");
    }

    private static boolean isMysqlLockTimeout(String sql) {
        String normalized = normalized(sql);
        return normalized.contains("innodb_lock_wait_timeout")
            || normalized.matches(".*\\block_wait_timeout\\b.*");
    }

    private static int positiveInteger(Environment environment, String propertyName, int defaultValue) {
        String configured = environment.getProperty(propertyName);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
