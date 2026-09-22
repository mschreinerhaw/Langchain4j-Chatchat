package com.chatchat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatchat.migration.DataMigrationApplication.Engine;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetBootstrapTest {
    @Test
    void packagedSchemasContainOnlySupportedStatements() throws Exception {
        for (Engine engine : Engine.values()) {
            for (String module : List.of("api", "mcp")) {
                assertTrue(TargetBootstrap.schemaStatements(engine, module).size()
                        >= MigrationSchema.expectedTables(module).size());
            }
        }
    }

    @Test
    void splitsSqlWithoutBreakingQuotedSemicolonsAndComments() {
        List<String> statements = TargetBootstrap.splitSql("-- comment; ignored\n"
                + "INSERT INTO sample(value) VALUES ('a;''b'); /* note; */ "
                + "INSERT INTO sample(value) VALUES ('c');");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;''b'"));
    }

    @Test
    void packagedStarterDataScriptsAreReadable() throws Exception {
        for (String engine : List.of("mysql", "postgresql")) {
            for (String file : List.of("chatchat-api-securities-seed.sql", "chatchat-mcp-securities-seed.sql")) {
                List<String> statements = TargetBootstrap.splitSql(MigrationSchema.resourceSql(engine, file));
                assertEquals(file.startsWith("chatchat-api") ? 4 : 1, statements.size());
                for (String statement : statements) {
                    assertTrue(TargetBootstrap.parseSeedRows(statement).size() > 0);
                }
            }
        }
    }

    @Test
    void splitsStarterValuesAtRowBoundariesOnly() {
        assertEquals(List.of("('first', 'a,b')", "('second', concat('a', 'b'))"),
                TargetBootstrap.valueTuples("('first', 'a,b'), ('second', concat('a', 'b'))"));
    }
}
