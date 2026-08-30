package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.tool.ToolActivityRetryPolicy;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RuntimeOsToolActivityImpl implements RuntimeOsToolActivity {

    public static final String IDEMPOTENCY_KEY_ATTRIBUTE = "workflowActivityIdempotencyKey";
    public static final String ACTIVITY_ID_ATTRIBUTE = "workflowActivityId";

    private final ToolRuntimeService toolRuntimeService;

    public RuntimeOsToolActivityImpl(ToolRuntimeService toolRuntimeService) {
        this.toolRuntimeService = Objects.requireNonNull(toolRuntimeService, "toolRuntimeService");
    }

    @Override
    public ToolRuntimeExecution execute(TemporalToolActivityCommand command) {
        ToolRuntimeRequest request = command.request();
        enforceRetryAdmission(command, request);
        Map<String, Object> attributes = new LinkedHashMap<>(
            request.getAttributes() == null ? Map.of() : request.getAttributes());
        attributes.put(IDEMPOTENCY_KEY_ATTRIBUTE, command.idempotencyKey());
        attributes.put(ACTIVITY_ID_ATTRIBUTE, Activity.getExecutionContext().getInfo().getActivityId());
        attributes.put("workflowActivityAttempt", Activity.getExecutionContext().getInfo().getAttempt());
        attributes.put("workflowActivityRetrySafe", command.retrySafe());
        attributes.put("workflowActivityRetryPolicyReason", command.retryPolicyReason());
        // Temporal owns whole-Activity retries here. Disable the inner transport retry loop so
        // one configured Activity attempt always means one governed ToolRuntime attempt.
        attributes.put("toolRetryAttempts", 0);
        request.setAttributes(Map.copyOf(attributes));
        ToolRuntimeExecution execution = toolRuntimeService.execute(request);
        if (execution == null) {
            return null;
        }
        Map<String, Object> audit = new LinkedHashMap<>(
            execution.audit() == null ? Map.of() : execution.audit());
        audit.put("workflowActivityId", attributes.get(ACTIVITY_ID_ATTRIBUTE));
        audit.put("workflowActivityIdempotencyKey", command.idempotencyKey());
        audit.put("workflowActivityAttempt", attributes.get("workflowActivityAttempt"));
        audit.put("workflowActivityMaximumAttempts", command.maximumAttempts());
        audit.put("workflowActivityRetrySafe", command.retrySafe());
        audit.put("workflowActivityRetryPolicyReason", command.retryPolicyReason());
        return new ToolRuntimeExecution(
            execution.output(), execution.metadata(), execution.trace(), execution.outcome(),
            Map.copyOf(audit));
    }

    private void enforceRetryAdmission(TemporalToolActivityCommand command,
                                       ToolRuntimeRequest request) {
        ToolActivityRetryPolicy.Decision admitted = new ToolActivityRetryPolicy().resolve(
            toolRuntimeService.metadata(request.getToolName()));
        boolean exceedsAdmission = command.retrySafe()
            && (!admitted.retrySafe() || command.maximumAttempts() > admitted.maximumAttempts());
        if (!exceedsAdmission) {
            return;
        }
        throw ApplicationFailure.newNonRetryableFailure(
            "Tool Activity retry admission no longer matches runtime metadata for "
                + request.getToolName(),
            "TOOL_ACTIVITY_RETRY_ADMISSION_REJECTED");
    }
}
