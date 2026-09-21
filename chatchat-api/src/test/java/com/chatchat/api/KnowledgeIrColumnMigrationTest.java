package com.chatchat.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIrColumnMigrationTest {
    @Test
    void h2MigrationPreservesLongSectionAndTitle() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:knowledge_ir_column_migration;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table knowledge_ir_unit (source_section varchar(500), title varchar(500))");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "database",
                "migration", "h2", "V20260921_01__knowledge_ir_full_sections.sql")));
        }

        String longValue = "章节".repeat(400);
        jdbc.update("insert into knowledge_ir_unit(source_section, title) values (?, ?)", longValue, longValue);
        assertThat(jdbc.queryForObject("select source_section from knowledge_ir_unit", String.class))
            .isEqualTo(longValue);
        assertThat(jdbc.queryForObject("select title from knowledge_ir_unit", String.class))
            .isEqualTo(longValue);
    }
}
