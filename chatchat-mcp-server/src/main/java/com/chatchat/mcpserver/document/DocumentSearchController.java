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

    @PostMapping({"", "/document-search"})
    public ApiResponse<DocumentSearchResult> search(@RequestBody DocumentSearchRequest request) {
        try {
            return ApiResponse.success(evidenceService.search(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.badRequest(exception.getMessage());
        }
    }

    @PostMapping("/document-search/expand")
    public ApiResponse<DocumentSearchExpandResult> expand(@RequestBody DocumentSearchExpandRequest request) {
        try {
            return ApiResponse.success(evidenceService.expand(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.badRequest(exception.getMessage());
        }
    }
}
