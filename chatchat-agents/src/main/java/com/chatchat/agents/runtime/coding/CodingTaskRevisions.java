package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingContractException;
import com.chatchat.common.runtime.coding.CodingErrorCode;
import java.util.function.LongFunction;

/** Retries only journal CAS conflicts. The mutation must never dispatch external work. */
public final class CodingTaskRevisions {
    private CodingTaskRevisions() { }

    public static <T> T mutate(DurableCodingTaskService tasks, KernelDataScope scope, String taskId,
                                LongFunction<T> mutation) {
        for (int attempt = 0; ; attempt++) {
            long revision = tasks.get(scope, taskId).revision();
            try { return mutation.apply(revision); }
            catch (CodingContractException conflict) {
                if (attempt >= 7 || conflict.error().code() != CodingErrorCode.INVALID_STATE_TRANSITION
                        || !"Coding task revision conflict".equals(conflict.getMessage())) throw conflict;
            }
        }
    }
}
