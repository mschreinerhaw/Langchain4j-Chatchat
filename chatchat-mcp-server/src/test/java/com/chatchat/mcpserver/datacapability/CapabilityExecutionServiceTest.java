package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityExecutionServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final CapabilityExecutionRepository repository = mock(CapabilityExecutionRepository.class);
    private final CapabilityAdapter adapter = mock(CapabilityAdapter.class);
    private final Map<String, CapabilityExecution> records = new ConcurrentHashMap<>();
    private final CapabilityExecutionService service = new CapabilityExecutionService(capabilities, repository, new TemplateParameterValidator(json), json);
    CapabilityExecutionServiceTest() {
        when(capabilities.adapter(any())).thenReturn(adapter);
        when(repository.saveAndFlush(any())).thenAnswer(i -> { CapabilityExecution e = i.getArgument(0); records.put(e.getId(), e); return e; });
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(records.get(i.getArgument(0))));
    }
    @AfterEach void close() { service.stop(); }
    private CapabilityDefinition definition(int timeout) {
        return new CapabilityDefinition("query_one", "测试", null, CapabilityType.RELATIONAL, null, "db", "SELECT {{id}}",
            Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")), "required", List.of("id")),
            Map.of("identifier", "id"), Map.of(), timeout, 1, true, true, true);
    }
    @Test void validatesParametersMapsResultsAndTracksExecution() throws Exception {
        when(adapter.execute(any(), anyMap())).thenReturn(new CapabilityAdapter.QueryResult(List.of(Map.of("id", 42), Map.of("id", 43))));
        var result = service.execute(definition(5), Map.of("id", 42, "undeclared", "ignored"), true, false);
        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(json.readTree(result.getResultJson()).at("/rows/0/identifier").asInt()).isEqualTo(42);
        assertThat(json.readTree(result.getResultJson()).get("truncated").asBoolean()).isTrue();
        assertThat(result.getFinishedAt()).isNotNull();
        verify(adapter).execute(any(), eq(Map.of("id", 42)));
    }
    @Test void missingRequiredParameterIsRecordedWithoutCallingDatabase() throws Exception {
        var result = service.execute(definition(5), Map.of(), true, false);
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getError()).contains("id");
        verify(adapter, never()).execute(any(), anyMap());
    }
    @Test void unpublishedApiCannotBeInvoked() {
        var d = definition(5);
        var disabled = new CapabilityDefinition(d.code(), d.title(), null, d.type(), null, d.connectionId(), d.query(),
            d.inputSchema(), d.resultMapping(), d.options(), 5, 1, false, false, true);
        when(capabilities.get(d.code())).thenReturn(disabled);
        assertThatThrownBy(() -> service.invoke(d.code(), Map.of(), false, false)).hasMessageContaining("not published");
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void timeoutIsTerminalEvenWhenAnAdapterReturnsLate() throws Exception {
        CountDownLatch release = new CountDownLatch(1), completed = new CountDownLatch(1);
        when(adapter.execute(any(), anyMap())).thenAnswer(i -> {
            try { release.await(); } catch (InterruptedException ignored) { release.await(); }
            completed.countDown(); return new CapabilityAdapter.QueryResult(List.of(Map.of("id", 1)));
        });
        var result = service.execute(definition(1), Map.of("id", 1), true, false);
        assertThat(result.getStatus()).isEqualTo("TIMED_OUT");
        release.countDown(); assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.get(result.getId()).getStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.getResultJson()).isNull();
    }
    @Test void callerContextReachesWorker() throws Exception {
        var caller = new McpInvocationContext.Context("caller", null, null, "request", null, "user", null, "tenant",
            null, null, null, null, null, null, null, null);
        when(adapter.execute(any(), anyMap())).thenAnswer(i -> {
            assertThat(McpInvocationContext.current()).isSameAs(caller);
            return new CapabilityAdapter.QueryResult(List.of(Map.of("id", 1)));
        });
        try (var scope = McpInvocationContext.open(caller)) {
            assertThat(service.execute(definition(5), Map.of("id", 1), false, false).getStatus()).isEqualTo("SUCCEEDED");
        }
        assertThat(McpInvocationContext.current()).isNull();
    }
}
