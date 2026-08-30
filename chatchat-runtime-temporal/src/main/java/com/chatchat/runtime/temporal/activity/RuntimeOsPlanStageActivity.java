package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Non-deterministic business stages called by the deterministic plan execution Workflow. */
@ActivityInterface
public interface RuntimeOsPlanStageActivity {

    @ActivityMethod(name = "runtime-os-plan-model-arbitrate-v1")
    PlanModelArbitrationResult arbitrate(PlanModelArbitrationCommand command);

    @ActivityMethod(name = "runtime-os-plan-step-prepare-v1")
    PlanStepPreparationResult prepare(PlanStepPreparationCommand command);

    @ActivityMethod(name = "runtime-os-plan-node-persist-v1")
    PlanNodePersistenceResult persist(PlanNodePersistenceCommand command);
}
