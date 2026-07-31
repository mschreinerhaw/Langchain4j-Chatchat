package com.chatchat.api;

import com.chatchat.agents.runtime.ToolRuntimePolicy;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.api.runtime.EnterpriseToolRuntimePolicyProvider;
import com.chatchat.chat.skills.SkillConfigEntity;
import com.chatchat.chat.skills.SkillConfigRepository;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.SysPermission;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.SysPermissionRepository;
import com.chatchat.enterprise.repository.SysUserRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "chatchat.test.context=api-user-role-schedule-mcp-authorization",
    "chatchat.api.auth.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiUserRoleScheduleMcpAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnterpriseAdminService adminService;

    @Autowired
    private SysPermissionRepository permissionRepository;

    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private SkillConfigRepository skillConfigRepository;

    @Autowired
    private McpToolAssetRepository toolAssetRepository;

    @Autowired
    private McpToolPermissionRepository toolPermissionRepository;

    @Autowired
    private EnterpriseToolRuntimePolicyProvider toolPolicyProvider;

    @MockBean
    private ChatModel chatModel;

    @Test
    void loginMenuScheduleAndMcpToolAuthorizationStayTenantAndRoleScoped() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SysTenant tenantA = adminService.saveTenant(tenant("joint-a-" + suffix));
        SysTenant tenantB = adminService.saveTenant(tenant("joint-b-" + suffix));
        SysRole schedulerRole = adminService.saveRole(role(tenantA.getId(), "SCHEDULER_" + suffix));
        SysRole viewerRole = adminService.saveRole(role(tenantB.getId(), "VIEWER_" + suffix));
        SysPermission scheduleMenu = permission("platform:schedules");
        SysPermission scheduleManage = permission("platform:schedules:manage");
        SysPermission workspaceChat = permission("workspace:chat");

        EnterpriseAdminService.UserView scheduler = adminService.saveUser(
            user(tenantA.getId(), "scheduler-" + suffix), List.of(schedulerRole.getId())
        );
        EnterpriseAdminService.UserView viewer = adminService.saveUser(
            user(tenantB.getId(), "viewer-" + suffix), List.of(viewerRole.getId())
        );
        String storedPassword = userRepository.findById(scheduler.id()).orElseThrow().getPasswordHash();
        assertThat(storedPassword).isNotEqualTo("joint-password");
        assertThat(PasswordHashCodec.matches("joint-password", storedPassword)).isTrue();
        String agentId = "joint-agent-" + suffix;
        adminService.saveRoleAuthorization(schedulerRole.getId(),
            new EnterpriseAdminService.RoleAuthorizationRequest(
                List.of(scheduleMenu.getId(), scheduleManage.getId()),
                List.of(),
                null,
                List.of(agentId)
            ));
        adminService.saveRoleAuthorization(viewerRole.getId(),
            new EnterpriseAdminService.RoleAuthorizationRequest(
                List.of(workspaceChat.getId()), List.of(), null, List.of()
            ));
        skillConfigRepository.save(publishedAgent(agentId));

        McpToolAsset tool = toolAssetRepository.save(tool("joint_sql_query_" + suffix));
        toolPermissionRepository.save(toolPermission(tenantA.getId(), schedulerRole.getId(), tool));

        String schedulerToken = login("scheduler-" + suffix, "joint-password");
        String viewerToken = login("viewer-" + suffix, "joint-password");

        mockMvc.perform(get("/api/v1/enterprise/menus").header("Authorization", bearer(schedulerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("platform"))
            .andExpect(jsonPath("$.data[0].children[0].id").value("schedules"))
            .andExpect(jsonPath("$.data[0].children.length()").value(1));
        mockMvc.perform(get("/api/v1/enterprise/menus").header("Authorization", bearer(viewerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("workspace"))
            .andExpect(jsonPath("$.data[0].children[0].id").value("chat"));

        mockMvc.perform(get("/api/v1/enterprise/roles").header("Authorization", bearer(schedulerToken)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/agent/tasks/runtime/schedules")
                .header("Authorization", bearer(schedulerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "tenantId", tenantB.getId(),
                    "userId", viewer.id(),
                    "agentId", agentId,
                    "name", "joint schedule",
                    "enabled", false,
                    "triggerType", "ONCE",
                    "question", "查询最新交易日证券与指数涨跌"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tenantId").value(tenantA.getId()))
            .andExpect(jsonPath("$.data.userId").value(scheduler.id()));
        mockMvc.perform(get("/api/v1/agent/tasks/runtime/schedules")
                .header("Authorization", bearer(viewerToken)))
            .andExpect(status().isForbidden());

        ToolRuntimePolicy allowed = toolPolicyProvider.resolve(ToolRuntimeRequest.builder()
            .tenantId(tenantA.getId())
            .userId(scheduler.id())
            .toolName(tool.getLocalToolName())
            .build(), null);
        ToolRuntimePolicy forgedTenant = toolPolicyProvider.resolve(ToolRuntimeRequest.builder()
            .tenantId(tenantA.getId())
            .userId(viewer.id())
            .toolName(tool.getLocalToolName())
            .attributes(Map.of("roles", List.of("SUPER_ADMIN"), "roleIds", List.of(schedulerRole.getId())))
            .build(), null);

        assertThat(allowed.allowed()).isTrue();
        assertThat(forgedTenant.allowed()).isFalse();
        assertThat(forgedTenant.reason()).contains("does not belong");
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/enterprise/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
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
        user.setPasswordHash("joint-password");
        user.setStatus("enabled");
        return user;
    }

    private SkillConfigEntity publishedAgent(String id) {
        SkillConfigEntity skill = new SkillConfigEntity();
        skill.setId(id);
        skill.setLabel(id);
        skill.setDescription("joint authorization test agent");
        skill.setMarketStatus("published");
        return skill;
    }

    private McpToolAsset tool(String localName) {
        McpToolAsset tool = new McpToolAsset();
        tool.setLocalToolName(localName);
        tool.setServiceId("joint-service");
        tool.setServiceName("joint-service");
        tool.setRemoteToolName(localName);
        tool.setEnabled(true);
        tool.setStatus("online");
        return tool;
    }

    private McpToolPermission toolPermission(String tenantId, String roleId, McpToolAsset tool) {
        McpToolPermission permission = new McpToolPermission();
        permission.setTenantId(tenantId);
        permission.setTargetType("ROLE");
        permission.setTargetId(roleId);
        permission.setToolId(tool.getId());
        permission.setLocalToolName(tool.getLocalToolName());
        permission.setEffect("allow");
        permission.setEnabled(true);
        return permission;
    }
}
