package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InteractionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysAuditLogRepository auditLogRepository;

    @Autowired
    private McpToolPermissionRepository toolPermissionRepository;

    @MockBean
    private ToolRegistry toolRegistry;

    @Test
    void toolDirectWritesPersistentAuditLog() throws Exception {
        String tenantId = "tenant-p4-001";
        String toolName = "mcp_demo_web_search";
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("Demo Web Search")
            .author("MCP:Demo")
            .metadata(Map.of("serviceId", "svc-demo"))
            .build());
        when(toolRegistry.executeEnhancedTool(ArgumentMatchers.eq(toolName), any()))
            .thenReturn(ToolOutput.success(Map.of("result", "ok")));

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", tenantId,
            "userId", "user-p4-001",
            "mode", "tool_direct",
            "toolName", toolName,
            "query", "hello",
            "availableTools", List.of(toolName)
        ));

        mockMvc.perform(post("/api/v1/interactions/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.toolTraces[0].runtimeMetadata.outcome").value("success"));

        List<SysAuditLog> logs = auditLogRepository.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId);
        assertThat(logs).isNotEmpty();
        SysAuditLog log = logs.get(0);
        assertThat(log.getModuleName()).isEqualTo("tool_runtime");
        assertThat(log.getActionName()).isEqualTo("success");
        assertThat(log.getResourceId()).isEqualTo(toolName);
        assertThat(log.getResult()).isEqualTo("success");
    }

    @Test
    void tenantToolPermissionCanDenyToolRuntime() throws Exception {
        String tenantId = "tenant-p4-002";
        String toolName = "mcp_demo_blocked_tool";

        McpToolPermission permission = new McpToolPermission();
        permission.setTenantId(tenantId);
        permission.setTargetType("tenant");
        permission.setTargetId(tenantId);
        permission.setLocalToolName(toolName);
        permission.setEffect("deny");
        permission.setEnabled(true);
        toolPermissionRepository.save(permission);

        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("Blocked Tool")
            .author("MCP:Blocked")
            .build());

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", tenantId,
            "userId", "user-p4-002",
            "mode", "tool_direct",
            "toolName", toolName,
            "query", "blocked",
            "availableTools", List.of(toolName)
        ));

        mockMvc.perform(post("/api/v1/interactions/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.toolTraces[0].runtimeMetadata.outcome").value("denied"))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("failed")));

        verify(toolRegistry, never()).executeEnhancedTool(ArgumentMatchers.eq(toolName), any());

        List<SysAuditLog> logs = auditLogRepository.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId);
        assertThat(logs).isNotEmpty();
        SysAuditLog log = logs.get(0);
        assertThat(log.getModuleName()).isEqualTo("tool_runtime");
        assertThat(log.getActionName()).isEqualTo("denied");
        assertThat(log.getResult()).isEqualTo("denied");
    }
}
