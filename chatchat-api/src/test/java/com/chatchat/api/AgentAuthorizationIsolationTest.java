package com.chatchat.api;

import com.chatchat.chat.task.ScheduledTaskRunEntity;
import com.chatchat.chat.task.ScheduledTaskRunRepository;
import com.chatchat.chat.task.AgentScheduledTaskService;
import com.chatchat.chat.task.ScheduledAgentTaskRequest;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillConfigEntity;
import com.chatchat.chat.skills.SkillConfigRepository;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "chatchat.test.context=agent-authorization-isolation")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentAuthorizationIsolationTest {

    @Autowired
    private EnterpriseAdminService adminService;

    @MockBean
    private ChatModel chatModel;

    @Autowired
    private ScheduledTaskRunRepository scheduledTaskRunRepository;

    @Autowired
    private SkillConfigRepository skillConfigRepository;

    @Autowired
    private SkillCatalogService skillCatalogService;

    @Autowired
    private AgentScheduledTaskService agentScheduledTaskService;

    @Test
    void adminSeesAllAgentsWhileTenantUsersOnlySeeOwnRoleBindings() {
        SysTenant tenantA = adminService.saveTenant(tenant("tenant-agent-a"));
        SysTenant tenantB = adminService.saveTenant(tenant("tenant-agent-b"));
        SysRole roleA = adminService.saveRole(role(tenantA.getId(), "AGENT_USER_A"));
        SysRole tenantBSuperAdmin = adminService.saveRole(role(tenantB.getId(), "SUPER_ADMIN"));

        EnterpriseAdminService.UserView userA = adminService.saveUser(user(tenantA.getId(), "agent-user-a"), List.of(roleA.getId()));
        EnterpriseAdminService.UserView userB = adminService.saveUser(user(tenantB.getId(), "agent-user-b"), List.of(roleA.getId()));
        EnterpriseAdminService.UserView userBSuper = adminService.saveUser(
            user(tenantB.getId(), "tenant-super-admin-b"), List.of(tenantBSuperAdmin.getId())
        );
        adminService.saveRoleAuthorization(roleA.getId(), new EnterpriseAdminService.RoleAuthorizationRequest(
            List.of(), List.of(), null, List.of("agent-alpha")
        ));

        assertThat(adminService.canAccessAgent("admin", "any-agent")).isTrue();
        assertThat(adminService.canAccessAgent(userA.id(), "agent-alpha")).isTrue();
        assertThat(adminService.canAccessAgent(userA.id(), "agent-beta")).isFalse();
        assertThat(adminService.canAccessAgent(userB.id(), "agent-alpha")).isFalse();
        assertThat(adminService.canAccessAgent(userBSuper.id(), "agent-alpha")).isFalse();
    }

    @Test
    void notificationHistoryIsTenantScopedSearchableAndPagedByTen() {
        String scheduleId = UUID.randomUUID().toString();
        for (int index = 0; index < 11; index++) {
            scheduledTaskRunRepository.save(notificationRun("tenant-history-a", scheduleId, "EMAIL", index));
        }
        scheduledTaskRunRepository.save(notificationRun("tenant-history-a", scheduleId, "SMS", 20));
        scheduledTaskRunRepository.save(notificationRun("tenant-history-b", scheduleId, "EMAIL", 30));

        var firstPage = scheduledTaskRunRepository.searchNotificationHistory(
            "tenant-history-a", scheduleId, "email", PageRequest.of(0, 10)
        );
        var secondPage = scheduledTaskRunRepository.searchNotificationHistory(
            "tenant-history-a", scheduleId, "email", PageRequest.of(1, 10)
        );

        assertThat(firstPage.getTotalElements()).isEqualTo(11);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(secondPage.getContent()).hasSize(1);
    }

    @Test
    void onlyExactlyPublishedAgentsPassSchedulePublicationValidation() {
        SkillConfigEntity draft = skill("schedule-draft-agent", "draft");
        SkillConfigEntity published = skill("schedule-published-agent", "published");
        skillConfigRepository.saveAll(List.of(draft, published));

        assertThat(skillCatalogService.isPublished(draft.getId())).isFalse();
        assertThat(skillCatalogService.isPublished(published.getId())).isTrue();
        assertThat(skillCatalogService.isPublished("missing-agent")).isFalse();

        ScheduledAgentTaskRequest request = new ScheduledAgentTaskRequest();
        request.setTenantId("tenant-schedule-publication");
        request.setUserId("schedule-user");
        request.setAgentId(draft.getId());
        request.setQuestion("should not be scheduled");
        request.setTriggerType("ONCE");
        assertThatThrownBy(() -> agentScheduledTaskService.create(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Agent未发布");
    }

    private SkillConfigEntity skill(String id, String marketStatus) {
        SkillConfigEntity skill = new SkillConfigEntity();
        skill.setId(id);
        skill.setLabel(id);
        skill.setMarketStatus(marketStatus);
        return skill;
    }

    private ScheduledTaskRunEntity notificationRun(String tenantId, String scheduleId, String channelType, int offset) {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setScheduledTaskId(scheduleId);
        run.setTenantId(tenantId);
        run.setUserId("history-user");
        run.setAgentId("history-agent");
        run.setTaskId("agent-task-" + offset);
        run.setStatus("SUCCESS");
        run.setQuestion("history question");
        run.setFireTime(Instant.now().minusSeconds(offset));
        run.setNotificationChannelType(channelType);
        run.setNotificationChannelName(channelType + " notification");
        run.setNotificationReceiver("receiver-" + offset);
        run.setNotificationStatus("SUCCESS");
        run.setNotificationSentAt(Instant.now().minusSeconds(offset));
        return run;
    }

    private SysTenant tenant(String code) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(code);
        tenant.setTenantName(code);
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
        user.setPasswordHash("test-password");
        user.setStatus("enabled");
        return user;
    }
}
