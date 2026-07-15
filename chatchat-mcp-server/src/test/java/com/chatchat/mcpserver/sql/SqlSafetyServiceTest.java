package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyServiceTest {

    private final SqlSafetyService service = new SqlSafetyService();

    @Test
    void allowsReadOnlyVendorMaintenanceQueriesWithoutRewritingLimit() {
        assertThat(service.validateAndNormalize("SELECT * FROM v$session", 50))
            .isEqualTo("SELECT * FROM v$session");
        assertThat(service.validateAndNormalize("SELECT * FROM v$lock", 50))
            .isEqualTo("SELECT * FROM v$lock");
        assertThat(service.validateAndNormalize("SELECT lock, update_time FROM audit_lock", 50))
            .isEqualTo("SELECT lock, update_time FROM audit_lock");
        assertThat(service.validateAndNormalize("SELECT * FROM pg_locks WHERE locktype = 'LOCK'", 50))
            .isEqualTo("SELECT * FROM pg_locks WHERE locktype = 'LOCK'");
        assertThat(service.validateAndNormalize("SELECT * FROM sys.dm_io_virtual_file_stats(NULL, NULL)", 50))
            .isEqualTo("SELECT * FROM sys.dm_io_virtual_file_stats(NULL, NULL)");
    }

    @Test
    void stillRejectsExecAndWriteStatements() {
        assertThatThrownBy(() -> service.validateAndNormalize("EXEC sp_spaceused", 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EXEC");
        assertThatThrownBy(() -> service.validateAndNormalize("UPDATE users SET name = 'x'", 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UPDATE");
    }

    @Test
    void rejectsReadOnlyPrefixesThatContainMutatingOrLockingClauses() {
        assertThatThrownBy(() -> service.validateAndNormalize("SELECT * FROM users FOR UPDATE", 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("locking clauses");
        assertThatThrownBy(() -> service.validateAndNormalize("SELECT * INTO OUTFILE '/tmp/users' FROM users", 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SELECT INTO");
        assertThatThrownBy(() -> service.validateAndNormalize("EXPLAIN ANALYZE UPDATE users SET name = 'x'", 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ANALYZE");
    }
}
