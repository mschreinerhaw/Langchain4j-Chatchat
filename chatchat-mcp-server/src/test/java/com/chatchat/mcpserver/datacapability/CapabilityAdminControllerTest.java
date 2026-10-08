package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.admin.CapabilityAdminController;
import com.chatchat.mcpserver.datacapability.calendar.*;
import com.chatchat.mcpserver.datacapability.connection.QueryConnectionService;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.chatchat.mcpserver.datacapability.importing.CapabilityImportService;
import com.chatchat.mcpserver.datacapability.publication.CapabilityMcpPublisher;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CapabilityAdminControllerTest {
    @Test void datasourceReferencesAreReadOnlyAndNeverAcceptConnectionWrites() throws Exception {
        var connections = mock(QueryConnectionService.class);
        when(connections.list(CapabilityType.GRAPH)).thenReturn(List.of(
            new QueryConnectionService.AssetReference("central-asset", "图数据源", CapabilityType.GRAPH, true)));
        var controller = new CapabilityAdminController(mock(CapabilityService.class), mock(CapabilityExecutionService.class),
            mock(CapabilityImportService.class), mock(CapabilityMcpPublisher.class), connections,
            mock(TradingCalendarService.class), mock(SqlDatasourceConfigService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/data-capabilities/connections").param("type", "GRAPH"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("central-asset"))
            .andExpect(jsonPath("$.data[0].endpoint").doesNotExist()).andExpect(jsonPath("$.data[0].headersJson").doesNotExist());
        mvc.perform(post("/api/v1/data-capabilities/connections").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }
    @Test void bindsModuleFilterCodeAsyncFlagAndIsoDates() throws Exception {
        var capabilities = mock(CapabilityService.class);
        var executions = mock(CapabilityExecutionService.class);
        var calendar = mock(TradingCalendarService.class);
        var controller = new CapabilityAdminController(capabilities, executions, mock(CapabilityImportService.class),
            mock(CapabilityMcpPublisher.class), mock(QueryConnectionService.class), calendar, mock(SqlDatasourceConfigService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(capabilities.list(CapabilityType.GRAPH)).thenReturn(List.of());
        mvc.perform(get("/api/v1/data-capabilities").param("type", "GRAPH")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(capabilities).list(CapabilityType.GRAPH);
        when(executions.invoke("query_one", Map.of("name", "Alice"), false, true)).thenReturn(new CapabilityExecution());
        mvc.perform(post("/api/v1/data-capabilities/query_one/invoke").param("async", "true")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alice\"}")).andExpect(status().isOk());
        verify(executions).invoke("query_one", Map.of("name", "Alice"), false, true);
        mvc.perform(get("/api/v1/data-capabilities/calendar/days").param("market", "SSE")
            .param("start", "2026-01-01").param("end", "2026-01-02")).andExpect(status().isOk());
        verify(calendar).range("SSE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
    }
}
