package com.chatchat.mcpserver.templatepublication.binding;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateQueryBindingSchemaMigratorTest {

    @Test
    void replacesLegacyServiceRoleUniquenessWithDomainSubjectUniqueness() {
        JdbcTemplate jdbc = jdbc("migration");
        jdbc.execute("""
            CREATE TABLE mcp_template_query_binding (
                id VARCHAR(64) PRIMARY KEY,
                service_id VARCHAR(64) NOT NULL,
                role_id VARCHAR(64) NOT NULL,
                domain_code VARCHAR(64) NOT NULL,
                subject_type VARCHAR(16) NOT NULL,
                subject_id VARCHAR(128) NOT NULL,
                CONSTRAINT uk_template_query_service_role UNIQUE (service_id, role_id)
            )
            """);

        new TemplateQueryBindingSchemaMigrator(jdbc).run(null);

        insert(jdbc, "one", "customer_service", "ROLE", "role-1");
        insert(jdbc, "two", "wealth_service", "ROLE", "role-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_template_query_binding", Integer.class))
            .isEqualTo(2);
        assertThatThrownBy(() -> insert(jdbc, "three", "customer_service", "ROLE", "role-1"))
            .hasMessageContaining("Unique index or primary key violation");
    }

    @Test
    void migrationIsIdempotent() {
        JdbcTemplate jdbc = jdbc("idempotent");
        jdbc.execute("""
            CREATE TABLE mcp_template_query_binding (
                id VARCHAR(64) PRIMARY KEY,
                service_id VARCHAR(64) NOT NULL,
                role_id VARCHAR(64) NOT NULL,
                domain_code VARCHAR(64) NOT NULL,
                subject_type VARCHAR(16) NOT NULL,
                subject_id VARCHAR(128) NOT NULL,
                CONSTRAINT uk_template_query_service_role_domain_subject
                    UNIQUE (service_id, role_id, domain_code, subject_type, subject_id)
            )
            """);
        TemplateQueryBindingSchemaMigrator migrator = new TemplateQueryBindingSchemaMigrator(jdbc);

        migrator.run(null);
        migrator.run(null);

        insert(jdbc, "one", "customer_service", "ROLE", "role-1");
        insert(jdbc, "two", "wealth_service", "ROLE", "role-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_template_query_binding", Integer.class))
            .isEqualTo(2);
    }

    private JdbcTemplate jdbc(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(dataSource);
    }

    private void insert(JdbcTemplate jdbc, String id, String domain, String subjectType, String subjectId) {
        jdbc.update("INSERT INTO mcp_template_query_binding "
                + "(id, service_id, role_id, domain_code, subject_type, subject_id) VALUES (?, ?, ?, ?, ?, ?)",
            id, "chatchat-mcp-server", "role-1", domain, subjectType, subjectId);
    }
}
