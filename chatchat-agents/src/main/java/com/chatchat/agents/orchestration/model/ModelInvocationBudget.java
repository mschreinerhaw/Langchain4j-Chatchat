package com.chatchat.agents.orchestration.model;

/** Caller-thread phase budget; composes with the existing request deadline, without nested executors. */
public final class ModelInvocationBudget implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
    private final Long previous;
    private ModelInvocationBudget(long milliseconds) {
        previous=DEADLINE.get();
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1,milliseconds));
        DEADLINE.set(previous==null?end:Math.min(end,previous));
    }
    public static ModelInvocationBudget open(long milliseconds) {return new ModelInvocationBudget(milliseconds);}
    static long remaining(long requestRemaining) {
        Long end=DEADLINE.get();
        if(end==null)return requestRemaining;
        long phase=Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()));
        return requestRemaining<0?phase:Math.min(requestRemaining,phase);
    }
    @Override public void close() {if(previous==null)DEADLINE.remove();else DEADLINE.set(previous);}
}
