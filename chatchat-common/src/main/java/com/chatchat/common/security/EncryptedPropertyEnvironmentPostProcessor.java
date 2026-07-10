package com.chatchat.common.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class EncryptedPropertyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String CRYPTO_KEY_PROPERTY = "chatchat.internal-credential.crypto-key";
    private static final String CRYPTO_KEY_FILE_PROPERTY = "chatchat.internal-credential.crypto-key-file";
    private static final String CRYPTO_KEY_ENV = "CHATCHAT_INTERNAL_CRYPTO_KEY";
    private static final String CRYPTO_KEY_FILE_ENV = "CHATCHAT_INTERNAL_CRYPTO_KEY_FILE";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            if (!(source instanceof DecryptingPropertySource) && !isSpringConfigurationPropertySource(source)) {
                names.add(source.getName());
            }
        }
        Supplier<String> cryptoKey = () -> cryptoKey(environment);
        for (String name : names) {
            PropertySource<?> source = sources.get(name);
            if (source != null && !(source instanceof DecryptingPropertySource)) {
                sources.replace(name, new DecryptingPropertySource(source, cryptoKey));
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static String cryptoKey(ConfigurableEnvironment environment) {
        String value = firstNonBlank(System.getenv(CRYPTO_KEY_ENV), System.getProperty(CRYPTO_KEY_ENV));
        if (!value.isBlank()) {
            return value;
        }
        value = readKeyFile(firstNonBlank(System.getenv(CRYPTO_KEY_FILE_ENV), System.getProperty(CRYPTO_KEY_FILE_ENV)));
        if (!value.isBlank()) {
            return value;
        }
        value = rawProperty(environment, CRYPTO_KEY_PROPERTY);
        if (!value.isBlank()) {
            return value;
        }
        return readKeyFile(rawProperty(environment, CRYPTO_KEY_FILE_PROPERTY));
    }

    private static String rawProperty(ConfigurableEnvironment environment, String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source instanceof DecryptingPropertySource decrypting
                ? decrypting.getRawProperty(name)
                : source.getProperty(name);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String readKeyFile(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(path.trim())).trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read internal credential crypto key file: " + path, ex);
        }
    }

    private static boolean isSpringConfigurationPropertySource(PropertySource<?> source) {
        return source.getClass().getName().contains("ConfigurationPropertySourcesPropertySource");
    }

    private static final class DecryptingPropertySource extends EnumerablePropertySource<PropertySource<?>> {

        private final Supplier<String> cryptoKey;

        private DecryptingPropertySource(PropertySource<?> delegate, Supplier<String> cryptoKey) {
            super(delegate.getName(), delegate);
            this.cryptoKey = cryptoKey;
        }

        @Override
        public Object getProperty(String name) {
            Object value = getRawProperty(name);
            if (value instanceof String text && InternalSecretCipher.isEncrypted(text)) {
                return InternalSecretCipher.decryptIfNecessary(text, cryptoKey.get());
            }
            return value;
        }

        @Override
        public String[] getPropertyNames() {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                return enumerable.getPropertyNames();
            }
            return new String[0];
        }

        private Object getRawProperty(String name) {
            return source.getProperty(name);
        }
    }
}
