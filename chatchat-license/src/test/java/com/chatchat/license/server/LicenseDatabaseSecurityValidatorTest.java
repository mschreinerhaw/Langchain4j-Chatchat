package com.chatchat.license.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseDatabaseSecurityValidatorTest {
    @Test
    void rejectsEmptyDefaultAndWeakDatabasePasswords() {
        assertThatThrownBy(() -> new LicenseDatabaseSecurityValidator("").run(null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LicenseDatabaseSecurityValidator("password123").run(null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LicenseDatabaseSecurityValidator("Change-Me_H2#2026!Secure").run(null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsUniqueComplexDatabasePassword() {
        assertThatCode(() -> new LicenseDatabaseSecurityValidator("LmcH2_A9x!7Qp#4Vn@2Ks").run(null))
            .doesNotThrowAnyException();
    }
}
