package com.chatchat.api.controller.search;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Searches the API-owned legacy corpus for an authenticated MCP caller. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/document-search")
public class InternalDocumentSearchController {
    private final DocumentSearchEvidenceService evidenceService;
    private final EnterpriseAdminService adminService;

    @PostMapping
    public ApiResponse<DocumentSearchResult> search(@RequestBody DocumentSearchRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()
            || request.tenantId() == null || request.tenantId().isBlank()) {
            return ApiResponse.badRequest("tenantId and userId are required");
        }
        EnterpriseAdminService.UserView user;
        try {
            user = adminService.getUserView(request.userId());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(403, "document search principal is not authorized");
        }
        if (user == null || !"enabled".equalsIgnoreCase(user.status())
            || !Objects.equals(user.tenantId(), request.tenantId())) {
            return ApiResponse.error(403, "document search principal is not authorized");
        }
        try {
            DocumentSearchRequest authorized = new DocumentSearchRequest(
                request.query(), request.topK(), request.fileIds(), request.selectedFileIds(),
                request.selectedDocumentIds(), request.documentVisibilityEnforced(), request.filters(),
                user.tenantId(), user.id(), adminService.authorizationRoleKeys(user.id()), request.debug());
            return ApiResponse.success(evidenceService.search(authorized));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }
}
