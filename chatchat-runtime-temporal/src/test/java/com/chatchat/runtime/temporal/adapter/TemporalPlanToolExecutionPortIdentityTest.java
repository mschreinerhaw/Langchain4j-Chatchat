package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionCommand;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalPlanToolExecutionPortIdentityTest {

    private final PlanToolExecutionCommand command = new PlanToolExecutionCommand(
        PlanToolExecutionCommand.SCHEMA_VERSION, "run-1", "scope-1", "0", 2,
        "PRIMARY", "fingerprint-1", "run-1:scope-1:step:2:primary:fingerprint-1",
        ToolRuntimeRequest.builder().toolName("report.read")
            .tenantId("tenant-1").userId("user-1").build());

    @Test
    void samePersistedInvocationCanReattach() {
        assertThatCode(() -> TemporalPlanToolExecutionPort.validatePersistedIdentity(
            identity(), command)).doesNotThrowAnyException();
    }

    @Test
    void anotherRunOrInvocationCannotReuseThePersistedToolResult() {
        for (String changed : new String[] {"planRunId", "planStepId", "planToolIdempotencyKey",
            "planInvocationFingerprint", "planToolName", "planTenantId", "planUserId"}) {
            Map<String, String> persisted = identity();
            persisted.put(changed, "another-value");
            assertThatThrownBy(() -> TemporalPlanToolExecutionPort.validatePersistedIdentity(
                persisted, command)).as(changed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(changed);
        }
    }

    @Test
    void olderWorkflowCanReattachUsingItsOriginalIdentityFields() {
        Map<String, String> persisted = identity();
        persisted.remove("planInvocationFingerprint");
        persisted.remove("planToolName");
        assertThatCode(() -> TemporalPlanToolExecutionPort.validatePersistedIdentity(
            persisted, command)).doesNotThrowAnyException();
    }

    private Map<String, String> identity() {
        return new LinkedHashMap<>(Map.of(
            "planToolSchemaVersion", command.schemaVersion(),
            "planRunId", command.runId(),
            "planExecutionScope", command.planExecutionScope(),
            "planStepId", String.valueOf(command.stepId()),
            "planInvocationRole", command.invocationRole(),
            "planToolIdempotencyKey", command.idempotencyKey(),
            "planInvocationFingerprint", command.invocationFingerprint(),
            "planToolName", command.request().getToolName(),
            "planTenantId", command.request().getTenantId(),
            "planUserId", command.request().getUserId()));
    }
}
