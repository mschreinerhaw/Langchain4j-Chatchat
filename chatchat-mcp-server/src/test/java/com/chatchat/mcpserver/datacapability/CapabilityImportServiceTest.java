package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.CapabilityAdapter;
import com.chatchat.mcpserver.datacapability.importing.*;
import com.chatchat.mcpserver.database.category.DataQueryCategoryService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CapabilityImportServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, CapabilityEntity> store = new ConcurrentHashMap<>();
    private final CapabilityRepository repository = mock(CapabilityRepository.class);
    private final CapabilityImportRepository batches = mock(CapabilityImportRepository.class);
    private final CapabilityAdapter adapter = mock(CapabilityAdapter.class);
    private final CapabilityService capabilities;
    private final CapabilityImportService imports;
    CapabilityImportServiceTest() {
        when(adapter.type()).thenReturn(CapabilityType.RELATIONAL);
        when(repository.existsById(anyString())).thenAnswer(i -> store.containsKey(i.getArgument(0)));
        when(repository.saveAndFlush(any())).thenAnswer(i -> { CapabilityEntity e = i.getArgument(0); store.put(e.getCode(), e); return e; });
        when(batches.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        capabilities = new CapabilityService(repository, json, List.of(adapter), mock(DataQueryCategoryService.class));
        imports = new CapabilityImportService(capabilities, repository, batches, json);
    }
    private JsonNode definition(String code) {
        return json.valueToTree(new CapabilityDefinition(code, "查询", "测试", CapabilityType.RELATIONAL, null, "db", "SELECT 1",
            null, null, null, 30, 10, false, false, false));
    }
    @Test void malformedAndDuplicateRowsDoNotPreventSuccessfulImports() throws Exception {
        var result = imports.importDefinitions(List.of(definition("first_query"), json.readTree("{\"type\":\"BOGUS\"}"),
            definition("first_query"), definition("last_query")), false);
        assertThat(result.getSucceeded()).isEqualTo(2); assertThat(result.getFailed()).isEqualTo(2);
        assertThat(store.keySet()).containsExactlyInAnyOrder("first_query", "last_query");
        var feedback = json.readTree(result.getResultsJson());
        assertThat(feedback.get(1).get("row").asInt()).isEqualTo(2);
        assertThat(feedback.get(2).get("error").asText()).contains("Duplicate");
        verify(batches).saveAndFlush(result);
    }
    @Test void dryRunValidatesWithoutRegisteringCapabilities() {
        var result = imports.importDefinitions(List.of(definition("valid_query")), true);
        assertThat(result.getSucceeded()).isEqualTo(1); assertThat(store).isEmpty();
        assertThat(result.getResultsJson()).contains("VALID");
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void rejectsBadSchemaLimitsAndUnknownTypesBeforeSaving() {
        var invalid = new CapabilityDefinition("bad_query", "查询", null, CapabilityType.RELATIONAL, null, "db", "SELECT 1",
            Map.of("type", "object", "properties", Map.of(), "required", List.of("undeclared")), null, null, 30, 10, false, false, false);
        assertThatThrownBy(() -> capabilities.create(invalid)).hasMessageContaining("Required");
        verify(repository, never()).saveAndFlush(any());
    }
}
