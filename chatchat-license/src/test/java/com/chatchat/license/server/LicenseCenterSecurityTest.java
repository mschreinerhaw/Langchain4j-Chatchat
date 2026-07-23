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

@SpringBootTest(properties = "chatchat.license-center.password=test-only-password")
@AutoConfigureMockMvc
class LicenseCenterSecurityTest {

    @Autowired
    MockMvc mockMvc;

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
}
