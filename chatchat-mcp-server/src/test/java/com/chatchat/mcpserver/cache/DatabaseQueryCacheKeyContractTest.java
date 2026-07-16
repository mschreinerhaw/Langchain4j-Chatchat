package com.chatchat.mcpserver.cache;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseQueryCacheKeyContractTest {

    @Test
    void usesTemplateIdAsStableNamespaceAndCanonicalParametersAsIdentity() {
        DatabaseQueryCacheConfig policy = new DatabaseQueryCacheConfig();
        policy.setKeyStrategy("TEMPLATE_ID_PARAMS_DATASOURCE");
        DatabaseQueryCacheConfigService configService = mock(DatabaseQueryCacheConfigService.class);
        when(configService.current()).thenReturn(policy);
        DatabaseQueryCacheService service = new DatabaseQueryCacheService(
            new McpCacheProperties(),
            mock(McpRocksDbStore.class),
            mock(RedisCacheStore.class),
            new ObjectMapper(),
            configService
        );
        DatabaseQueryConfig template = template("template-orders", "order_query", "select * from orders");
        Map<String, Object> firstParameters = new LinkedHashMap<>();
        firstParameters.put("market", "SH");
        firstParameters.put("date", "2026-07-16");
        Map<String, Object> reorderedParameters = new LinkedHashMap<>();
        reorderedParameters.put("date", "2026-07-16");
        reorderedParameters.put("market", "SH");

        String first = service.key(template, firstParameters);
        String reordered = service.key(template, reorderedParameters);

        assertThat(first).startsWith("db-query-cache:v2:template:template-orders:");
        assertThat(reordered).isEqualTo(first);

        template.setToolName("renamed_order_query");
        template.setSqlTemplate("select order_id from orders");
        assertThat(service.key(template, reorderedParameters)).isEqualTo(first);

        template.setId("template-orders-v2");
        assertThat(service.key(template, reorderedParameters))
            .startsWith("db-query-cache:v2:template:template-orders-v2:")
            .isNotEqualTo(first);
    }

    @Test
    void migratesLegacySqlStrategiesToTemplateIdentity() {
        DatabaseQueryCacheConfigRepository repository = mock(DatabaseQueryCacheConfigRepository.class);
        DatabaseQueryCacheConfig stored = new DatabaseQueryCacheConfig();
        stored.setKeyStrategy("NORMALIZED_SQL_PARAMS");
        when(repository.findById(DatabaseQueryCacheConfig.DEFAULT_ID)).thenReturn(Optional.of(stored));

        DatabaseQueryCacheConfig current = new DatabaseQueryCacheConfigService(repository).current();

        assertThat(current.getKeyStrategy()).isEqualTo("TEMPLATE_ID_PARAMS_DATASOURCE");
    }

    private DatabaseQueryConfig template(String id, String toolName, String sql) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId(id);
        config.setToolName(toolName);
        config.setTitle(toolName);
        config.setDatasourceId("datasource-1");
        config.setSqlTemplate(sql);
        return config;
    }
}
