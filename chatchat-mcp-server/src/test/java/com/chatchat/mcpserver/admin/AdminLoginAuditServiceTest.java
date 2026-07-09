package com.chatchat.mcpserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminLoginAuditServiceTest {

    private final AdminLoginAuditLogRepository repository = mock(AdminLoginAuditLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdminLoginAuditService service = new AdminLoginAuditService(repository, objectMapper);

    @Test
    void recordsSuccessfulLoginMetadata() throws Exception {
        service.recordSuccess("admin", request());

        AdminLoginAuditLog log = savedLog();
        assertThat(log.getUsername()).isEqualTo("admin");
        assertThat(log.getActionName()).isEqualTo("admin-login");
        assertThat(log.getResult()).isEqualTo("success");
        assertThat(log.getIpAddress()).isEqualTo("10.2.3.4");
        assertThat(log.getMacAddress()).isEqualTo("AA-BB-CC-DD-EE-FF");
        assertThat(log.getUserAgent()).isEqualTo("JUnit-MCP");
        JsonNode detail = objectMapper.readTree(log.getDetail());
        assertThat(detail.path("ipAddress").asText()).isEqualTo("10.2.3.4");
        assertThat(detail.path("macAddress").asText()).isEqualTo("AA-BB-CC-DD-EE-FF");
    }

    @Test
    void recordsFailedLoginReason() throws Exception {
        service.recordFailure("admin", "Invalid username or password", request());

        AdminLoginAuditLog log = savedLog();
        assertThat(log.getResult()).isEqualTo("failure");
        JsonNode detail = objectMapper.readTree(log.getDetail());
        assertThat(detail.path("reason").asText()).isEqualTo("Invalid username or password");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.2.3.4, 10.2.3.5");
        request.addHeader("X-Client-Mac", "AA-BB-CC-DD-EE-FF");
        request.addHeader("User-Agent", "JUnit-MCP");
        return request;
    }

    private AdminLoginAuditLog savedLog() {
        ArgumentCaptor<AdminLoginAuditLog> captor = ArgumentCaptor.forClass(AdminLoginAuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
