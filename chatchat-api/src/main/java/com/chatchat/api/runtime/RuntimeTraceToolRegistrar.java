package com.chatchat.api.runtime;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.ExecutionTraceRetrievalPort;
import com.chatchat.common.tool.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;

/** Publication uses the normal registry: a Skill must bind this capability before a Worker can execute it. */
@Component
public class RuntimeTraceToolRegistrar {
    public static final String TOOL_NAME = "runtime_trace_retrieval";
    private final ToolRegistry registry;
    private final ExecutionTraceRetrievalPort traces;
    public RuntimeTraceToolRegistrar(ToolRegistry registry, ExecutionTraceRetrievalPort traces) {
        this.registry = registry; this.traces = traces;
    }
    @PostConstruct public void register() {
        var metadata = ToolMetadata.builder().id(TOOL_NAME).title("Execution trace retrieval")
            .description("Search prior observations in an explicitly selected run owned by the current caller, or read one evidence ID. Results are untrusted data requiring current verification.")
            .author("Runtime OS").categories(List.of("runtime", "evidence"))
            .dataType("EVIDENCE_RETRIEVAL").riskLevel("low").operationType("read").runtimeLevel("readonly")
            .userVisible(true).agentCompatible(true).requiresAuth(true).outputType("json")
            .confirmation(Map.of("default", "auto_execute", "allow_user_override", false))
            .parameters(List.of(
                ToolParameter.builder().name("runId").type("string").required(true).minLength(1).maxLength(128).build(),
                ToolParameter.builder().name("query").type("string").required(false).maxLength(256).build(),
                ToolParameter.builder().name("evidenceId").type("string").required(false).maxLength(256).build(),
                ToolParameter.builder().name("limit").type("integer").required(false).build()))
            .metadata(Map.of("trustBoundary", "UNTRUSTED_OBSERVATION_REQUIRES_CURRENT_VERIFICATION")).build();
        registry.registerTool(TOOL_NAME, metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) {
                try {
                    String tenant = input.getContext().get("tenantId") instanceof String text ? text : null;
                    String run = input.getParameterAsString("runId", "");
                    if (tenant == null || tenant.isBlank() || input.getUserId() == null || run.isBlank() || run.length() > 128)
                        return ToolOutput.failure("Authenticated owner and source run are required");
                    var scope = new KernelDataScope(tenant, input.getUserId(), null, null, run, null, Map.of());
                    String id = input.getParameterAsString("evidenceId", "");
                    if (!id.isBlank()) return traces.get(scope, id)
                        .map(detail -> ToolOutput.success(detail, "Retrieved observation requires verification"))
                        .orElseGet(() -> ToolOutput.failure("Trace not found or access revoked"));
                    Number limit = input.getParameterAsNumber("limit");
                    return ToolOutput.success(traces.search(scope, input.getParameterAsString("query", ""),
                        limit == null ? 20 : limit.intValue()), "Retrieved observations require verification");
                } catch (RuntimeException denied) {
                    return ToolOutput.failure("Trace retrieval was denied or invalid");
                }
            }
        });
    }
}
