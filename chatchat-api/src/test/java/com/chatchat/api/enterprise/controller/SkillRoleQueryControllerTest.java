package com.chatchat.api.enterprise.controller;

import com.chatchat.api.enterprise.service.SkillRoleQueryService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillRoleQueryControllerTest {
    @Test
    void rejectsOtherTenantAndPassesBoundedQueryParameters() throws Exception {
        var query = mock(SkillRoleQueryService.class);
        var admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("u")).thenReturn(new EnterpriseAdminService.UserView(
            "u", "t", 100001L, null, "u", "User", null, null, "enabled", null, List.of(), List.of(), null, null));
        var mvc = standaloneSetup(new SkillRoleQueryController(query, admin)).build();
        String path = "/api/v1/enterprise/resource-grants/skill-role-query";
        mvc.perform(get(path).param("tenantId", "t")).andExpect(status().isForbidden());
        mvc.perform(get(path).requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "u").param("tenantId", "other"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(query);
        mvc.perform(get(path).requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "u")
            .param("tenantId", "t").param("view", "skills").param("query", "固收").param("page", "2"))
            .andExpect(status().isOk());
        verify(query).query("t", "skills", "固收", "", "", "", 2, 20);
    }
}
