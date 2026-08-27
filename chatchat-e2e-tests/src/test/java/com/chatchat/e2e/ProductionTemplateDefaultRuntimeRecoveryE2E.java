package com.chatchat.e2e;

import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Release gate for evidence-backed template selection and default parameter execution.
 *
 * <p>The Runtime exists to execute a usable governed template. Omitted parameters
 * with declared defaults must not turn into a user-facing protocol error.</p>
 */
class ProductionTemplateDefaultRuntimeRecoveryE2E {

    @Test
    @SuppressWarnings("unchecked")
    void rejectsIncompatibleCandidateThenExecutesAlternativeWithEvidenceOverridesAndDefaults() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String templateTool = "mcp_runtime_" + suffix + "_api_template_query";
        String executorTool = "mcp_runtime_" + suffix + "_api_template_execute";
        String incompatibleTemplate = "precise_" + suffix;
        String executableTemplate = "compatible_" + suffix;
        String customerId = "customer-" + suffix.substring(0, 12);
        List<Map<String, Object>> executorCalls = new ArrayList<>();

        ToolRegistry registry = registry(templateTool, executorTool, input -> {
            Map<String, Object> arguments = new LinkedHashMap<>(input.getParameters());
            executorCalls.add(Map.copyOf(arguments));
            return ToolOutput.success(Map.of(
                "schemaVersion", "api_execution_result.v1",
                "templateId", arguments.get("templateId"),
                "records", List.of(Map.of("customerId", customerId, "amount", 1250.50))
            ));
        }, candidates(incompatibleTemplate, executableTemplate));

