package com.chatchat.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalCredentialPropertiesTest {

    @Test
    void decryptsEncryptedInternalCredential() {
        InternalCredentialProperties properties = new InternalCredentialProperties();
        properties.setCryptoKey("test-crypto-key");
        properties.setEncryptedSecret(InternalSecretCipher.encrypt("internal-secret", "test-crypto-key"));

        assertThat(properties.resolvedSecret()).isEqualTo("internal-secret");
    }

    @Test
    void rejectsLegacyPlaintextInternalCredential() {
        InternalCredentialProperties properties = new InternalCredentialProperties();
        properties.setSecret("internal-secret");

        assertThatThrownBy(properties::resolvedSecret)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no longer allowed");
    }
}
