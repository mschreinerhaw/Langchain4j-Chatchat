package com.chatchat.common.runtime.capability;

import com.chatchat.common.kernel.KernelDataScope;

/** A typed compute adapter. Its implementation owns no cross-node planning decisions. */
public interface ExecutionUnit<I, O> {
    ComputeNodeType nodeType();
    Class<I> inputType();
    Class<O> outputType();
    O execute(I input, KernelDataScope scope);
}
