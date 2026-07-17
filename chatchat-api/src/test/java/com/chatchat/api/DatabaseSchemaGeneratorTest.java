package com.chatchat.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaGeneratorTest {
    @Test
    void generateApiSchemasFromJpaEntities() throws Exception {
        Path output = Path.of("target", "generated-schema");
        Files.createDirectories(output);
        generate("org.hibernate.dialect.MySQLDialect", output.resolve("chatchat-api-mysql.sql"));
        generate("org.hibernate.dialect.H2Dialect", output.resolve("chatchat-api-h2.sql"));
        assertSchemaMatches(output.resolve("chatchat-api-mysql.sql"), Path.of("..", "database", "init", "mysql", "chatchat-api.sql"), 42);
        assertSchemaMatches(output.resolve("chatchat-api-h2.sql"), Path.of("..", "database", "init", "h2", "chatchat-api.sql"), 42);
    }

    private void generate(String dialect, Path target) throws Exception {
        Files.deleteIfExists(target);
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:schema_api;DB_CLOSE_DELAY=-1", "sa", "");
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.chatchat");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", dialect);
        properties.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.put("hibernate.hbm2ddl.delimiter", ";");
        properties.put("hibernate.format_sql", "true");
        properties.put("jakarta.persistence.schema-generation.database.action", "none");
        properties.put("jakarta.persistence.schema-generation.scripts.action", "create");
        properties.put("jakarta.persistence.schema-generation.scripts.create-target", target.toAbsolutePath().toString());
        factory.setJpaPropertyMap(properties);
        factory.afterPropertiesSet();
        factory.destroy();
    }

    private void assertSchemaMatches(Path generated, Path committed, int expectedTables) throws Exception {
        String generatedSql = normalize(Files.readString(generated));
        String committedSql = normalize(Files.readString(committed));
        assertThat(committedSql.split("create table ", -1).length - 1).isEqualTo(expectedTables);
        assertThat(committedSql).isEqualTo(generatedSql);
    }

    private String normalize(String sql) {
        return sql.replaceAll("(?m)^--.*$", "").replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
