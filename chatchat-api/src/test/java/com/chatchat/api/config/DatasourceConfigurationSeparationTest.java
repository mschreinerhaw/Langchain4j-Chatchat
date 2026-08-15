package com.chatchat.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceConfigurationSeparationTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void applicationProfilesSelectSeparateDatasourceFiles() throws Exception {
        assertProfileSelects(Path.of("src/main/resources/application-dev.yml"), "datasource-mysql.yml");
        assertProfileSelects(Path.of("src/main/resources/application-prod.yml"), "datasource-h2.yml");
        assertProfileSelects(Path.of("../packaging/config/application-dev.yml"), "datasource-mysql.yml");
        assertProfileSelects(Path.of("../packaging/config/application-prod.yml"), "datasource-h2.yml");
    }

    @Test
    void datasourceFilesContainDatabaseSettings() throws Exception {
        assertDatasource(Path.of("src/main/resources/datasource-mysql.yml"), "jdbc:mysql:", "org.hibernate.dialect.MySQLDialect");
        assertDatasource(Path.of("src/main/resources/datasource-h2.yml"), "jdbc:h2:", "org.hibernate.dialect.H2Dialect");
        assertDatasource(Path.of("../packaging/config/datasource-mysql.yml"), "jdbc:mysql:", "org.hibernate.dialect.MySQLDialect");
        assertDatasource(Path.of("../packaging/config/datasource-h2.yml"), "jdbc:h2:", "org.hibernate.dialect.H2Dialect");
    }

    @Test
    void apiProfilesDefaultMcpCommunicationToSharedEncryptedCredential() throws Exception {
        for (Path profile : List.of(
            Path.of("src/main/resources/application-dev.yml"),
            Path.of("src/main/resources/application-prod.yml"),
            Path.of("../packaging/config/application-dev.yml"),
            Path.of("../packaging/config/application-prod.yml"))) {
            List<PropertySource<?>> sources = load(profile);
            assertSharedEncryptedDefault(value(sources,
                "chatchat.mcp.center.encrypted-admin-password"), "CHATCHAT_API_MCP_ENCRYPTED_PASSWORD");
            assertSharedEncryptedDefault(value(sources,
                "chatchat.mcp.center.encrypted-invocation-token"), "CHATCHAT_API_MCP_ENCRYPTED_INVOCATION_TOKEN");
        }
    }

    @Test
    void defaultDevelopmentProfileImportsSelectedDatasourceAtRuntime() {
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (ConfigurableApplicationContext context = application.run(
            "--spring.config.location=classpath:/application.yml",
            "--spring.main.banner-mode=off",
            "--debug=false",
            "--logging.level.root=OFF"
        )) {
            assertThat(context.getEnvironment().getDefaultProfiles()).contains("dev");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:mysql:");
            assertThat(context.getEnvironment().getProperty("spring.jpa.database-platform"))
                .isEqualTo("org.hibernate.dialect.MySQLDialect");
        }
    }

    private void assertProfileSelects(Path path, String expectedImport) throws Exception {
        List<PropertySource<?>> sources = load(path);
        assertThat(value(sources, "spring.config.import")).isEqualTo(expectedImport);
        assertThat(value(sources, "spring.datasource.url")).isNull();
        assertThat(value(sources, "spring.datasource.username")).isNull();
        assertThat(value(sources, "spring.datasource.password")).isNull();
    }

    private void assertDatasource(Path path, String jdbcPrefix, String dialect) throws Exception {
        List<PropertySource<?>> sources = load(path);
        assertThat(propertyDefaultValue(value(sources, "spring.datasource.url"))).startsWith(jdbcPrefix);
        assertThat(value(sources, "spring.datasource.driver-class-name")).isNotNull();
        assertThat(value(sources, "spring.jpa.database-platform")).isEqualTo(dialect);
    }

    private String propertyDefaultValue(Object rawValue) {
        String value = String.valueOf(rawValue);
        if (!value.startsWith("${") || !value.endsWith("}")) return value;
        int separator = value.indexOf(':', 2);
        return separator < 0 ? value : value.substring(separator + 1, value.length() - 1);
    }

    private List<PropertySource<?>> load(Path path) throws Exception {
        return loader.load(path.toString(), new FileSystemResource(path));
    }

    private Object value(List<PropertySource<?>> sources, String key) {
        return sources.stream()
            .map(source -> source.getProperty(key))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private void assertSharedEncryptedDefault(Object value, String overrideName) {
        assertThat(String.valueOf(value))
            .contains(overrideName)
            .contains("chatchat.internal-credential.encrypted-secret");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
