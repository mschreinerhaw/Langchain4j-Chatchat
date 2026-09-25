package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Read-only, owner-scoped access to a Judge-accepted evidence bundle. */
@RestController
@RequestMapping("/api/v1/agent/analysis/evidence")
@Tag(name = "Agent Runtime OS Analysis")
public class AgentEvidenceArchiveController {
    private final AnalysisEvidenceArchivePort archive;
    private final ObjectMapper mapper;

    public AgentEvidenceArchiveController(AnalysisEvidenceArchivePort archive, ObjectMapper mapper) {
        this.archive = archive;
        this.mapper = mapper;
    }

    @GetMapping("/{archiveId}")
    @Operation(summary = "Read a verified evidence archive owned by the authenticated tenant and user")
    public ApiResponse<ArchiveView> get(@PathVariable String archiveId, HttpServletRequest request) {
        String tenant = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String user = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant == null || user == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (archiveId == null || !archiveId.matches("[0-9a-fA-F-]{36}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence archive not found");
        var stored = archive.read(tenant, user, archiveId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence archive not found"));
        try {
            var reference = stored.reference();
            JsonNode bundle = mapper.readTree(stored.bundleJson());
            return ApiResponse.success(new ArchiveView(reference.archiveId(), reference.sha256(),
                reference.byteLength(), bundle));
        } catch (JsonProcessingException invalid) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Archived evidence cannot be decoded", invalid);
        }
    }

    @GetMapping
    @Operation(summary = "List evidence archives for a run owned by the authenticated tenant and user")
    public ApiResponse<List<AnalysisEvidenceArchivePort.Reference>> list(@RequestParam String runId,
                                                                           HttpServletRequest request) {
        String tenant = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String user = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant == null || user == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (runId == null || runId.isBlank() || runId.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bounded runId is required");
        return ApiResponse.success(archive.listByRun(tenant, user, runId));
    }

    private String attribute(HttpServletRequest request, String key) {
        Object value = request.getAttribute(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public record ArchiveView(String archiveId, String sha256, long byteLength, JsonNode bundle) { }
}
