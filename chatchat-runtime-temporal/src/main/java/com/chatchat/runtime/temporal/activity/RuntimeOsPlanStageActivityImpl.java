package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;

import io.temporal.failure.ApplicationFailure;

public final class RuntimeOsPlanStageActivityImpl implements RuntimeOsPlanStageActivity {

    private final PlanExecutionPhaseHandler handler;

    public RuntimeOsPlanStageActivityImpl(PlanExecutionPhaseHandler handler) {
        this.handler = handler;
    }

    @Override
    public PlanModelArbitrationResult arbitrate(PlanModelArbitrationCommand command) {
        return requiredHandler().arbitrate(command);
    }

    @Override
    public PlanStepPreparationResult prepare(PlanStepPreparationCommand command) {
        return requiredHandler().prepare(command);
    }

    @Override
    public PlanNodePersistenceResult persist(PlanNodePersistenceCommand command) {
        return requiredHandler().persist(command);
    }

    private PlanExecutionPhaseHandler requiredHandler() {
        if (handler == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Plan execution phase handler is not configured",
                "PLAN_PHASE_HANDLER_UNAVAILABLE");
        }
        return handler;
    }
}
