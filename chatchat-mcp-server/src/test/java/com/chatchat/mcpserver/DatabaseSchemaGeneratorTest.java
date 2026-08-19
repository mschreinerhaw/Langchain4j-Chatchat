package com.chatchat.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaGeneratorTest {
    @Test
    void generateMcpServerSchemasFromJpaEntities() throws Exception {
        Path output = Path.of("target", "generated-schema");
        Files.createDirectories(output);
        generate("org.hibernate.dialect.MySQLDialect", output.resolve("chatchat-mcp-server-mysql.sql"));
        generate("org.hibernate.dialect.H2Dialect", output.resolve("chatchat-mcp-server-h2.sql"));
        assertSchemaMatches(output.resolve("chatchat-mcp-server-mysql.sql"), Path.of("..", "database", "init", "mysql", "chatchat-mcp-server.sql"), 36);
        assertSchemaMatches(output.resolve("chatchat-mcp-server-h2.sql"), Path.of("..", "database", "init", "h2", "chatchat-mcp-server.sql"), 36);
    }

    private void generate(String dialect, Path target) throws Exception {
        Files.deleteIfExists(target);
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:schema_mcp;DB_CLOSE_DELAY=-1", "sa", "");
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.chatchat.mcpserver", "com.chatchat.integration.mcp");
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
        String withoutComments = sql.replaceAll("(?m)^--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String raw : withoutComments.split(";")) {
            String statement = raw.trim();
            if (statement.isEmpty()) continue;
            if (statement.toLowerCase().startsWith("create table ")) {
                int open = statement.indexOf('(');
                int close = statement.lastIndexOf(')');
                if (open > 0 && close > open) {
                    List<String> columns = new ArrayList<>(Arrays.asList(
                        statement.substring(open + 1, close).split(",\\s*\\R")));
                    columns.replaceAll(String::trim);
                    Collections.sort(columns);
                    statement = statement.substring(0, open + 1) + String.join(",", columns)
                        + statement.substring(close);
                }
            }
            statement = statement.replaceAll("(?i)add\\s+constraint\\s+\\S+\\s+unique", "add unique")
                .replaceAll("\\s+", " ").trim().toLowerCase();
            statements.add(statement);
        }
        Collections.sort(statements);
        return String.join(";", statements);
    }
}
