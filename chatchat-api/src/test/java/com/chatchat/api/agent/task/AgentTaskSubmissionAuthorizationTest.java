package com.chatchat.api.agent.task;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentTaskSubmissionAuthorizationTest {
    @Mock AgentTaskService tasks;
    @Mock EnterpriseAdminService identities;
    @Mock SkillCatalogService skills;
    @InjectMocks AgentTaskController controller;

    @Test void rejectsMissingAgentBeforeTheRuntimeCanFallBackToAnUnassignedDefault() {
        var body = new AgentTaskSubmitRequest();
        assertThat(controller.submit(body, request()).getCode()).isEqualTo(400);
        verifyNoInteractions(tasks);
    }
    @Test void rejectsRecalledOrUnknownAgentsBeforeSubmission() {
        var body = body(); when(skills.isPublished("allowed")).thenReturn(false);
        assertThat(controller.submit(body, request()).getCode()).isEqualTo(400);
        verifyNoInteractions(tasks);
    }
    @Test void rejectsPublishedAgentsOutsideCurrentRoleGrants() {
        var body = body(); when(skills.isPublished("allowed")).thenReturn(true);
        when(identities.canAccessAgent("caller", "allowed")).thenReturn(false);
        assertThat(controller.submit(body, request()).getCode()).isEqualTo(400);
        verifyNoInteractions(tasks);
    }
    @Test void authorizedSubmissionUsesAuthenticatedIdentityInsteadOfBodyIdentity() {
        var body = body(); when(skills.isPublished("allowed")).thenReturn(true);
        when(identities.canAccessAgent("caller", "allowed")).thenReturn(true);
        assertThat(controller.submit(body, request()).getCode()).isEqualTo(200);
        verify(tasks).submit(argThat(task -> "tenant".equals(task.getTenantId()) && "caller".equals(task.getUserId())));
    }
    private AgentTaskSubmitRequest body() {
        var body = new AgentTaskSubmitRequest(); body.setAgentId("allowed"); body.setQuery("hello");
        body.setTenantId("forged"); body.setUserId("other-user"); return body;
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "caller");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant"); return request;
    }
}
