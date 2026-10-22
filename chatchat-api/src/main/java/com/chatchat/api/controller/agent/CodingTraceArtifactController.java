package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Artifacts are read on demand under the run owner's identity, never copied into the event log. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
@RequestMapping(AppConstants.API_V1 + "/agent/runtime")
public class CodingTraceArtifactController {
    private final AgentRuntime runtime;
    private final McpPythonControlPlanePort python;
    private com.chatchat.api.runtime.coding.cline.CodingCodeArtifactStore codeArtifacts;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCodeArtifacts(com.chatchat.api.runtime.coding.cline.CodingCodeArtifactStore value) { codeArtifacts = value; }

    @GetMapping("/runs/{runId}/coding/{taskId}/{operationId}/{kind}")
    public ApiResponse<?> artifact(@PathVariable("runId") String runId, @PathVariable("taskId") String taskId,
            @PathVariable("operationId") String operationId, @PathVariable("kind") String kind, HttpServletRequest request) {
        var run = runtime.find(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Object tenant = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        Object user = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant == null || user == null || run.request() == null
                || !Objects.equals(tenant, run.request().getTenantId())
                || !Objects.equals(user, run.request().getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (codeArtifacts != null && "code".equals(kind) && operationId.matches("cline-[0-9a-f]{64}")) {
            var scope = new com.chatchat.common.kernel.KernelDataScope(tenant.toString(),user.toString(),null,null,runId,null,Map.of());
            var submitted = codeArtifacts.find(scope,taskId,operationId);
            if (submitted.isPresent()) {
                var file = submitted.get();
                String source = file.get("content").toString();
                String safe = CodingArtifactRedactor.redact(source);
                var response=new java.util.LinkedHashMap<String,Object>(Map.of("kind","code","content",safe,"sha256",file.get("sha256"),
                    "sourceBytes",file.get("sourceBytes"),"redacted",!source.equals(safe),"truncated",false,"version",operationId,"status","SUBMITTED_TO_RUNTIME"));
                response.put("fileName",file.get("fileName"));response.put("script",file.getOrDefault("script",Map.of()));
                return ApiResponse.success(response);
            }
        }
        boolean referenced = runtime.observations(runId).stream().map(item -> item.metadata()).anyMatch(meta ->
            "CODING_TRACE".equals(meta.get("eventKind")) && taskId.equals(meta.get("taskId"))
                && operationId.equals(meta.get("stepId")));
        if (!referenced || !operationId.matches("cline-[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String file = switch (kind) {
            case "code" -> com.chatchat.common.runtime.coding.CodingScript.LEGACY_FILE;
            case "stdout" -> "stdout.txt";
            case "stderr" -> "stderr.txt";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        byte[] bytes = python.artifact(tenant.toString(), user.toString(), runId, taskId, operationId,
            "code".equals(kind) ? "code" : "logs", file);
        int limit = "code".equals(kind) ? 256_000 : 8_192;
        String content = new String(bytes, StandardCharsets.UTF_8);
        String safeContent = CodingArtifactRedactor.redact(content);
        byte[] presentation = safeContent.getBytes(StandardCharsets.UTF_8);
        // These are protected artifacts. Do not feed their contents back into the Runtime model context.
        return ApiResponse.success(Map.of("kind", kind, "content", new String(presentation,0,Math.min(presentation.length,limit),StandardCharsets.UTF_8),
            "redacted", !content.equals(safeContent), "truncated", presentation.length > limit, "totalBytes", bytes.length));
    }
}
