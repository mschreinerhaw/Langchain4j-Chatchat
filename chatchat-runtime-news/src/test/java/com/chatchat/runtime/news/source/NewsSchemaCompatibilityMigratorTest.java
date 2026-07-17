package com.chatchat.runtime.news.source;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThatCode;

class NewsSchemaCompatibilityMigratorTest {

    @Test
    void convertsLegacyH2ClosedEnumToEvolvableVarchar() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:news-schema-compat;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table news_source (id bigint primary key, source_type enum('API','WEB_LIST') not null)");
        jdbc.update("insert into news_source(id, source_type) values (1, 'WEB_LIST')");

        new NewsSchemaCompatibilityMigrator(dataSource).run(null);

        assertThatCode(() -> jdbc.update(
            "update news_source set source_type = 'CNINFO_ANNOUNCEMENTS' where id = 1"))
            .doesNotThrowAnyException();
    }
}
