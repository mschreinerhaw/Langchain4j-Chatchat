package com.chatchat.agents.runtime.plan.diagnostic;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticRunTest {

    @Test
    void deserializesDiagnosticProfileFromInterpretationPlanProtocol() throws Exception {
        InterpretationPlan plan = new ObjectMapper().readValue("""
            {
              "version": "1.0",
              "intent": {"type": "tool_chain", "goal": "check service health", "risk_level": "low"},
              "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": []},
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "insufficient evidence"},
                    "depends_on": []
                  }
                ],
                "diagnostic_profile": {
                  "profile_id": "generic_health_check",
                  "target_kind": "service",
                  "checks": [
                    {
                      "check_id": "service_availability",
                      "capability": "service_status",
                      "dimension": "availability",
                      "required": true,
                      "priority": 1,
                      "step_ids": []
                    }
                  ]
                }
              },
              "execution_policy": {"max_steps": 1},
              "review": {
                "self_check": {
                  "completeness_score": 0.2,
                  "hallucination_risk": 0.1,
                  "tool_sufficiency": false,
                  "missing_steps": ["service_status"]
                }
              }
            }
            """, InterpretationPlan.class);

        assertThat(plan.plan().diagnosticProfile().profileId()).isEqualTo("generic_health_check");
        assertThat(plan.plan().diagnosticProfile().checks())
            .singleElement()
            .satisfies(check -> {
                assertThat(check.capability()).isEqualTo("service_status");
                assertThat(check.stepIds()).isEmpty();
            });
    }

    @Test
    void reportsMixedDatabaseAndServerCoverageWithoutInventingMissingHealthScores() {
        InterpretationPlan plan = plan(
            List.of(
                check("database_availability", "instance_status", "availability", 1, List.of(1)),
                check("session_pressure", "session_overview", "performance", 2, List.of()),
                check("lock_contention", "lock_overview", "performance", 3, List.of()),
                check("storage_capacity", "tablespace_usage", "capacity", 4, List.of()),
                check("host_resources", "resource_usage", "capacity", 5, List.of(2))
            ),
            3
        );
        List<InterpretationPlanRuntime.StepExecution> executions = List.of(
            execution(
                1,
                "database_query_execute",
                Map.of(
                    "rows", List.of(Map.of("instanceName", "db-instance", "status", "OPEN")),
                    "diagnosticAssessment", Map.of(
                        "availability", Map.of("score", 100, "confidence", 0.96)
                    )
                )
            ),
            execution(
                2,
                "host_resource_query",
                Map.of("cpuPct", 22, "memoryPct", 48, "diskPct", 61)
            ),
            new InterpretationPlanRuntime.StepExecution(
                3, "final_answer", "", true, Map.of("answer", "partial"), null, null, "partial", 1
            )
        );

        DiagnosticRun run = DiagnosticRun.evaluate(plan, executions, Set.of(), 1);

        assertThat(run.coverage())
            .isEqualTo(new DiagnosticRun.Coverage(5, 2, 0, 3, 0.4));
        assertThat(run.checks()).filteredOn(check -> "missing".equals(check.status()))
            .hasSize(3)
            .allSatisfy(check -> assertThat(check.reason()).isEqualTo("execution_budget_exhausted"));
        assertThat(run.checks()).filteredOn(check -> "completed".equals(check.status()))
            .extracting(DiagnosticRun.CheckResult::checkId)
            .containsExactly("database_availability", "host_resources");
        assertThat(run.checks().get(0).evidenceRefs())
            .containsExactly("iteration:1:step:1:tool:database_query_execute");
        assertThat(run.assessment().dimensions().get("availability").score()).isEqualTo(100.0);
        assertThat(run.assessment().dimensions().get("capacity").score()).isNull();
        assertThat(run.assessment().overallScore()).isNull();
        assertThat(run.assessment().overallConfidence()).isEqualTo(0.384);
        assertThat(run.assessment().overallStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void extractsOnlyExplicitStructuredAssessmentAndProducesOverallScoreWhenCoverageIsComplete() {
        InterpretationPlan plan = plan(
            List.of(
                check("service_ready", "service_status", "availability", 1, List.of(1)),
                check("resource_margin", "resource_usage", "capacity", 2, List.of(2))
            ),
            3
        );
        List<InterpretationPlanRuntime.StepExecution> executions = List.of(
            execution(1, "service_query", Map.of(
                "assessment", Map.of("availability", Map.of("score", 100, "confidence", 0.9))
            )),
            execution(2, "resource_query", Map.of(
                "assessment", Map.of("capacity", Map.of("score", 80, "confidence", 0.8))
            )),
            new InterpretationPlanRuntime.StepExecution(
                3, "final_answer", "", true, Map.of("answer", "complete"), null, null, "complete", 1
            )
        );

        DiagnosticRun run = DiagnosticRun.evaluate(plan, executions, Set.of(), 2);

        assertThat(run.coverage().ratio()).isEqualTo(1.0);
        assertThat(run.assessment().overallScore()).isEqualTo(90.0);
        assertThat(run.assessment().overallConfidence()).isEqualTo(0.85);
        assertThat(run.assessment().overallStatus()).isEqualTo("ASSESSED");
    }

    @Test
    void doesNotMarkMultipleChecksCompletedFromOneScalarExecutorResult() {
        InterpretationPlan plan = plan(
            List.of(
                check("instance_status", "instance_status", "availability", 1, List.of(1)),
                check("session_overview", "session_overview", "performance", 2, List.of(1)),
                check("locks", "lock_overview", "performance", 3, List.of(1)),
                check("system_events", "system_wait_events", "performance", 4, List.of(1)),
                check("tablespace_size", "tablespace_usage", "capacity", 5, List.of(1))
            ),
            6
        );

        DiagnosticRun run = DiagnosticRun.evaluate(
            plan,
            List.of(execution(1, "sql_query_execute",
                Map.of("rows", List.of(Map.of("INSTANCE_NAME", "oraclewind", "STATUS", "OPEN"))))),
            Set.of(),
            1
        );

        assertThat(run.coverage()).isEqualTo(new DiagnosticRun.Coverage(5, 0, 0, 5, 0.0));
        assertThat(run.checks()).allSatisfy(check -> {
            assertThat(check.status()).isEqualTo("missing");
            assertThat(check.reason()).isEqualTo("no_check_specific_evidence");
            assertThat(check.evidenceRefs()).isEmpty();
        });
    }

    @Test
    void completesSharedChecksFromReviewedTemplateExecutionContract() {
        InterpretationPlan plan = plan(
            List.of(
                check("first_check", "first_capability", "first", 1, List.of(1)),
                check("second_check", "second_capability", "second", 2, List.of(1))
            ),
            2
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "arbitrary_registered_template_executor",
            true,
            Map.of("result", Map.of("arbitraryNestedValue", 7)),
            null,
            null,
            null,
            5,
            Map.of("templateExecutionReview", Map.of(
                "schemaVersion", "template_execution_satisfaction.v1",
                "satisfied", true
            ))
        );

        DiagnosticRun run = DiagnosticRun.evaluate(plan, List.of(execution), Set.of(), 1);

        assertThat(run.coverage()).isEqualTo(new DiagnosticRun.Coverage(2, 2, 0, 0, 1.0));
        assertThat(run.checks()).allSatisfy(check -> {
            assertThat(check.status()).isEqualTo("completed");
            assertThat(check.evidenceRefs())
                .containsExactly("iteration:1:step:1:tool:arbitrary_registered_template_executor");
        });
    }

    @Test
    void doesNotCreditRejectedTemplateExecutionContract() {
        InterpretationPlan plan = plan(
            List.of(
                check("first_check", "first_capability", "first", 1, List.of(1)),
                check("second_check", "second_capability", "second", 2, List.of(1))
            ),
            2
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "arbitrary_registered_template_executor",
            true,
            Map.of("result", Map.of("arbitraryNestedValue", 7)),
            null,
            null,
            null,
            5,
            Map.of("templateExecutionReview", Map.of(
                "schemaVersion", "template_execution_satisfaction.v1",
                "satisfied", false
            ))
        );

        DiagnosticRun run = DiagnosticRun.evaluate(plan, List.of(execution), Set.of(), 1);

        assertThat(run.coverage()).isEqualTo(new DiagnosticRun.Coverage(2, 0, 0, 2, 0.0));
    }

    @Test
    void completesSharedChecksOnlyFromTheirOrderedBatchChildEvidence() {
        InterpretationPlan plan = plan(
            List.of(
                check("instance_status", "instance_status", "availability", 1, List.of(1)),
                check("session_overview", "session_overview", "performance", 2, List.of(1)),
                check("locks", "lock_overview", "performance", 3, List.of(1))
            ),
            4
        );
        Map<String, Object> output = Map.of("results", List.of(
            Map.of("callId", "instance_status", "status", "SUCCESS", "output", Map.of("STATUS", "OPEN")),
            Map.of("callId", "session_overview", "status", "SUCCESS", "output", Map.of("ACTIVE", 3)),
            Map.of("callId", "locks", "status", "FAILED", "error", Map.of("message", "timeout"))
        ));

        DiagnosticRun run = DiagnosticRun.evaluate(
            plan,
            List.of(execution(1, "sql_query_execute", output)),
            Set.of(),
            1
        );

        assertThat(run.coverage()).isEqualTo(new DiagnosticRun.Coverage(3, 2, 1, 0, 0.667));
        assertThat(run.checks()).extracting(DiagnosticRun.CheckResult::status)
            .containsExactly("completed", "completed", "failed");
    }

    @Test
    void creditsOnlyTheTemplateExplicitlyResolvedForASharedScalarStep() {
        InterpretationPlan plan = plan(
            List.of(
                check("instance_status", "instance_status", "availability", 1, List.of(1)),
                check("session_overview", "session_overview", "performance", 2, List.of(1))
            ),
            3
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "sql_query_execute",
            true,
            Map.of("rows", List.of(Map.of("INSTANCE_NAME", "oraclewind", "STATUS", "OPEN"))),
            null,
            null,
            null,
            5,
            Map.of("resolvedInput", Map.of("templateCode", "ORACLE_INSTANCE_STATUS"))
        );

        DiagnosticRun run = DiagnosticRun.evaluate(plan, List.of(execution), Set.of(), 1);

        assertThat(run.coverage()).isEqualTo(new DiagnosticRun.Coverage(2, 1, 0, 1, 0.5));
        assertThat(run.checks()).extracting(DiagnosticRun.CheckResult::status)
            .containsExactly("completed", "missing");
    }

    @Test
    void calculatesWeightedPartialEvidenceAndBoundedRetryProtocol() {
        List<InterpretationPlan.DiagnosticCheck> checks = List.of(
            weightedCheck("instance_status", "instance_status", "availability", 1, 30, List.of(1)),
            weightedCheck("sessions", "session_overview", "performance", 2, 15, List.of(1)),
            weightedCheck("locks", "lock_overview", "performance", 3, 20, List.of(1)),
            weightedCheck("system_events", "system_wait_events", "performance", 4, 25, List.of(1)),
            weightedCheck("tablespace", "tablespace_usage", "capacity", 5, 10, List.of(1))
        );
        InterpretationPlan base = plan(checks, 6);
        InterpretationPlan plan = new InterpretationPlan(
            base.version(), base.intent(), base.context(),
            new InterpretationPlan.Plan(
                base.plan().steps(), base.plan().edgeContracts(), base.plan().dependencyContracts(),
                base.plan().bindings(), base.plan().stability(),
                new InterpretationPlan.DiagnosticProfile(
                    "weighted_oracle_health",
                    "database",
                    checks,
                    new InterpretationPlan.DiagnosticCompletionPolicy(2, 3, 0.8, 0.6)
                )
            ),
            base.executionPolicy(), base.review()
        );
        Map<String, Object> output = Map.of("results", List.of(
            Map.of("callId", "instance_status", "status", "SUCCESS"),
            Map.of("callId", "sessions", "status", "SUCCESS"),
            Map.of("callId", "locks", "status", "SUCCESS"),
            Map.of("callId", "tablespace", "status", "SUCCESS")
        ));

        DiagnosticRun run = DiagnosticRun.evaluate(
            plan, List.of(execution(1, "sql_query_execute", output)), Set.of(), 1);

        assertThat(run.coverage().ratio()).isEqualTo(0.8);
        assertThat(run.confidenceEngine().weightedCoverage()).isEqualTo(0.75);
        assertThat(run.confidenceEngine().evidenceLevel()).isEqualTo("PARTIAL_EVIDENCE");
        assertThat(run.confidenceEngine().partialConclusionAllowed()).isTrue();
        assertThat(run.confidenceEngine().remainingRetries()).isEqualTo(2);
        assertThat(run.confidenceEngine().completionStatus()).isEqualTo("RETRY_MISSING_EVIDENCE");
        assertThat(run.state()).isEqualTo(DiagnosticRunStateMachine.State.REPAIRING);
        assertThat(run.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(run.executionStatus())
            .isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(run.assessmentStatus())
            .isEqualTo(DiagnosticRunStateMachine.AssessmentStatus.PRELIMINARY_AVAILABLE);
        assertThat(run.evidenceCoverage()).isEqualTo(0.8);
        assertThat(run.failureCode()).isNull();
        assertThat(run.recoveryAction())
            .isEqualTo(DiagnosticRunStateMachine.RecoveryAction.RETRY_MISSING_EVIDENCE);
        assertThat(run.confidenceEngine().missingEvidence()).singleElement().satisfies(missing -> {
            assertThat(missing.checkId()).isEqualTo("system_events");
            assertThat(missing.priority()).isEqualTo("HIGH");
            assertThat(missing.retryEligible()).isTrue();
        });
    }

    @Test
    void mapsRuntimeContractFailureIntoRepairingStateWithoutLosingPartialEvidence() {
        InterpretationPlan plan = plan(
            List.of(
                check("instance_status", "instance_status", "availability", 1, List.of(1)),
                check("sessions", "session_overview", "performance", 2, List.of(2))
            ),
            3
        );
        List<InterpretationPlanRuntime.StepExecution> executions = List.of(
            execution(1, "sql_query_execute", Map.of(
                "diagnosticCheckId", "instance_status",
                "rows", List.of(Map.of("STATUS", "OPEN"))
            )),
            new InterpretationPlanRuntime.StepExecution(
                2,
                "reasoning",
                "",
                false,
                Map.of("message", "not structured"),
                DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.message(
                    "missing required field session_template"),
                null,
                null,
                1
            )
        );

        DiagnosticRun run = DiagnosticRun.evaluate(
            plan,
            executions,
            Set.of(2),
            1,
            DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.wireValue(),
            false
        );

        assertThat(run.state()).isEqualTo(DiagnosticRunStateMachine.State.REPAIRING);
        assertThat(run.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(run.failureCode())
            .isEqualTo(DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED);
        assertThat(run.recoveryAction())
            .isEqualTo(DiagnosticRunStateMachine.RecoveryAction.REWRITE_PLAN);
    }

    @Test
    void optimizerPreservesAndRemapsDiagnosticStepReferences() {
        InterpretationPlan.DiagnosticProfile profile = new InterpretationPlan.DiagnosticProfile(
            "generic_health_check",
            "service",
            List.of(check("resource_margin", "resource_usage", "capacity", 1, List.of(20)))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "check service health", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(10, "reasoning", "", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(
                        20, "mcp_tool", "resource_query",
                        Map.of("diagnosticCapability", "resource_usage"),
                        List.of(), null, null
                    ),
                    new InterpretationPlan.Step(
                        30, "final_answer", "", Map.of("answer", "done"), List.of(20), null, null
                    )
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                profile
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("resource_query"), List.of(), 30000),
            review()
        );

        InterpretationPlan optimized = new InterpretationPlanOptimizer().optimize(plan).plan();

        assertThat(optimized.plan().diagnosticProfile().checks().get(0).stepIds())
            .containsExactly(1);
        assertThat(optimized.steps()).extracting(InterpretationPlan.Step::toolName)
            .contains("resource_query");
    }

    private InterpretationPlan plan(List<InterpretationPlan.DiagnosticCheck> checks, int maxSteps) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "analyze environment health", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1, "mcp_tool", "database_query_execute",
                        Map.of("diagnosticCapability", "instance_status"),
                        List.of(), null, null
                    ),
                    new InterpretationPlan.Step(
                        2, "mcp_tool", "host_resource_query",
                        Map.of("diagnosticCapability", "resource_usage"),
                        List.of(1), null, null
                    ),
                    new InterpretationPlan.Step(
                        3, "final_answer", "", Map.of("answer", "partial"), List.of(2), null, null
                    )
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                new InterpretationPlan.DiagnosticProfile("environment_health_check", "mixed", checks)
            ),
            new InterpretationPlan.ExecutionPolicy(
                maxSteps,
                false,
                List.of("database_query_execute", "host_resource_query"),
                List.of(),
                30000
            ),
            review()
        );
    }

    private InterpretationPlan.DiagnosticCheck check(String checkId,
                                                     String capability,
                                                     String dimension,
                                                     int priority,
                                                     List<Integer> stepIds) {
        return new InterpretationPlan.DiagnosticCheck(
            checkId, capability, dimension, true, priority, stepIds
        );
    }

    private InterpretationPlan.DiagnosticCheck weightedCheck(String checkId,
                                                             String capability,
                                                             String dimension,
                                                             int priority,
                                                             double weight,
                                                             List<Integer> stepIds) {
        return new InterpretationPlan.DiagnosticCheck(
            checkId, capability, dimension, true, priority, stepIds, weight
        );
    }

    private InterpretationPlanRuntime.StepExecution execution(int stepId,
                                                              String toolName,
                                                              Map<String, Object> output) {
        return new InterpretationPlanRuntime.StepExecution(
            stepId, "mcp_tool", toolName, true, output, null, null, null, 5
        );
    }

    private InterpretationPlan.Context context() {
        return new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of());
    }

    private InterpretationPlan.Review review() {
        return new InterpretationPlan.Review(
            new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()),
            List.of()
        );
    }
}
