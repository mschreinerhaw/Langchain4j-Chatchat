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

    @GetMapping("/runs/{runId}/coding/{taskId}/{operationId}/{kind}")
    public ApiResponse<?> artifact(@PathVariable String runId, @PathVariable String taskId,
            @PathVariable String operationId, @PathVariable String kind, HttpServletRequest request) {
        var run = runtime.find(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Object tenant = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        Object user = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant == null || user == null || run.request() == null
                || !Objects.equals(tenant, run.request().getTenantId())
                || !Objects.equals(user, run.request().getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean referenced = runtime.observations(runId).stream().map(item -> item.metadata()).anyMatch(meta ->
            "CODING_TRACE".equals(meta.get("eventKind")) && taskId.equals(meta.get("taskId"))
                && operationId.equals(meta.get("stepId")));
        if (!referenced || !operationId.matches("cline-[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String file = switch (kind) {
            case "code" -> "main.py";
            case "stdout" -> "stdout.txt";
            case "stderr" -> "stderr.txt";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        byte[] bytes = python.artifact(tenant.toString(), user.toString(), runId, taskId, operationId,
            "code".equals(kind) ? "code" : "logs", file);
        int limit = "code".equals(kind) ? 256_000 : 8_192;
        String content = new String(bytes, 0, Math.min(bytes.length, limit), StandardCharsets.UTF_8);
        // These are protected artifacts. Do not feed their contents back into the Runtime model context.
        return ApiResponse.success(Map.of("kind", kind, "content", content,
            "truncated", bytes.length > limit, "totalBytes", bytes.length));
    }
}
