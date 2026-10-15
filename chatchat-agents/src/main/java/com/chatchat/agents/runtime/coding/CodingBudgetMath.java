package com.chatchat.agents.runtime.coding;

import com.chatchat.common.runtime.coding.CodingTaskContract.Budget;
import com.chatchat.common.runtime.coding.CodingTaskOutcome.Counters;
import java.math.BigDecimal;

/** Exact cumulative accounting. Peak memory is observational, never a cumulative reservation. */
final class CodingBudgetMath {
    private CodingBudgetMath() { }
    static Counters zero() { return new Counters(0,0,0,0,0,0,0,"0"); }
    static String decimal(BigDecimal value) { return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString(); }
    static Counters add(Counters a, Counters b) {
        return new Counters(Math.addExact(a.modelCalls(),b.modelCalls()), Math.addExact(a.modelTokens(),b.modelTokens()),
            Math.addExact(a.toolCalls(),b.toolCalls()), Math.addExact(a.codeExecutions(),b.codeExecutions()),
            Math.addExact(a.cpuMillis(),b.cpuMillis()), Math.max(a.peakMemoryBytes(),b.peakMemoryBytes()),
            Math.addExact(a.outputBytes(),b.outputBytes()), decimal(new BigDecimal(a.costAmount()).add(new BigDecimal(b.costAmount()))));
    }
    static Counters remaining(Counters reserved, Counters actual) {
        return new Counters(Math.max(0,reserved.modelCalls()-actual.modelCalls()), Math.max(0,reserved.modelTokens()-actual.modelTokens()),
            Math.max(0,reserved.toolCalls()-actual.toolCalls()), Math.max(0,reserved.codeExecutions()-actual.codeExecutions()),
            Math.max(0,reserved.cpuMillis()-actual.cpuMillis()), 0, Math.max(0,reserved.outputBytes()-actual.outputBytes()),
            decimal(new BigDecimal(reserved.costAmount()).subtract(new BigDecimal(actual.costAmount())).max(BigDecimal.ZERO)));
    }
    static boolean covers(Counters next, Counters previous) {
        return next.modelCalls()>=previous.modelCalls() && next.modelTokens()>=previous.modelTokens()
            && next.toolCalls()>=previous.toolCalls() && next.codeExecutions()>=previous.codeExecutions()
            && next.cpuMillis()>=previous.cpuMillis() && next.peakMemoryBytes()>=previous.peakMemoryBytes()
            && next.outputBytes()>=previous.outputBytes()
            && new BigDecimal(next.costAmount()).compareTo(new BigDecimal(previous.costAmount()))>=0;
    }
    static boolean within(Counters total, Budget limit) {
        return total.modelCalls()<=limit.maxModelCalls() && total.modelTokens()<=limit.maxModelTokens()
            && total.toolCalls()<=limit.maxToolCalls() && total.codeExecutions()<=limit.maxCodeExecutions()
            && total.cpuMillis()<=limit.maxCpuMillis() && total.outputBytes()<=limit.maxOutputBytes()
            && new BigDecimal(total.costAmount()).compareTo(new BigDecimal(limit.maxCostAmount()))<=0;
    }
}
