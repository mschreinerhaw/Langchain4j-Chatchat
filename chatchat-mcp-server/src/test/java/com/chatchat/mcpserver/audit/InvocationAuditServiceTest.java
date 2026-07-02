package com.chatchat.mcpserver.audit;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvocationAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsApiTemplateAuditFields() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = new InvocationAuditService(store, objectMapper);
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-1");
        config.setToolName("order_status_api");
        config.setTitle("Order status API");
        ApiInvokeResult result = new ApiInvokeResult(true, 200, Map.of(), Map.of("status", "paid"), "{}", null);

        service.recordApiCall(config, Map.of("username", "alice", "orderId", "O-1"), result, 12L);

        InvocationAuditLog log = savedLog(store);
        assertThat(log.getCaller()).isEqualTo("alice");
        assertThat(log.getTemplateType()).isEqualTo("api_service");
        assertThat(log.getTemplateId()).isEqualTo("order_status_api");
        assertThat(log.getTemplateName()).isEqualTo("Order status API");
        assertThat(log.getCreatedAt()).isNotNull();
    }

    @Test
    void recordsDatabaseQueryTemplateAuditFields() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = new InvocationAuditService(store, objectMapper);
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("db-query-1");
        config.setToolName("order_status_query");
        config.setTitle("Order status query");
        config.setDatasourceId("mysql-main");
        config.setBusinessGroup("order_services");
        ToolOutput output = ToolOutput.success(Map.of("rowCount", 1, "rows", List.of(Map.of("status", "paid"))));

        service.recordDatabaseQueryCall(config, Map.of("userId", "bob", "orderId", "O-2"), output, 18L);

        InvocationAuditLog log = savedLog(store);
        assertThat(log.getCaller()).isEqualTo("bob");
        assertThat(log.getTargetType()).isEqualTo("DATABASE_QUERY");
        assertThat(log.getTemplateType()).isEqualTo("database_query");
        assertThat(log.getTemplateId()).isEqualTo("order_status_query");
        assertThat(log.getTemplateName()).isEqualTo("Order status query");
        assertThat(log.getRequestSummary()).contains("databaseQueryId", "db-query-1", "order_services");
    }

    private McpRocksDbStore usableStore() {
        McpRocksDbStore store = mock(McpRocksDbStore.class);
        when(store.isUsable()).thenReturn(true);
        return store;
    }

    private InvocationAuditLog savedLog(McpRocksDbStore store) throws Exception {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(store, times(2)).put(keyCaptor.capture(), valueCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        List<byte[]> values = valueCaptor.getAllValues();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).startsWith("audit:data:")) {
                return objectMapper.readValue(new String(values.get(i), StandardCharsets.UTF_8), InvocationAuditLog.class);
            }
        }
        throw new AssertionError("No audit:data entry was written");
    }
}
