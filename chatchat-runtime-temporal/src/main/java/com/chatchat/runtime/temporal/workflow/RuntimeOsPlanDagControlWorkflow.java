package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.plan.execution.DeterministicPlanDagStateMachine;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.runtime.temporal.contract.TemporalPlanDagBarrierCommand;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsPlanDagControlWorkflow {

    @WorkflowMethod(name = "runtime-os-plan-dag-control-v1")
    PlanDagControlPort.Snapshot run(PlanDagControlPort.SessionCommand command);

    @UpdateMethod(name = "runtime-os-plan-dag-synchronize-v1")
    PlanDagControlPort.Snapshot synchronize(PlanDagControlPort.StateCommand command);

    @UpdateMethod(name = "runtime-os-plan-dag-barrier-v1")
    DeterministicPlanDagStateMachine.BarrierDecision decideBarrier(
        TemporalPlanDagBarrierCommand command);

    @UpdateMethod(name = "runtime-os-plan-dag-close-v1")
    PlanDagControlPort.Snapshot closeSession();

    @QueryMethod(name = "runtime-os-plan-dag-status-v1")
    PlanDagControlPort.Snapshot status();
}
