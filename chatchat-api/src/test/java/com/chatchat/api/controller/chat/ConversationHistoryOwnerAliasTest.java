package com.chatchat.api.controller.chat;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConversationHistoryOwnerAliasTest {
    @Test void studioHistoryUsesAuthenticatedTenantAndOwnerAliases() {
        var conversations=mock(ConversationService.class);
        var controller=new ConversationController(conversations,mock(WorkspaceOriginService.class),
            new ObjectAccessService(mock(JdbcTemplate.class),mock(EnterpriseAdminService.class)));
        controller.studioHistory("stable-id","PYTHON_STUDIO","code",2,10,request());
        verify(conversations).listStudioConversationSummaryPage("tenant",Set.of("stable-id","legacy-name"),"PYTHON_STUDIO","code",2,10);
        assertThrows(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class,
            ()->controller.studioHistory("another-owner","SQL_STUDIO","",1,30,request()));
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "stable-id");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME, "legacy-name");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        return request;
    }

    @Test
    void sidebarAndSearchMergeServerVerifiedAliasesWithinTheCurrentTenant() {
        var conversations = mock(ConversationService.class);
        var access = new ObjectAccessService(mock(JdbcTemplate.class), mock(EnterpriseAdminService.class));
        var controller = new ConversationController(conversations, mock(WorkspaceOriginService.class), access);
        var owners = Set.of("stable-id", "legacy-name");
        when(conversations.listOwnerConversationSummaryPage("tenant", owners, "", 1, 30))
            .thenReturn(new ConversationService.ConversationPage(List.of(), 0, 1, 30, 0));
        when(conversations.listOwnerConversationSummaryPage("tenant", owners, "analysis", 2, 10))
            .thenReturn(new ConversationService.ConversationPage(List.of(), 0, 2, 10, 0));
        controller.listUserConversationSummaries("legacy-name", "forged-tenant", 0, 30, request());
        controller.pageUserConversationSummaries("stable-id", "forged-tenant", "analysis", 2, 10, request());
        verify(conversations).listOwnerConversationSummaryPage("tenant", owners, "", 1, 30);
        verify(conversations).listOwnerConversationSummaryPage("tenant", owners, "analysis", 2, 10);
    }

    @Test
    void refusesAnotherPrincipalAndAliasThatCollidesWithAnExistingStableId() {
        var conversations = mock(ConversationService.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select id from sys_user where id = ?", "legacy-name"))
            .thenReturn(List.of(Map.of("id", "legacy-name")));
        var controller = new ConversationController(conversations, mock(WorkspaceOriginService.class),
            new ObjectAccessService(jdbc, mock(EnterpriseAdminService.class)));
        assertThrows(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class,
            () -> controller.listUserConversationSummaries("legacy-name", null, 0, 30, request()));
        assertThrows(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class,
            () -> controller.pageUserConversationSummaries("other-user", null, "", 1, 10, request()));
        verifyNoInteractions(conversations);
    }
}
