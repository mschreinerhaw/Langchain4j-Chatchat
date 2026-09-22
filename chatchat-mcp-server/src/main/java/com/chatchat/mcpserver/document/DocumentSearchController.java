package com.chatchat.mcpserver.document;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandResult;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP-owned HTTP control surface for document evidence retrieval. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/search")
public class DocumentSearchController {

    private final DocumentSearchEvidenceService evidenceService;
    private final DocumentEvidenceAuthorizationFilter authorizationFilter;
    private final ApiDocumentEvidenceClient apiClient;

    @PostMapping({"", "/document-search"})
    public ApiResponse<DocumentSearchResult> search(@RequestBody DocumentSearchRequest request) {
        try {
            return ApiResponse.success(authorizationFilter.filter(request, evidenceService.search(request)));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.badRequest(exception.getMessage());
        } catch (IllegalStateException exception) {
            return ApiResponse.error(503, "Document authorization is unavailable");
        }
    }

    @PostMapping("/document-search/expand")
    public ApiResponse<DocumentSearchExpandResult> expand(@RequestBody DocumentSearchExpandRequest request) {
        try {
            if (request == null || request.docId() == null || request.docId().isBlank()) {
                return ApiResponse.badRequest("docId is required");
            }
            if (authorizationFilter.enabled() && !apiClient.allowedDocumentIds(
                request.tenantId(), request.userId(), java.util.Set.of(request.docId()))
                .contains(request.docId())) {
                return ApiResponse.error(403, "Document is not authorized");
            }
            return ApiResponse.success(evidenceService.expand(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.badRequest(exception.getMessage());
        } catch (IllegalStateException exception) {
            return ApiResponse.error(503, "Document authorization is unavailable");
        }
    }
}
