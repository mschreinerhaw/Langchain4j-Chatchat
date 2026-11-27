package com.chatchat.api.enterprise.controller;

import com.chatchat.api.enterprise.service.TokenQuotaLedger;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TokenQuotaControllerTest {
    @Test
    void bindsPoolPathAndHistoryPageAndRejectsAnotherTenant() throws Exception {
        var ledger = mock(TokenQuotaLedger.class);
        var users = mock(SysUserRepository.class);
        var audits = mock(SysAuditLogRepository.class);
        var user = new SysUser();
        user.setTenantId("tenant-a");
        when(users.findById("account")).thenReturn(Optional.of(user));
        when(ledger.configure("tenant-a", TokenQuotaLedger.PoolType.USER, "account", 100L, true))
            .thenReturn(new TokenQuotaLedger.Pool(100L, 1, 0, 0));
        when(audits.findAll(org.mockito.ArgumentMatchers.<Specification<SysAuditLog>>any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        var mvc = standaloneSetup(new TokenQuotaController(ledger, mock(WorkspaceService.class), users,
            mock(SysRoleRepository.class), audits)).build();
        String path = "/api/v1/permission-center/token-pools/USER/account";
        String caller = ApiAuthenticationFilter.CURRENT_USER_VIEW;

        mvc.perform(put(path).requestAttr(caller, admin("tenant-a")).accept(MediaType.APPLICATION_JSON)
                .contentType("application/json").content("{\"unlimited\":false,\"limit\":100,\"reset\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.period").value(1));
        verify(audits).saveAndFlush(any(SysAuditLog.class));
        mvc.perform(get(path + "/changes").param("page", "2").requestAttr(caller, admin("tenant-a"))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(path + "/changes").requestAttr(caller, admin("tenant-b")))
            .andExpect(status().isNotFound());
    }

    private UserView admin(String tenant) {
        return new UserView("admin", tenant, null, null, null, "admin", null, null, null, "enabled", null,
            List.of(), List.of(ReportExportController.MANAGE_PERMISSION), null, null, false);
    }
}
