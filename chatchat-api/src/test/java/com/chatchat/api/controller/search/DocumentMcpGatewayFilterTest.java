package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMcpGatewayFilterTest {
    @Test
    void sendsUploadFileToMcpOverGrpc() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        DocumentGrpcTransferClient rpc = mock(DocumentGrpcTransferClient.class);
        when(rpc.transfer(any(DocumentTransferStart.class), any(InputStream.class))).thenAnswer(call -> {
            DocumentTransferStart start = call.getArgument(0);
            InputStream stream = call.getArgument(1);
            received.set(start.getOperation() + "|" + start.getTenantId() + "|" + start.getUserId()
                + "|" + start.getRoles() + "|" + start.getFileName() + "|"
                + new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return SearchDocument.builder().docId("new-1").build();
        });
        EnterpriseAdminService adminService = mock(EnterpriseAdminService.class);
        when(adminService.authorizationRoleKeys("user-1"))
            .thenReturn(java.util.List.of("role-super", "SUPER_ADMIN", "超级管理员"));
        DocumentMcpGatewayFilter filter = new DocumentMcpGatewayFilter("http://127.0.0.1:8090", "secret");
        ReflectionTestUtils.setField(filter, "documentGrpcTransferClient", rpc);
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(filter, "enterpriseAdminService", adminService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/search/documents/upload");
        request.setContentType("multipart/form-data");
        request.addPart(new MockPart("file", "guide.txt", "document bytes".getBytes(StandardCharsets.UTF_8)));
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW, new EnterpriseAdminService.UserView(
            "user-1", "tenant-1", null, null, null, "admin", null, null, null,
            "enabled", null, java.util.List.of("role-super"), java.util.List.of(), null, null, true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> fail("local search must not run"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("new-1");
        assertThat(received.get()).isEqualTo(
            "UPLOAD|tenant-1|user-1|role-super,SUPER_ADMIN,超级管理员|guide.txt|document bytes");
    }
}
