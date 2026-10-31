package com.chatchat.chat.skills.federation;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class NativeSkillSchemaMigrationTest {
    @Test void additiveMigrationIsIdempotentAndPreservesLegacyNullAndContent() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:native-skill-migration;DB_CLOSE_DELAY=-1")) {
            try(var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE ds_domain_skill (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), markdown_content TEXT)");
                statement.execute("INSERT INTO ds_domain_skill VALUES ('old','default','# Existing requirements')");
            }
            var migration=new McpSkillFederationSchemaMigrator(new JdbcTemplate());
            migration.migrate(connection); migration.migrate(connection);
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT markdown_content,runtime_compilation_enabled,compilation_status,native_source_id FROM ds_domain_skill WHERE id='old'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("# Existing requirements");
                assertThat(rows.getObject(2)).isNull(); assertThat(rows.getString(3)).isNull(); assertThat(rows.getString(4)).isNull();
            }
        }
    }
}
