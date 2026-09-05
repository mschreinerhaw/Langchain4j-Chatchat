/**
 * Invocation-local analytical control graphs and versioned cross-stage decisions.
 *
 * <p>Graphs own phase transitions, not tool side effects, transport retries, or durable storage.
 * Runtime ports execute node actions; Temporal continuations remain the durable authority.
 * Graph state and telemetry must not contain model instances or raw evidence payloads.
 */
package com.chatchat.agents.orchestration.analysis.graph;
