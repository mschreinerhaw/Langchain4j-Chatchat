/**
 * Runtime OS orchestration entry points, composition roots, and stable orchestration ports.
 *
 * <p>Planning, workflow, evidence,
 * tool, analysis, and answer implementations belong in functional child packages and communicate
 * through explicit ports. {@code AgentOrchestrationEngine} is a temporary migration composition
 * root and must continuously shrink until it contains wiring and lifecycle coordination only.
 * The package-private GraphPlanningPort and InterpretationAnalysisSession bind existing host
 * operations to analysis graphs without exposing host services as public APIs. Phase routing
 * belongs to analysis.graph; these adapters must not add a competing execution loop.</p>
 */
package com.chatchat.agents.orchestration;
