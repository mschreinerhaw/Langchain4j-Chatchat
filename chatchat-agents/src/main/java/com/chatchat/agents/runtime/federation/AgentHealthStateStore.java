package com.chatchat.agents.runtime.federation;

/** Atomic health history boundary shared by SLA ranking and the circuit breaker. */
public interface AgentHealthStateStore {
    State read(String agentId);
    State record(String agentId, boolean failed, long latencyMs, long nowEpochMs);

    record State(int samples, int consecutiveFailures, long ewmaLatencyMs, long openUntilEpochMs) {
        public static State empty() { return new State(0, 0, 0, 0); }

        public State next(boolean failed, long latencyMs, long nowEpochMs) {
            int count = samples + 1;
            long latency = Math.max(0, latencyMs);
            long ewma = samples == 0 ? latency : Math.round(ewmaLatencyMs * .8 + latency * .2);
            int failures = failed ? consecutiveFailures + 1 : 0;
            long until = failed && failures >= 3 ? nowEpochMs + 30_000 : 0;
            return new State(count, failures, ewma, until);
        }
    }
}
