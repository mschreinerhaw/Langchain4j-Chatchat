package com.chatchat.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedPropertyEnvironmentPostProcessorTest {

    @Test
    void leavesDedicatedCredentialCipherFieldsWrappedForStrictCredentialBinding() {
        String key = "test-crypto-key";
        String credentialCipher = InternalSecretCipher.encrypt("internal-secret", key);
        String regularCipher = InternalSecretCipher.encrypt("regular-secret", key);
        MockEnvironment environment = new MockEnvironment()
            .withProperty("chatchat.internal-credential.crypto-key", key)
            .withProperty("chatchat.internal-credential.encrypted-secret", credentialCipher)
            .withProperty("sample.encrypted-value", regularCipher);

        new EncryptedPropertyEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("chatchat.internal-credential.encrypted-secret"))
            .isEqualTo(credentialCipher);
        assertThat(environment.getProperty("sample.encrypted-value")).isEqualTo("regular-secret");
    }
}
