/**
 * Runtime OS orchestration entry points, composition roots, and stable orchestration ports.
 *
 * <p>The root package contains no feature algorithm by design. Planning, workflow, evidence,
 * tool, analysis, and answer implementations belong in functional child packages and communicate
 * through explicit ports. {@code AgentOrchestrationEngine} is a temporary migration composition
 * root and must continuously shrink until it contains wiring and lifecycle coordination only.</p>
 */
package com.chatchat.agents.orchestration;
