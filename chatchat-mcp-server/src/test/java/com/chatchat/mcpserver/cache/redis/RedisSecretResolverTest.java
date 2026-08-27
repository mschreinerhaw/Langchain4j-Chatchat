package com.chatchat.mcpserver.cache.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSecretResolverTest {

    @Test
    void preservesInlineValueForExistingAdminManagedConfigurations() {
        assertThat(RedisSecretResolver.resolve("existing-secret")).isEqualTo("existing-secret");
    }

    @Test
    void rejectsSecretFilesOutsideDockerSecretBoundary() {
        assertThatThrownBy(() -> RedisSecretResolver.resolve("file:./redis-password.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/run/secrets");
    }

    @Test
    void reportsMissingDockerSecretWithoutDisclosingPathOrValue() {
        assertThatThrownBy(() -> RedisSecretResolver.resolve("file:/run/secrets/not-present"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Redis secret file cannot be read");
    }
}
