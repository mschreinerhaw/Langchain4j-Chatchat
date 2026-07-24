package com.chatchat.api.enterprise;

import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginAuditServiceTest {

    private final SysAuditLogRepository repository = mock(SysAuditLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LoginAuditService service = new LoginAuditService(repository, objectMapper);

    @Test
    void recordsSuccessfulLoginNetworkMetadata() throws Exception {
        MockHttpServletRequest request = request();
        EnterpriseAdminService.UserView user = new EnterpriseAdminService.UserView(
            "user-1",
            "tenant-1",
            100000L,
            "org-1",
            "admin",
            "Admin",
            "admin@example.com",
            "13800000000",
            "enabled",
            Instant.now(),
            List.of("role-admin"),
            List.of("workspace:chat"),
            Instant.now(),
            Instant.now()
        );
        EnterpriseAdminService.AuthResult result = new EnterpriseAdminService.AuthResult("token", user);

        service.recordSuccess("login", "admin", result, request);

        SysAuditLog log = savedLog();
        assertThat(log.getTenantId()).isEqualTo("tenant-1");
        assertThat(log.getActorId()).isEqualTo("user-1");
        assertThat(log.getActorName()).isEqualTo("Admin");
        assertThat(log.getModuleName()).isEqualTo("auth");
        assertThat(log.getActionName()).isEqualTo("login");
        assertThat(log.getResult()).isEqualTo("success");
        JsonNode detail = objectMapper.readTree(log.getDetail());
        assertThat(detail.path("ipAddress").asText()).isEqualTo("10.1.2.3");
        assertThat(detail.path("macAddress").asText()).isEqualTo("00-11-22-33-44-55");
        assertThat(detail.path("userAgent").asText()).isEqualTo("JUnit");
    }

    @Test
    void recordsFailedLoginAttempt() throws Exception {
        service.recordFailure("login", "missing", "user not found", request());

        SysAuditLog log = savedLog();
        assertThat(log.getActorName()).isEqualTo("missing");
        assertThat(log.getModuleName()).isEqualTo("auth");
        assertThat(log.getActionName()).isEqualTo("login");
        assertThat(log.getResult()).isEqualTo("failure");
        JsonNode detail = objectMapper.readTree(log.getDetail());
        assertThat(detail.path("attemptedUsername").asText()).isEqualTo("missing");
        assertThat(detail.path("reason").asText()).isEqualTo("user not found");
        assertThat(detail.path("ipAddress").asText()).isEqualTo("10.1.2.3");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.1.2.3, 10.1.2.4");
        request.addHeader("X-Client-Mac", "00-11-22-33-44-55");
        request.addHeader("User-Agent", "JUnit");
        return request;
    }

    private SysAuditLog savedLog() {
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
