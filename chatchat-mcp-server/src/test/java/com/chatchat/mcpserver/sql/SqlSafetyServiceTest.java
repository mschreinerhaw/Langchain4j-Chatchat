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
}
