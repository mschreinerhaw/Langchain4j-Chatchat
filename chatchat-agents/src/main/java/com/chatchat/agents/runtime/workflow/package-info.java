/**
 * Workflow-engine-neutral Runtime OS contracts.
 *
 * <p>Model planning remains outside this package. Implementations own deterministic execution,
 * idempotent start, cancellation and lifecycle visibility; persistent Agent business state remains
 * authoritative in the Agent run store until a distributed workflow adapter takes ownership.</p>
 */
package com.chatchat.agents.runtime.workflow;
