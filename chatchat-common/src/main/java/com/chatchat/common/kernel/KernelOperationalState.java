package com.chatchat.common.kernel;

/** Operational readiness state shared by every Runtime OS Kernel component. */
public enum KernelOperationalState {
    STARTING,
    READY,
    DEGRADED,
    FAILED
}
