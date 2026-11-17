package com.chatchat.deployment.runtime;

import com.chatchat.deployment.release.ApplicationRelease;
import java.time.Instant;
import java.util.List;

/** Observation must never mutate workloads. Execution requires an approved, persisted plan. */
public interface RuntimeAdapter {
    String type();
    Observation observe(String instanceId);
    Plan preflight(ApplicationRelease release, String instanceId);
    String execute(String approvedPlanId, String actor);

    enum Health { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY, STOPPED }
    record ComponentHealth(String name, Health health, String reason) {}
    record Observation(String instanceId, Instant sampledAt, Health health, List<ComponentHealth> components) {}
    record Check(String name, boolean passed, String detail) {}
    record Plan(String id, Instant expiresAt, List<Check> checks) {}
}
