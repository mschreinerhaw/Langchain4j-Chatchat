package com.chatchat.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashCodecTest {

    @Test
    void hashesPasswordWithSaltAndVerifiesWithoutStoringPlaintext() {
        String first = PasswordHashCodec.encode("admin123");
        String second = PasswordHashCodec.encode("admin123");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("admin123");
        assertThat(PasswordHashCodec.isEncoded(first)).isTrue();
        assertThat(PasswordHashCodec.matches("admin123", first)).isTrue();
        assertThat(PasswordHashCodec.matches("wrong", first)).isFalse();
    }
}
