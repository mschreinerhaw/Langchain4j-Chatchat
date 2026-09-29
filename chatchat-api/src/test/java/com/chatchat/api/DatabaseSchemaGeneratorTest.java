package com.chatchat.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaGeneratorTest {
    @Test
    void initializeEmptyH2Database() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:api_init_validation", "sa", "");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                new FileSystemResource(Path.of("..", "database", "init", "h2", "chatchat-api.sql")));
            try (var statement = connection.createStatement()) {
                statement.executeQuery("select execution_engine, execution_model, published_compilation_id, runtime_metadata_json from ds_domain_skill").close();
                statement.executeQuery("select agent_id from resource_grant").close();
                statement.executeQuery("select request_json, result_json from ds_skill_analysis_run").close();
                statement.executeQuery("select domain_skill_id, contract_id, published_json from ds_skill_data_binding").close();
            }
        }
    }

    @Test
    void generateApiSchemasFromJpaEntities() throws Exception {
        Path output = Path.of("target", "generated-schema");
        Files.createDirectories(output);
        generate("org.hibernate.dialect.MySQLDialect", output.resolve("chatchat-api-mysql.sql"));
        generate("org.hibernate.dialect.H2Dialect", output.resolve("chatchat-api-h2.sql"));
        generate("org.hibernate.dialect.PostgreSQLDialect", output.resolve("chatchat-api-postgresql.sql"));
        assertSchemaMatches(output.resolve("chatchat-api-mysql.sql"), Path.of("..", "database", "init", "mysql", "chatchat-api.sql"), 93);
        assertSchemaMatches(output.resolve("chatchat-api-h2.sql"), Path.of("..", "database", "init", "h2", "chatchat-api.sql"), 93);
        assertSchemaMatches(output.resolve("chatchat-api-postgresql.sql"), Path.of("..", "database", "init", "postgresql", "chatchat-api.sql"), 93);
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
