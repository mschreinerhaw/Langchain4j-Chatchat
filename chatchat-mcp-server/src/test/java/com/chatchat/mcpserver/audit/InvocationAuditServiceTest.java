package com.chatchat.mcpserver.audit;

import com.chatchat.common.audit.AuditQueryProperties;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.api.invocation.ApiInvokeResult;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.cache.rocksdb.McpRocksDbStore;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvocationAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsApiTemplateAuditFields() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);
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
        InvocationAuditService service = service(store);
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("db-query-1");
        config.setToolName("order_status_query");
        config.setTitle("Order status query");
        config.setDatasourceId("mysql-main");
        config.setBusinessGroup("order_services");
        config.setSqlTemplate("SELECT status FROM orders WHERE order_id = {{orderId}}");
        ToolOutput output = ToolOutput.success(Map.of("rowCount", 1, "rows", List.of(Map.of("status", "paid"))));

        service.recordDatabaseQueryCall(config, Map.of("userId", "bob", "orderId", "O-2"), output, 18L);

        InvocationAuditLog log = savedLog(store);
        assertThat(log.getCaller()).isEqualTo("bob");
        assertThat(log.getTargetType()).isEqualTo("DATABASE_QUERY");
        assertThat(log.getTemplateType()).isEqualTo("database_query");
        assertThat(log.getTemplateId()).isEqualTo("order_status_query");
        assertThat(log.getTemplateName()).isEqualTo("Order status query");
        assertThat(log.getRequestSummary()).contains("databaseQueryId", "db-query-1", "order_services");
        assertThat(log.getAuditCategory()).isEqualTo("COMMAND_EXECUTION");
        assertThat(log.getCommandType()).isEqualTo("DATABASE_QUERY");
        assertThat(log.getUsername()).isEqualTo("bob");
        assertThat(log.getDatasourceName()).isEqualTo("Order status query");
        assertThat(log.getCommandSummary()).isEqualTo("SELECT status FROM orders WHERE order_id = {{orderId}}");
    }

    @Test
    void doesNotPersistProtocolHeartbeatAudit() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);

        service.recordMcpTransportRequest("POST", "/mcp", null, "mcp-client", "test", 200, 1L,
            null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

        verify(store, never()).put(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void doesNotPersistHeartbeatOnlyBatchAudit() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);

        service.recordMcpTransportRequest("POST", "/mcp", null, "mcp-client", "test", 200, 1L,
            null, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]");

        verify(store, never()).put(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void persistsBatchAuditWhenHeartbeatIsMixedWithBusinessProtocolTraffic() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);

        service.recordMcpTransportRequest("POST", "/mcp", null, "mcp-client", "test", 200, 1L,
            null, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]");

        InvocationAuditLog log = savedLog(store);
        assertThat(log.getTargetType()).isEqualTo("MCP_TRANSPORT");
        assertThat(log.isSuccess()).isTrue();
    }

    @Test
    void searchesCommandAuditsByCategoryUserDatasourceAndCommandTypeWithPagination() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);
        InvocationAuditLog first = commandLog("1", "alice", "Main MySQL", "SQL_QUERY", "SHOW ENGINE INNODB STATUS", 3);
        InvocationAuditLog second = commandLog("2", "alice", "Main MySQL", "SQL_QUERY", "SHOW PROCESSLIST", 2);
        InvocationAuditLog unrelated = commandLog("3", "bob", "Ops Host", "LINUX_COMMAND", "systemctl status app", 1);
        when(store.get("audit:data:1")).thenReturn(objectMapper.writeValueAsBytes(first));
        when(store.get("audit:data:2")).thenReturn(objectMapper.writeValueAsBytes(second));
        when(store.get("audit:data:3")).thenReturn(objectMapper.writeValueAsBytes(unrelated));
        doAnswer(invocation -> {
            Consumer<McpRocksDbStore.KeyValue> consumer = invocation.getArgument(2);
            consumer.accept(new McpRocksDbStore.KeyValue("audit:index:1".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8)));
            consumer.accept(new McpRocksDbStore.KeyValue("audit:index:2".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8)));
            consumer.accept(new McpRocksDbStore.KeyValue("audit:index:3".getBytes(StandardCharsets.UTF_8), "3".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(store).scan(org.mockito.ArgumentMatchers.eq("audit:index:"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        InvocationAuditService.AuditLogPage page = service.search(new InvocationAuditService.AuditLogSearchQuery(
            1, 1, "SHOW", null, null, null, null, null, null,
            "COMMAND_EXECUTION", "SQL_QUERY", "alice", "Main", null, null, null, null
        ));

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.filteredCount()).isEqualTo(2);
        assertThat(page.items()).extracting(InvocationAuditLog::getId).containsExactly("1");
    }

    @Test
    void defaultsToConfiguredRecentWindowWithoutDeletingHistoricalAudits() throws Exception {
        McpRocksDbStore store = usableStore();
        AuditQueryProperties properties = new AuditQueryProperties();
        properties.setDefaultQueryWindowDays(3);
        InvocationAuditService service = new InvocationAuditService(store, objectMapper, properties);
        InvocationAuditLog recent = commandLog("recent", "alice", "Main", "SQL_QUERY", "SELECT 1", 60);
        InvocationAuditLog historical = commandLog(
            "historical", "alice", "Main", "SQL_QUERY", "SELECT 2", 4 * 24 * 60 * 60L);
        when(store.get("audit:data:recent")).thenReturn(objectMapper.writeValueAsBytes(recent));
        when(store.get("audit:data:historical")).thenReturn(objectMapper.writeValueAsBytes(historical));
        doAnswer(invocation -> {
            Consumer<McpRocksDbStore.KeyValue> consumer = invocation.getArgument(2);
            consumer.accept(new McpRocksDbStore.KeyValue(
                "audit:index:recent".getBytes(StandardCharsets.UTF_8),
                "recent".getBytes(StandardCharsets.UTF_8)));
            consumer.accept(new McpRocksDbStore.KeyValue(
                "audit:index:historical".getBytes(StandardCharsets.UTF_8),
                "historical".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(store).scan(org.mockito.ArgumentMatchers.eq("audit:index:"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        InvocationAuditService.AuditLogPage recentPage = service.search(null);
        InvocationAuditService.AuditLogPage historicalPage = service.search(new InvocationAuditService.AuditLogSearchQuery(
            1, 20, null, null, null, null, null, null, null,
            null, null, null, null, null, null, 0L, null
        ));

        assertThat(recentPage.totalCount()).isEqualTo(2);
        assertThat(recentPage.items()).extracting(InvocationAuditLog::getId).containsExactly("recent");
        assertThat(historicalPage.items()).extracting(InvocationAuditLog::getId)
            .containsExactly("recent", "historical");
    }

    @Test
    void deletesOnlyAuditsInInclusiveTimeRangeAndRequestedCategory() throws Exception {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);
        long now = System.currentTimeMillis();
        InvocationAuditLog commandInRange = commandLogAt("command-in", "COMMAND_EXECUTION", now - 1_000);
        InvocationAuditLog invocationInRange = commandLogAt("invocation-in", "TOOL_INVOCATION", now - 2_000);
        InvocationAuditLog commandOutOfRange = commandLogAt("command-out", "COMMAND_EXECUTION", now - 20_000);
        when(store.get("audit:data:command-in")).thenReturn(objectMapper.writeValueAsBytes(commandInRange));
        when(store.get("audit:data:invocation-in")).thenReturn(objectMapper.writeValueAsBytes(invocationInRange));
        when(store.get("audit:data:command-out")).thenReturn(objectMapper.writeValueAsBytes(commandOutOfRange));
        doAnswer(invocation -> {
            Consumer<McpRocksDbStore.KeyValue> consumer = invocation.getArgument(2);
            auditIndex(consumer, "index-command-in", "command-in");
            auditIndex(consumer, "index-invocation-in", "invocation-in");
            auditIndex(consumer, "index-command-out", "command-out");
            return null;
        }).when(store).scan(org.mockito.ArgumentMatchers.eq("audit:index:"),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        long deleted = service.deleteByTimeRange(now - 5_000, now, "COMMAND_EXECUTION");

        assertThat(deleted).isEqualTo(1);
        verify(store).delete("audit:data:command-in");
        verify(store).delete("audit:index:index-command-in".getBytes(StandardCharsets.UTF_8));
        verify(store, never()).delete("audit:data:invocation-in");
        verify(store, never()).delete("audit:data:command-out");
    }

    @Test
    void rejectsReversedCleanupTimeRangeWithoutScanning() {
        McpRocksDbStore store = usableStore();
        InvocationAuditService service = service(store);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.deleteByTimeRange(2_000, 1_000, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("start time");
        verify(store, never()).scan(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    private InvocationAuditLog commandLog(String id, String username, String datasourceName,
                                           String commandType, String commandSummary, long secondsAgo) {
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(id);
        log.setAuditCategory("COMMAND_EXECUTION");
        log.setCommandType(commandType);
        log.setUsername(username);
        log.setCaller(username);
        log.setDatasourceName(datasourceName);
        log.setCommandSummary(commandSummary);
        log.setSuccess(true);
        log.setCreatedAt(Instant.now().minusSeconds(secondsAgo));
        return log;
    }

    private InvocationAuditLog commandLogAt(String id, String auditCategory, long createdAt) {
        InvocationAuditLog log = commandLog(id, "alice", "Main", "SQL_QUERY", "SELECT 1", 0);
        log.setAuditCategory(auditCategory);
        log.setCreatedAt(Instant.ofEpochMilli(createdAt));
        return log;
    }

    private void auditIndex(Consumer<McpRocksDbStore.KeyValue> consumer, String indexSuffix, String id) {
        consumer.accept(new McpRocksDbStore.KeyValue(
            ("audit:index:" + indexSuffix).getBytes(StandardCharsets.UTF_8),
            id.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private McpRocksDbStore usableStore() {
        McpRocksDbStore store = mock(McpRocksDbStore.class);
        when(store.isUsable()).thenReturn(true);
        return store;
    }

    private InvocationAuditService service(McpRocksDbStore store) {
        return new InvocationAuditService(store, objectMapper, new AuditQueryProperties());
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
