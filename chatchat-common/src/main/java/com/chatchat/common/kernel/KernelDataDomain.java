package com.chatchat.common.kernel;

/** Explicit data domains that may cross a Kernel component boundary. */
public enum KernelDataDomain {
    CONTROL,
    TOOL_ARGUMENTS,
    TOOL_RESULTS,
    EVIDENCE,
    OBSERVATIONS,
    EVENTS,
    ARTIFACTS,
    SECRETS
}
