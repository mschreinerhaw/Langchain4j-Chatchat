package com.chatchat.mcpserver.market;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialQueryPoolConfigurationTest {

    @Test
    void startsDedicatedPoolAndExecutesBoundedPositionalAndNamedReads() {
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUrl("jdbc:h2:mem:isolated-financial-pool;MODE=MySQL;DB_CLOSE_DELAY=-1");
        primary.setUsername("sa");
        primary.setPassword("");
        primary.setDriverClassName("org.h2.Driver");

        FinancialQueryPoolProperties properties = new FinancialQueryPoolProperties();
        properties.setMaximumPoolSize(2);
        properties.setMinimumIdle(0);
        properties.setConnectionTimeoutMs(500);
        properties.setQueryTimeoutSeconds(2);

        FinancialQueryPoolConfiguration configuration = new FinancialQueryPoolConfiguration();
        try (FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations reads =
                 configuration.financialReadOperations(primary, properties)) {
            assertThat(reads.queryForList("select ? as metric_value", 7))
                .singleElement().satisfies(row -> assertThat(row).containsValue(7));
            assertThat(reads.queryForList("select cast(:value as integer) as metric_value",
                    Map.of("value", 9), 1, 2))
                .singleElement().satisfies(row -> assertThat(row).containsValue(9));
        }
    }
}
