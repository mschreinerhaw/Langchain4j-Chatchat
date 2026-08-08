package com.chatchat.license.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "chatchat.license-center.password=test-only-password",
    "spring.datasource.url=jdbc:h2:mem:license_security_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.password=Test-H2_Audit#2026!Secure"
})
@AutoConfigureMockMvc
class LicenseCenterSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void customLoginPageIsPubliclyAvailable() throws Exception {
        mockMvc.perform(get("/login.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("登录授权中心")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("LiveMCP")));
    }

    @Test
    void anonymousUserCannotOpenInternalLicenseCenter() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "LICENSE_ADMIN")
    void authenticatedLicenseAdminCanOpenInternalPage() throws Exception {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("License Center")));
    }

    @Test
    @WithMockUser(roles = "LICENSE_ADMIN")
    void authenticatedLicenseAdminCanReadAuditRecords() throws Exception {
        mockMvc.perform(get("/api/licenses/audits"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"content\":[]")));
    }
}
