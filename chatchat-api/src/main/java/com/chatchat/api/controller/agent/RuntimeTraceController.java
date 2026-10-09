package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.runtime.analysis.spi.AnalysisProgressPort;
import com.chatchat.common.runtime.evidence.ExecutionTraceRetrievalPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** The caller supplies a source run, never an owner or tenant. The same port is injectable into Workers. */
@RestController
@RequestMapping("/api/v1/agent/analysis/runtime")
public class RuntimeTraceController {
    private final ExecutionTraceRetrievalPort traces;
    private final AnalysisProgressPort progress;
    public RuntimeTraceController(ExecutionTraceRetrievalPort traces, AnalysisProgressPort progress) {
        this.traces = traces; this.progress = progress;
    }
    @GetMapping("/runs/{runId}")
    public ApiResponse<AnalysisProgressPort.State> state(@PathVariable("runId") String runId, HttpServletRequest request) {
        return ApiResponse.success(progress.state(scope(request, runId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found")));
    }
    @PostMapping("/traces/search")
    public ApiResponse<List<ExecutionTraceRetrievalPort.Summary>> search(@RequestBody Search body, HttpServletRequest request) {
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bounded query is required");
        return ApiResponse.success(traces.search(scope(request, body.runId()), body.query(), body.limit()));
    }
    @GetMapping("/traces/{evidenceId}")
    public ApiResponse<ExecutionTraceRetrievalPort.Detail> get(@PathVariable("evidenceId") String evidenceId,
            @RequestParam("runId") String runId, HttpServletRequest request) {
        if (evidenceId == null || evidenceId.length() > 256)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found");
        return ApiResponse.success(traces.get(scope(request, runId), evidenceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found")));
    }
    private KernelDataScope scope(HttpServletRequest request, String run) {
        String tenant = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String user = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenant == null || user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated owner required");
        if (run == null || run.isBlank() || run.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bounded source run is required");
        return new KernelDataScope(tenant, user, null, null, run, null, Map.of());
    }
    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
    public record Search(String runId, String query, int limit) {}
}
