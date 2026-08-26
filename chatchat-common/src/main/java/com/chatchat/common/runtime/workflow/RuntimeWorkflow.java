package com.chatchat.common.runtime.workflow;

import com.chatchat.common.kernel.KernelComponentDescriptor;
import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocol;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.RuntimeOsKernel;

import java.util.Set;

/** Stable Runtime OS port for an executable workflow. */
@FunctionalInterface
public interface RuntimeWorkflow<I, O> extends RuntimeOsKernel<I, O> {
    O execute(I input);

    @Override default O executeKernel(I payload, KernelDataScope scope) { return execute(payload); }
    @Override default KernelComponentDescriptor kernelDescriptor() {
        return new KernelComponentDescriptor(getClass().getName(), "runtime-workflow", "1", Set.of("execute"));
    }
    @Override default KernelProtocol kernelProtocol() { return KernelProtocolCatalog.RUNTIME_EXECUTION; }
    @Override default KernelDataBoundary kernelDataBoundary() { return KernelProtocolCatalog.RUNTIME_BOUNDARY; }
}
