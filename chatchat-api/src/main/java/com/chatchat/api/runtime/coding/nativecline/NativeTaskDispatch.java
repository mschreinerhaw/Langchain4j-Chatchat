package com.chatchat.api.runtime.coding.nativecline;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.kernel.KernelDataScope;
/** Scheduling boundary only; no model/analysis decisions. */
public interface NativeTaskDispatch {
    void start(CodingTaskContract contract);
    void cancel(KernelDataScope scope,String task);
    default java.util.Map<String,Object> status(KernelDataScope scope,String task){return java.util.Map.of();}
    default java.util.List<java.util.Map<String,Object>> nodes(){return java.util.List.of();}
}
