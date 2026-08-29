package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.AgentApiToken;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.AgentApiTokenRepository;
import com.chatchat.enterprise.repository.security.RoleAgentBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AgentApiTokenServiceTest {

    @Mock private AgentApiTokenRepository tokenRepository;
    @Mock private SysUserRepository userRepository;
    @Mock private SysUserRoleRepository userRoleRepository;
    @Mock private SysRoleRepository roleRepository;
    @Mock private RoleAgentBindingRepository roleAgentBindingRepository;
    @Mock private SysTenantRepository tenantRepository;
    @Mock private SysAuditLogRepository auditLogRepository;

    private AgentApiTokenService service;
    private SysUser admin;

    @BeforeEach
    void setUp() {
        service = new AgentApiTokenService(tokenRepository, userRepository, userRoleRepository, roleRepository,
            roleAgentBindingRepository, tenantRepository, auditLogRepository, new ObjectMapper());
        admin = new SysUser();
        admin.setId("admin-id");
        admin.setTenantId("platform-id");
        admin.setUsername("admin");
        admin.setDisplayName("Administrator");
        admin.setStatus("enabled");
        SysTenant tenant = new SysTenant();
        tenant.setId("platform-id");
        tenant.setTenantNo(PLATFORM_TENANT_NO);
        lenient().when(userRepository.findById("admin-id")).thenReturn(Optional.of(admin));
        lenient().when(tenantRepository.findById("platform-id")).thenReturn(Optional.of(tenant));
        lenient().when(tokenRepository.save(any())).thenAnswer(invocation -> {
            AgentApiToken token = invocation.getArgument(0);
            token.onCreate();
            return token;
        });
    }

    @Test
    void createsPermanentHashedTokenAndReturnsSecretOnlyOnce() {
        AgentApiTokenService.IssuedToken issued = service.create("admin-id",
            new AgentApiTokenService.CreateTokenRequest("admin-id", "console", true, null));

        ArgumentCaptor<AgentApiToken> record = ArgumentCaptor.forClass(AgentApiToken.class);
        verify(tokenRepository).save(record.capture());
        assertThat(issued.secret()).startsWith(AgentApiTokenService.TOKEN_PREFIX);
        assertThat(record.getValue().getTokenHash()).hasSize(64).doesNotContain(issued.secret());
        assertThat(record.getValue().getExpiresAt()).isNull();
        assertThat(issued.token().permanent()).isTrue();
    }

    @Test
    void authenticatesCurrentEnabledUserAndRecordsUsage() {
        AgentApiTokenService.IssuedToken issued = service.create("admin-id",
            new AgentApiTokenService.CreateTokenRequest("admin-id", "automation", false, 3600L));
        ArgumentCaptor<AgentApiToken> record = ArgumentCaptor.forClass(AgentApiToken.class);
        verify(tokenRepository).save(record.capture());
        when(tokenRepository.findByTokenHash(record.getValue().getTokenHash()))
            .thenReturn(Optional.of(record.getValue()));

        AgentApiTokenService.Authentication authentication = service.authenticate(
            issued.secret(), "127.0.0.1", "/api/v1/published-agents/demo/questions");

        assertThat(authentication.userId()).isEqualTo("admin-id");
        verify(tokenRepository).recordUse(any(), any(Instant.class), any(), any());
    }

    @Test
    void rejectsExpiredTokenWithoutRecordingUsage() {
        AgentApiToken expired = new AgentApiToken();
        expired.setId("expired-id");
        expired.setTokenHash("ignored");
        expired.setTokenPreview("ccat_expired");
        expired.setTenantId("platform-id");
        expired.setStatus("active");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThat(service.authenticate("ccat_expired-secret", "127.0.0.1", "/status")).isNull();
    }
}
