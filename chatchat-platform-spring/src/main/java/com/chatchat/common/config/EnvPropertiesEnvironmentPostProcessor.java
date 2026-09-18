package com.chatchat.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads optional deployment overrides from {@code config/env.properties}.
 *
 * <p>The file uses regular Spring property names, including bracket notation for
 * map keys. Command-line arguments, JVM system properties, and operating-system
 * environment variables retain higher precedence. Values from this file override
 * application YAML so operators can choose either source without duplicating model
 * resolution logic.</p>
 */
public class EnvPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "chatchatEnvProperties";
    static final String LOCATION_PROPERTY = "chatchat.env-properties.location";
    static final String LOCATION_ENV = "CHATCHAT_ENV_PROPERTIES_LOCATION";
    static final String DEFAULT_LOCATION = "config/env.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredLocation = firstNonBlank(
            System.getProperty(LOCATION_PROPERTY),
            System.getenv(LOCATION_ENV),
            environment.getProperty(LOCATION_PROPERTY)
        );
        boolean explicitLocation = configuredLocation != null;
        Path path = Path.of(explicitLocation ? configuredLocation : DEFAULT_LOCATION)
            .toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            if (explicitLocation) {
                throw new IllegalStateException("Configured env.properties file does not exist: " + path);
            }
            return;
        }

        Properties values = load(path);
        if (values.isEmpty()) {
            return;
        }
        PropertiesPropertySource propertySource = new PropertiesPropertySource(PROPERTY_SOURCE_NAME, values);
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.replace(PROPERTY_SOURCE_NAME, propertySource);
        } else if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, propertySource);
        } else {
            sources.addFirst(propertySource);
        }
    }

    @Override
    public int getOrder() {
        // Run after Spring ConfigData (+10) and before credential decryption (+20).
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    private Properties load(Path path) {
        Properties loaded = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load env.properties: " + path, ex);
        }
        Properties normalized = new Properties();
        loaded.forEach((key, value) -> normalized.put(String.valueOf(key).trim(), value));
        return normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
