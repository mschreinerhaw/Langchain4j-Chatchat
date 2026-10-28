package com.chatchat.runtime.news.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

class NewsDeploymentConfigurationTest {
    @Test void composeEnvironmentNamesBindToRealNewsProperties() {
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,Map.of(
            "CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENABLED","true",
            "CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENDPOINT","https://search.example:9200",
            "CHATCHAT_RUNTIME_NEWS_OPENSEARCH_INSECURESSL","false",
            "CHATCHAT_RUNTIME_NEWS_OPENSEARCH_RETENTIONDAYS","9",
            "CHATCHAT_RUNTIME_NEWS_WEBSEARCH_ENABLED","true",
            "CHATCHAT_RUNTIME_NEWS_WEBSEARCH_CACHE_ENABLED","true")));
        var properties=Binder.get(env).bind("chatchat.runtime.news",Bindable.of(NewsRuntimeProperties.class)).get();
        assertThat(properties.getOpenSearch().isEnabled()).isTrue();
        assertThat(properties.getOpenSearch().getEndpoint()).isEqualTo("https://search.example:9200");
        assertThat(properties.getOpenSearch().isInsecureSsl()).isFalse();
        assertThat(properties.getOpenSearch().getRetentionDays()).isEqualTo(9);
        assertThat(properties.getWebSearch().isEnabled()).isTrue();
        assertThat(properties.getWebSearch().getCache().isEnabled()).isTrue();
    }
    @Test void postgresNewsSchemaIsIdempotentAndDoesNotContainMarketOrControlPlaneTables() throws Exception {
        Class.forName("org.postgresql.Driver");
        var source=new DriverManagerDataSource("jdbc:h2:mem:news-postgres-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var schema=new FileSystemResource("../database/init/postgresql/chatchat-runtime-news.sql");
        var populator=new ResourceDatabasePopulator(schema);populator.execute(source);
        var jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO news_source(capability_id,source_code,source_name,source_type,entry_url,enabled,created_at,updated_at) VALUES(1,'keep','existing','RSS','https://example.test',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        populator.execute(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM news_source",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isEqualTo(4);
    }
}
