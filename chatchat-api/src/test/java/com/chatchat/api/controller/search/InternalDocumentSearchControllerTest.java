package com.chatchat.api.controller.search;

import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalDocumentSearchControllerTest {
    @Test
    void rejectsUnknownUser() {
        EnterpriseAdminService users = mock(EnterpriseAdminService.class);
        DocumentSearchEvidenceService evidence = mock(DocumentSearchEvidenceService.class);
        when(users.getUserView("user-1")).thenThrow(new IllegalArgumentException("user not found"));

        assertThat(new InternalDocumentSearchController(evidence, users)
            .search(request("tenant-1")).getCode()).isEqualTo(403);
        verifyNoInteractions(evidence);
    }

    @Test
    void rejectsWrongTenantAndUsesDirectoryRolesInsteadOfCallerRoles() {
        EnterpriseAdminService users = mock(EnterpriseAdminService.class);
        DocumentSearchEvidenceService evidence = mock(DocumentSearchEvidenceService.class);
        when(users.getUserView("user-1")).thenReturn(new EnterpriseAdminService.UserView(
            "user-1", "tenant-1", null, null, null, "alice", null, null, null,
            "enabled", null, List.of("reader-role"), List.of(), null, null, false));
        InternalDocumentSearchController controller = new InternalDocumentSearchController(evidence, users);
        DocumentSearchRequest forged = request("tenant-2");
        assertThat(controller.search(forged).getCode()).isEqualTo(403);
        verifyNoInteractions(evidence);

        DocumentSearchResult result = mock(DocumentSearchResult.class);
        when(evidence.search(any())).thenReturn(result);
        assertThat(controller.search(request("tenant-1")).getCode()).isEqualTo(200);
        ArgumentCaptor<DocumentSearchRequest> captured = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(evidence).search(captured.capture());
        assertThat(captured.getValue().roles()).containsExactly("reader-role");
        assertThat(captured.getValue().userId()).isEqualTo("user-1");
    }

    private DocumentSearchRequest request(String tenantId) {
        return new DocumentSearchRequest("livedata 安装", 8, List.of(), null,
            tenantId, "user-1", List.of("admin-role"), false);
    }
}
