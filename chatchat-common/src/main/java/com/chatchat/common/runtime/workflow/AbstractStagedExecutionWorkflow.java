package com.chatchat.common.runtime.workflow;

import com.chatchat.common.kernel.KernelDataScope;

/**
 * Template method for capability workflows whose execution requires explicit
 * analysis, planning, execution, verification, and result assembly stages.
 */
public abstract class AbstractStagedExecutionWorkflow<I, A, P, E, O>
    extends AbstractRuntimeWorkflow<I, O> {

    @Override
    protected final O doExecute(I input, KernelDataScope scope) {
        A analysis = analyze(input, scope);
        P plan = plan(input, analysis, scope);
        E execution = executePlan(input, analysis, plan, scope);
        verify(input, analysis, plan, execution, scope);
        return assemble(input, analysis, plan, execution, scope);
    }

    @Override
    protected final O doExecute(I input) {
        throw new UnsupportedOperationException("Kernel-scoped execution is required");
    }

    protected abstract A analyze(I input, KernelDataScope scope);

    protected abstract P plan(I input, A analysis, KernelDataScope scope);

    protected abstract E executePlan(I input, A analysis, P plan, KernelDataScope scope);

    protected abstract void verify(I input, A analysis, P plan, E execution, KernelDataScope scope);

    protected abstract O assemble(I input, A analysis, P plan, E execution, KernelDataScope scope);
}
