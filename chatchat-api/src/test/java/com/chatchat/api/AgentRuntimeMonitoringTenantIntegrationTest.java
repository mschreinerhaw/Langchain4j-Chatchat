package com.chatchat.api;

import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.chat.task.event.AgentEvent;
import com.chatchat.chat.task.event.AgentEventStore;
import com.chatchat.chat.task.core.AgentTaskLatestEntity;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "chatchat.test.context=agent-runtime-monitoring-tenant",
    "chatchat.api.auth.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentRuntimeMonitoringTenantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnterpriseAdminService adminService;

    @Autowired
    private SysPermissionRepository permissionRepository;

    @Autowired
    private AgentTaskLatestRepository taskRepository;

    @Autowired
    private AgentEventStore eventStore;

    @Autowired
    private InterpretationPlanStore planStore;

    @Autowired
    private SysAuditLogRepository auditLogRepository;

    @MockBean
    private ChatModel chatModel;

    @Test
    void everyRuntimeMonitoringResultUsesAuthenticatedTenantAndMaintainedPermission() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SysTenant tenantA = adminService.saveTenant(tenant("monitor-a-" + suffix));
        SysTenant tenantB = adminService.saveTenant(tenant("monitor-b-" + suffix));
        SysRole monitorRoleA = adminService.saveRole(role(tenantA.getId(), "MONITOR_A_" + suffix));
        SysRole monitorRoleB = adminService.saveRole(role(tenantB.getId(), "MONITOR_B_" + suffix));
        SysRole unprivilegedRole = adminService.saveRole(role(tenantA.getId(), "NO_MONITOR_" + suffix));
        SysPermission menuPermission = permission("platform:tasks");
        SysPermission monitorPermission = permission("platform:tasks:monitor");
        adminService.saveRoleAuthorization(monitorRoleA.getId(), authorization(menuPermission, monitorPermission));
        adminService.saveRoleAuthorization(monitorRoleB.getId(), authorization(menuPermission, monitorPermission));

        EnterpriseAdminService.UserView userA = adminService.saveUser(
            user(tenantA.getId(), "monitor-a-" + suffix), List.of(monitorRoleA.getId())
        );
        EnterpriseAdminService.UserView userB = adminService.saveUser(
            user(tenantB.getId(), "monitor-b-" + suffix), List.of(monitorRoleB.getId())
        );
        adminService.saveUser(
            user(tenantA.getId(), "no-monitor-" + suffix), List.of(unprivilegedRole.getId())
        );

        AgentTaskLatestEntity taskA = task(tenantA.getId(), userA.id(), "task-a-" + suffix, "tool-a-" + suffix);
        AgentTaskLatestEntity taskB = task(tenantB.getId(), userB.id(), "task-b-" + suffix, "tool-b-" + suffix);
        taskRepository.saveAll(List.of(taskA, taskB));
        eventStore.save(event(taskA, "TOOL_RESULT", "tool-a-" + suffix));
        eventStore.save(event(taskB, "TOOL_RESULT", "tool-b-" + suffix));
        savePlan(taskA, "plan-a-" + suffix);
        savePlan(taskB, "plan-b-" + suffix);
        auditLogRepository.save(toolAudit(tenantA.getId(), userA.id(), "tool-a-" + suffix));
        auditLogRepository.save(toolAudit(tenantB.getId(), userB.id(), "tool-b-" + suffix));

        String tokenA = login("monitor-a-" + suffix);
        String tokenWithoutPermission = login("no-monitor-" + suffix);

        mockMvc.perform(get("/api/v1/enterprise/menus").header("Authorization", bearer(tokenA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].children[0].id").value("tasks"));

        mockMvc.perform(get("/api/v1/agent/tasks/runtime")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId())
                .param("latestLimit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tenantId").value(tenantA.getId()))
            .andExpect(jsonPath("$.data.totalTasks").value(1))
            .andExpect(jsonPath("$.data.latestTasks[0].taskId").value(taskA.getTaskId()));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskA.getTaskId() + "/events")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].toolName").value("tool-a-" + suffix));
        mockMvc.perform(get("/api/v1/agent/tasks/" + taskB.getTaskId() + "/events")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("Task not found for tenant: " + taskB.getTaskId()));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskA.getTaskId() + "/plan-dag")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.planId").value("plan-a-" + suffix));
        mockMvc.perform(get("/api/v1/agent/tasks/" + taskA.getTaskId() + "/plan/versions")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/agent/tasks/runtime/effects")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tenantId").value(tenantA.getId()))
            .andExpect(jsonPath("$.data.totalTasks").value(1))
            .andExpect(jsonPath("$.data.feedbackTasks").value(1));
        mockMvc.perform(get("/api/v1/agent/tasks/runtime/experiences")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tenantId").value(tenantA.getId()));

        mockMvc.perform(get("/api/v1/agent/tasks/runtime/tool-audits")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId())
                .param("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tenantId").value(tenantA.getId()))
            .andExpect(jsonPath("$.data[0].toolName").value("tool-a-" + suffix));
        mockMvc.perform(get("/api/v1/agent/tasks/runtime/tool-governance")
                .header("Authorization", bearer(tokenA))
                .param("tenantId", tenantB.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tenantId").value(tenantA.getId()))
            .andExpect(jsonPath("$.data.tools").isArray());

        mockMvc.perform(get("/api/v1/agent/tasks/runtime")
                .header("Authorization", bearer(tokenWithoutPermission))
                .param("tenantId", tenantA.getId()))
            .andExpect(status().isForbidden());
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/enterprise/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "password", "monitor-password"
                ))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        return response.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private SysPermission permission(String code) {
        return permissionRepository.findByPermissionCode(code)
            .orElseThrow(() -> new AssertionError("missing permission seed: " + code));
    }

    private EnterpriseAdminService.RoleAuthorizationRequest authorization(SysPermission... permissions) {
        return new EnterpriseAdminService.RoleAuthorizationRequest(
            java.util.Arrays.stream(permissions).map(SysPermission::getId).toList(),
            List.of(),
            null,
            List.of()
        );
    }

    private SysTenant tenant(String key) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(key);
        tenant.setTenantName(key);
        tenant.setStatus("enabled");
        return tenant;
    }

    private SysRole role(String tenantId, String code) {
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setStatus("enabled");
        return role;
    }

    private SysUser user(String tenantId, String username) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash("monitor-password");
        user.setStatus("enabled");
        return user;
    }

    private AgentTaskLatestEntity task(String tenantId, String userId, String taskId, String agentId) {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId(taskId);
        task.setTenantId(tenantId);
        task.setUserId(userId);
        task.setAgentId(agentId);
        task.setSessionId("session-" + taskId);
        task.setStatus("SUCCESS");
        task.setQuestion("monitor " + taskId);
        task.setAnswerSummary("completed " + taskId);
        task.setFeedbackUseful(true);
        task.setFeedbackAdopted(true);
        task.setFeedbackResolved(true);
        task.setFeedbackReasonCategory("answer_correct");
        task.setFeedbackTime(Instant.now());
        return task;
    }

    private AgentEvent event(AgentTaskLatestEntity task, String type, String toolName) {
        return AgentEvent.builder()
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .sequence(1L)
            .type(type)
            .status("SUCCESS")
            .toolName(toolName)
            .payload("{\"rows\":1}")
            .build();
    }

    private void savePlan(AgentTaskLatestEntity task, String planId) {
        InterpretationPlanRecord record = new InterpretationPlanRecord(
            task.getTenantId(),
            task.getTaskId(),
            planId,
            1,
            "{}",
            "{\"nodes\":[],\"edges\":[]}",
            Map.of("planId", planId, "nodes", List.of(), "edges", List.of()),
            "COMPLETED",
            System.currentTimeMillis(),
            System.currentTimeMillis()
        );
        planStore.saveSnapshot(record);
        planStore.saveVersion(record);
    }

    private SysAuditLog toolAudit(String tenantId, String userId, String toolName) {
        SysAuditLog log = new SysAuditLog();
        log.setTenantId(tenantId);
        log.setActorId(userId);
        log.setActorName(userId);
        log.setModuleName("tool_runtime");
        log.setActionName("invoke");
        log.setResourceType("tool");
        log.setResourceId(toolName);
        log.setResult("success");
        log.setDetail("{\"toolName\":\"" + toolName + "\",\"outcome\":\"success\"}");
        return log;
    }
}
