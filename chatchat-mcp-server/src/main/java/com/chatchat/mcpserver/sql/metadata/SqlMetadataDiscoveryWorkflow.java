package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Executes one model-selected operation. It does not decide what the model should explore next. */
@Component
@RequiredArgsConstructor
public class SqlMetadataDiscoveryWorkflow extends AbstractStagedExecutionWorkflow<
    SqlMetadataDiscoveryWorkflow.Request, String, String, Map<String, Object>, Map<String, Object>> {
    public static final String WORKFLOW_ID = "SQL_METADATA_DISCOVERY";
    private final SqlMetadataDiscoveryService discovery;

    /** Authorization is captured by the transport adapter, never deserialized from model arguments. */
    public record Request(Map<String, Object> arguments, Predicate<String> authorizedDomains) {
        public Request { arguments = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments)); }
    }
    @Override public String workflowId() { return WORKFLOW_ID; }
    @Override protected void validateInput(Request input, KernelDataScope scope) {
        if (input == null || input.authorizedDomains() == null || scope.tenantId() == null || scope.userId() == null)
            throw new SecurityException("Authenticated metadata execution scope is required");
        discovery.validate(input.arguments());
    }
    @Override protected String analyze(Request input, KernelDataScope scope) { return (String) input.arguments().get("operation"); }
    @Override protected String plan(Request input, String operation, KernelDataScope scope) { return operation; }
    @Override protected Map<String, Object> executePlan(Request input, String operation, String plan, KernelDataScope scope) {
        return discovery.discover(input.arguments(), input.authorizedDomains());
    }
    @Override protected void verify(Request input, String operation, String plan, Map<String, Object> result, KernelDataScope scope) {
        if (result == null || !Boolean.TRUE.equals(result.get("doesNotExecuteSql")))
            throw new IllegalStateException("Invalid metadata-only result");
    }
    @Override protected Map<String, Object> assemble(Request input, String operation, String plan, Map<String, Object> result, KernelDataScope scope) {
        Map<String, Object> output = new LinkedHashMap<>(result);
        output.put("workflow", Map.of("workflowId", WORKFLOW_ID, "operation", operation,
            "requestId", java.util.Objects.toString(scope.requestId(), ""), "strategyOwner", "MODEL"));
        return output;
    }
}
