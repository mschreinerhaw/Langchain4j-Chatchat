package com.chatchat.api.testing;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Auth integration tests run against the same required policy migration as production. */
@TestConfiguration(proxyBeanMethods=false)
public class RuntimeSemanticPolicyFixture {
    @Bean ApplicationRunner authorizationTestPolicyMigration(JdbcTemplate jdbc) {
        return args -> new ResourceDatabasePopulator(new FileSystemResource(
            "../database/migration/h2/V20261009_01__agent_runtime_semantic_policy.sql")).execute(jdbc.getDataSource());
    }
}
