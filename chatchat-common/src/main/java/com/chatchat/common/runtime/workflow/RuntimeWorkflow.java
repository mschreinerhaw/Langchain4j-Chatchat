package com.chatchat.common.runtime.workflow;

import com.chatchat.common.kernel.KernelComponentDescriptor;
import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocol;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.RuntimeOsKernel;

import java.util.Set;

/** Stable Runtime OS port for an executable workflow. */
public interface RuntimeWorkflow<I, O> extends RuntimeOsKernel<I, O> {
    /** Stable identity; must not depend on an implementation class name. */
    String workflowId();

    O execute(I input);

    /** Scope-aware entry point used by the Kernel and overridable by direct implementations. */
    default O execute(I input, KernelDataScope scope) {
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        return execute(input);
    }

    @Override default O executeKernel(I payload, KernelDataScope scope) { return execute(payload, scope); }
    @Override default KernelComponentDescriptor kernelDescriptor() {
        return new KernelComponentDescriptor(workflowId(), "runtime-workflow", "1", Set.of("execute"));
    }
    @Override default KernelProtocol kernelProtocol() { return KernelProtocolCatalog.RUNTIME_EXECUTION; }
    @Override default KernelDataBoundary kernelDataBoundary() { return KernelProtocolCatalog.RUNTIME_BOUNDARY; }
}