        ToolRuntimeService toolRuntime = runtime(registry);
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            if (templateTool.equals(request.step().toolName())) {
                return InterpretationPlanRuntime.StepReview.accepted(
                    "Selected the candidate whose required business input is present and whose remaining inputs have governed defaults.",
                    Map.of(
                        "selectedTemplateIds", List.of(executableTemplate),
                        "rejectedTemplateIds", List.of(incompatibleTemplate),
                        "refinedIntent", "execute a customer flow query with a compatible parameter contract",
                        "templateEvaluations", List.of(
                            Map.of(
                                "templateId", incompatibleTemplate,
                                "decision", "reject",
                                "totalScore", 0.35,
                                "reasons", List.of("accountId is required but unavailable and has no default")
                            ),
                            Map.of(
                                "templateId", executableTemplate,
                                "decision", "accept",
                                "totalScore", 0.96,
                                "reasons", List.of("customerId is available; date and paging fields have template defaults")
                            )
                        )
                    )
                );
            }
            return InterpretationPlanRuntime.StepReview.accepted(
                "The executor returned business records.", Map.of());
        };
        InterpretationPlanRuntime.DagExecutionController controller = request -> {
            if (request.remainingStepIds().contains(2)) {
                Map<String, Object> protocol = Map.of(
                    "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
                    "step_id", 2,
                    "template_id", executableTemplate,
                    "arguments", Map.of(
                        "customerId", Map.of(
                            "value", customerId,
                            "source", "user_query",
                            "evidence", customerId
                        )
                    ),
                    // These are intentionally omitted by the user. Their template defaults
                    // are authoritative and must resolve them before execution.
                    "unresolved_parameters", List.of("startDate", "endDate", "page", "pageSize")
                );
                return new InterpretationPlanRuntime.DagDecision(
                    InterpretationExecutionProtocol.VERSION,
                    "execute_step",
                    List.of(2),
                    "Use the observed customer id and preserve template defaults for omitted fields.",
                    null,
                    Map.of("parameterProtocols", List.of(protocol))
                );
            }
            return InterpretationPlanRuntime.DagDecision.finalAnswer(
                3,
                "Execution evidence is available.",
                "查询成功；原精确候选因缺少无默认值的 accountId 被放弃，改用参数契约兼容的候选并采用其日期和分页默认值。"
            );
        };
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntime,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            controller
        );

        try {
            InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
                new InterpretationPlanRuntime.ExecutionRequest(
                    plan(templateTool, executorTool),
                    registry,
                    List.of(templateTool, executorTool),
                    "tenant-" + suffix,
                    "request-" + suffix,
                    "conversation-" + suffix,
                    "user-" + suffix,
                    Map.of(
                        "originalUserQuery", "查询客户 " + customerId + " 的资金流水",
                        "requireTemplateParameterProtocol", true
                    )
                )
            );

            assertThat(result.success())
                .as("status=%s error=%s", result.status(), result.errorMessage())
                .isTrue();
            assertThat(result.errorMessage()).isNull();
            assertThat(result.finalAnswer())
                .as("Runtime must finish with a usable answer after executing the recovered path")
                .isNotBlank()
                .doesNotContain("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED", "service unavailable");
            assertThat(executorCalls).hasSize(1);
            Map<String, Object> executorInput = executorCalls.get(0);
            assertThat(executorInput)
                .containsEntry("templateId", executableTemplate)
                .containsEntry("template", executableTemplate);
            Map<String, Object> parameters = (Map<String, Object>) executorInput.get("parameters");
            assertThat(parameters)
                .containsEntry("customerId", customerId)
                .containsEntry("startDate", "2026-08-01")
                .containsEntry("endDate", "2026-08-04")
                .containsEntry("page", 1)
                .containsEntry("pageSize", 50)
                .doesNotContainKey("accountId");

            InterpretationPlanRuntime.StepExecution discovery = result.steps().stream()
                .filter(step -> Integer.valueOf(1).equals(step.stepId()))
                .findFirst()
                .orElseThrow();
            assertThat(discovery.metadata())
                .containsEntry("runtimeSelectedTemplateIds", List.of(executableTemplate));
            assertThat(discovery.metadata().get("runtimeTemplateCandidateEvaluations").toString())
                .contains(incompatibleTemplate, executableTemplate)
                .contains("accountId is required but unavailable and has no default")
                .contains("date and paging fields have template defaults");
            assertThat(result.steps())
                .filteredOn(step -> Integer.valueOf(2).equals(step.stepId()))
                .allSatisfy(step -> {
                    assertThat(step.success()).isTrue();
                    assertThat(step.errorMessage()).isNull();
                    assertThat(step.output().toString()).contains(customerId, "1250.5");
                });
        } finally {
            toolRuntime.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesDefaultRequiredParametersAndDiscardsUnprovenOverrides() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String templateTool = "mcp_runtime_" + suffix + "_api_template_query";
        String executorTool = "mcp_runtime_" + suffix + "_api_template_execute";
        String templateId = "all_defaults_" + suffix;
        List<Map<String, Object>> executorCalls = new ArrayList<>();
        Map<String, Object> template = template(
            templateId,
            Map.of(
                "market", Map.of("type", "string", "default", "ALL"),
                "limit", Map.of("type", "integer", "default_value", 100)
            ),
            List.of("market", "limit")
        );
        ToolRegistry registry = registry(templateTool, executorTool, input -> {
            executorCalls.add(Map.copyOf(input.getParameters()));
            return ToolOutput.success(Map.of("records", List.of(Map.of("status", "executed"))));
        }, Map.of("templates", List.of(template), "returnedCount", 1));
        ToolRuntimeService toolRuntime = runtime(registry);
        int[] controllerCallsForExecutor = {0};
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntime,
            new InterpretationPlanValidator(),
            request -> {
                if (request.remainingStepIds().contains(2)) {
                    controllerCallsForExecutor[0]++;
                }
                return InterpretationPlanRuntime.DagDecision.finalAnswer(3, "done", "执行成功");
            }
        );

        try {
            InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
                new InterpretationPlanRuntime.ExecutionRequest(
                    plan(templateTool, executorTool, Map.of("market", "UNPROVEN-OVERRIDE")),
                    registry,
                    List.of(templateTool, executorTool),
                    "tenant-" + suffix,
                    "request-" + suffix,
                    "conversation-" + suffix,
                    "user-" + suffix,
                    Map.of("originalUserQuery", "执行默认查询", "requireTemplateParameterProtocol", true)
                )
            );

            assertThat(result.success()).isTrue();
            assertThat(controllerCallsForExecutor[0])
                .as("default-covered executor should be scheduled by Runtime without a model parameter round")
                .isZero();
            assertThat(executorCalls).hasSize(1);
            Map<String, Object> parameters = (Map<String, Object>) executorCalls.get(0).get("parameters");
            assertThat(parameters)
                .containsEntry("market", "ALL")
                .containsEntry("limit", 100);
        } finally {
            toolRuntime.shutdown();
        }
    }

    private ToolRegistry registry(String templateTool,
                                  String executorTool,
                                  java.util.function.Function<ToolInput, ToolOutput> executor,
                                  Map<String, Object> discoveryResult) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0))
            .title(invocation.getArgument(0))
            .riskLevel("low")
            .categories(List.of("mcp"))
            .build());
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolInput input = invocation.getArgument(1);
            if (templateTool.equals(toolName)) {
                return ToolOutput.success(discoveryResult);
            }
            if (executorTool.equals(toolName)) {
                return executor.apply(input);
            }
            return ToolOutput.failure("Unexpected E2E tool: " + toolName);
        });
        return registry;
    }

    private ToolRuntimeService runtime(ToolRegistry registry) {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        return new ToolRuntimeService(
            registry,
            new ObjectMapper(),
            properties,
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
    }

    private Map<String, Object> candidates(String incompatibleTemplate, String executableTemplate) {
        return Map.of(
            "schemaVersion", "template_query_result.v1",
            "returnedCount", 2,
            "templates", List.of(
                template(
                    incompatibleTemplate,
                    Map.of("accountId", Map.of("type", "string")),
                    List.of("accountId")
                ),
                template(
                    executableTemplate,
                    Map.of(
                        "customerId", Map.of("type", "string"),
                        "startDate", Map.of("type", "string", "default", "2026-08-01"),
                        "endDate", Map.of("type", "string", "defaultValue", "2026-08-04"),
                        "page", Map.of("type", "integer", "default", 1),
                        "pageSize", Map.of("type", "integer", "default_value", 50)
                    ),
                    List.of("customerId", "startDate", "endDate", "page", "pageSize")
                )
            )
        );
    }

    private Map<String, Object> template(String templateId,
                                         Map<String, Object> properties,
                                         List<String> required) {
        return Map.of(
            "templateId", templateId,
            "executionTool", "api_template_execute",
            "parameterSchema", Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false
            ),
            "requiredParameters", required
        );
    }

    private InterpretationPlan plan(String templateTool, String executorTool) {
        return plan(templateTool, executorTool, Map.of());
    }

    private InterpretationPlan plan(String templateTool,
                                    String executorTool,
                                    Map<String, Object> executorParameters) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Execute a usable governed query", "low"),
            new InterpretationPlan.Context(
                List.of(), List.of(), List.of(),
                List.of("Use discovered templates and execute when defaults close omitted inputs")
            ),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        templateTool,
                        Map.of("filters", Map.of("intent", "customer flow"), "limit", 10),
                        List.of(),
                        new InterpretationPlan.OutputContract("object", "template_query_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        executorTool,
                        Map.of("parameters", executorParameters == null ? Map.of() : executorParameters),
                        List.of(1),
                        new InterpretationPlan.OutputContract("object", "api_execution_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        3,
                        "final_answer",
                        "",
                        Map.of("answer", "Execution completed from governed evidence."),
                        List.of(2),
                        null,
                        null
                    )
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(templateTool, executorTool),
                List.of(),
                30_000
            ),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.95, 0.05, true, List.of()),
                List.of("Return an error only after evidence-backed execution paths are exhausted")
            )
        );
    }
}
