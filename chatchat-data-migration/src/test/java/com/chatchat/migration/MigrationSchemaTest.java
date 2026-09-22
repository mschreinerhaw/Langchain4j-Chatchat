package com.chatchat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MigrationSchemaTest {
    @Test
    void apiScopeComesFromPackagedInitSqlAndIgnoresOtherTables() throws Exception {
        Set<String> expected = MigrationSchema.expectedTables("api");
        assertTrue(expected.contains("knowledge_ir_unit"));
        assertTrue(expected.contains("resource_grant"));
        assertFalse(expected.contains("news_analysis_task"));
        assertFalse(expected.contains("platform_model_config"));

        Set<String> actual = new HashSet<>(expected);
        actual.add("news_analysis_task");
        actual.add("platform_model_config");
        assertEquals(expected, MigrationSchema.selectTables(actual, expected, "Source database"));
    }

    @Test
    void missingInitSqlTableIsReportedBeforeMigration() throws Exception {
        Set<String> expected = MigrationSchema.expectedTables("mcp");
        assertTrue(expected.contains("mcp_service_config"));
        Set<String> actual = new HashSet<>(expected);
        actual.remove("mcp_service_config");
        assertThrows(IllegalStateException.class,
                () -> MigrationSchema.selectTables(actual, expected, "Target database"));
    }
}
