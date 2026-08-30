package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.runtime.plan.execution.DeterministicPlanDagStateMachine;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalPlanDagBarrierCommand;
import com.chatchat.runtime.temporal.workflow.RuntimeOsPlanDagControlWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Temporal-backed durable session for DAG state transitions. */
public final class TemporalPlanDagControlPort implements PlanDagControlPort {

    public static final String WORKFLOW_ID_PREFIX = "plan-dag::";

    private final WorkflowClient client;
    private final TemporalWorkflowProperties properties;

    public TemporalPlanDagControlPort(WorkflowClient client, TemporalWorkflowProperties properties) {
        this.client = Objects.requireNonNull(client, "client");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Session open(SessionCommand command) {
        String workflowId = workflowId(command.sessionId());
        RuntimeOsPlanDagControlWorkflow workflow = client.newWorkflowStub(
            RuntimeOsPlanDagControlWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.taskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setMemo(Map.of(
                    "planDagSchemaVersion", command.schemaVersion(),
                    "planDagSessionId", command.sessionId()))
                .build());
        try {
            WorkflowClient.start(workflow::run, command);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // An Activity retry or worker restart reattaches to the durable DAG state owner.
        }
        return new TemporalSession(workflow);
    }

    public static String workflowId(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return WORKFLOW_ID_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to derive plan DAG Workflow id", ex);
        }
    }

    private static final class TemporalSession implements Session {
        private final RuntimeOsPlanDagControlWorkflow workflow;
        private boolean closed;

        private TemporalSession(RuntimeOsPlanDagControlWorkflow workflow) {
            this.workflow = workflow;
        }

        @Override
        public Snapshot synchronize(StateCommand command) {
            ensureOpen();
            return workflow.synchronize(command);
        }

        @Override
        public DeterministicPlanDagStateMachine.BarrierDecision decideBarrier(
            Collection<DeterministicPlanDagStateMachine.NodeOutcome> outcomes,
            boolean commitIndependentSuccesses) {
            ensureOpen();
            return workflow.decideBarrier(new TemporalPlanDagBarrierCommand(
                outcomes == null ? List.of() : List.copyOf(outcomes),
                commitIndependentSuccesses));
        }

        @Override
        public void close() {
            if (!closed) {
                workflow.closeSession();
                closed = true;
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Plan DAG Temporal session is closed");
            }
        }
    }
}
