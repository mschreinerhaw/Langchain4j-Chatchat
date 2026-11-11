package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;

/** Select an explicit frozen execution profile; never silently fall back. */
public final class NativeAwareCodingAdapter implements CodingAgentSPI {
    private final CodingAgentSPI legacy;private final NativeS3CodingAdapter nativeNode;private final DurableCodingTaskService tasks;
    public NativeAwareCodingAdapter(CodingAgentSPI legacy,NativeS3CodingAdapter nativeNode,DurableCodingTaskService tasks){this.legacy=legacy;this.nativeNode=nativeNode;this.tasks=tasks;}
    private CodingAgentSPI select(CodingTaskContract contract){if(!NativeSiloTaskService.supports(contract))return legacy;if(nativeNode==null)throw new IllegalStateException("Native S3 execution is not enabled; no Sandbox fallback");return nativeNode;}
    public CodingTaskOutcome submit(CodingTaskContract contract){return select(contract).submit(contract);}
    public CodingTaskOutcome get(KernelDataScope scope,String task){return select(tasks.contract(scope,task)).get(scope,task);}
    public CodingTaskOutcome cancel(KernelDataScope scope,String task,String reason){return select(tasks.contract(scope,task)).cancel(scope,task,reason);}
    public CodingTaskOutcome resume(KernelDataScope scope,String task){return select(tasks.contract(scope,task)).resume(scope,task);}
    public Subscription subscribe(KernelDataScope scope,String task,long after,Observer observer){return select(tasks.contract(scope,task)).subscribe(scope,task,after,observer);}
}
