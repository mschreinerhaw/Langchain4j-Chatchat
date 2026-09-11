package com.chatchat.agents.runtime.batch;

public enum BatchExecutionMode {
    SEQUENTIAL,
    /** Runtime-authorized concurrent execution for independent read-only calls. */
    PARALLEL_READ_ONLY
}
