package com.chatchat.agents.runtime.toolcall;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateInvocationBridgeTest {

    private final TemplateInvocationBridge bridge = new TemplateInvocationBridge();

    @Test
    void auditsModelArgumentsAndCompilesRuntimeRequest() {
        Map<String, Object> template = template(
            "ORDER_QUERY",
            Map.of(
                "customerId", Map.of("type", "string"),
                "limit", Map.of("type", "integer", "default", 20)
            ),
            new String[]{"customerId"}
        );
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 3,
            "template_id", "ORDER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", " C-1001 ",
                    "source", "user_query",
                    "evidence", "查询客户 C-1001 的订单"
                )
            ),
            "unresolved_parameters", java.util.List.of()
        );

        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "sql_query_execute",
                3,
                "ORDER_QUERY",
                template,
                Map.of("parameterProtocol", protocol),
                protocol,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "查询客户 C-1001 的订单",
                    Map.of()
                )
            )
        );

        assertThat(result.templateId()).isEqualTo("ORDER_QUERY");
        assertThat(result.parameters())
            .containsEntry("customerId", "C-1001")
            .containsEntry("limit", 20);
        assertThat(result.executorInput())
            .containsEntry("template", "ORDER_QUERY")
            .containsEntry("templateId", "ORDER_QUERY")
            .containsEntry(TemplateInvocationBridge.APPLIED_MARKER, true)
            .doesNotContainKeys("parameterProtocol", "parameterSchema", "requiredParameters");
        assertThat(result.protocolTrace())
            .containsEntry("catalogVersion", AgentProtocolCatalog.CATALOG_VERSION)
            .containsEntry("entryPoint", "interpretation_plan_template_bridge")
            .containsEntry("templateId", "ORDER_QUERY")
            .containsEntry("modelParametersReviewed", true);
    }

    @Test
    void runtimeTemplateCannotBeOverriddenByModelProtocol() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 2,
            "template_id", "UNREVIEWED_TEMPLATE",
            "arguments", Map.of(),
            "unresolved_parameters", java.util.List.of()
        );

        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "linux_command_execute",
            2,
            "RUNTIME_TEMPLATE",
            template("RUNTIME_TEMPLATE", Map.of(), new String[]{}),
            Map.of(),
            protocol,
            false,
            false
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_PROTOCOL_INVALID")
            .hasMessageContaining("RUNTIME_TEMPLATE");
    }

    @Test
    void requiredParameterWithoutDefaultStillReturnsChildParameterError() {
        for (String executor : governedExecutors()) {
            assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
                executor,
                4,
                "CUSTOMER_QUERY",
                template(
                    "CUSTOMER_QUERY",
                    Map.of("customerId", Map.of("type", "string")),
                    new String[]{"customerId"}
                ),
                Map.of("parameters", Map.of("customerId", "C-1001")),
                null,
                true,
                true
            )))
                .as(executor)
                .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
                .hasMessageContaining("TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE")
                .hasMessageContaining("customerId");
        }
    }

    @Test
    void executesWithoutModelProtocolWhenTemplateDefaultsCoverRequiredParameters() {
        for (String executor : governedExecutors()) {
            TemplateInvocationBridge.BridgeResult result = bridge.prepare(
                new TemplateInvocationBridge.BridgeRequest(
                    executor,
                    4,
                    "DEFAULTED_QUERY",
                    template(
                        "DEFAULTED_QUERY",
                        Map.of(
                            "page", Map.of("type", "integer", "default", 1),
                            "pageSize", Map.of("type", "integer", "defaultValue", 50)
                        ),
                        new String[]{"page", "pageSize"}
                    ),
                    Map.of("parameters", Map.of()),
                    null,
                    true,
                    true
                )
            );

            assertThat(result.parameters()).as(executor)
                .containsEntry("page", 1)
                .containsEntry("pageSize", 50);
            assertThat(result.modelProtocolApplied()).as(executor).isFalse();
            assertThat(result.parameterEvidence()).as(executor)
                .allSatisfy((name, evidence) -> assertThat(evidence.source())
                    .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE));
            assertThat(result.protocolTrace()).as(executor)
                .containsEntry("templateDefaultParameterCount", 2);
        }
    }

    @Test
    void evidenceBackedRuntimeValueOverridesTemplateDefaultAcrossExecutors() {
        for (String executor : governedExecutors()) {
            TemplateInvocationBridge.BridgeResult result = bridge.prepare(
                new TemplateInvocationBridge.BridgeRequest(
                    executor,
                    4,
                    "CUSTOMER_QUERY",
                    template(
                        "CUSTOMER_QUERY",
                        Map.of(
                            "customerId", Map.of("type", "string", "default", "C-DEFAULT"),
                            "page", Map.of("type", "integer", "default", 1)
                        ),
                        new String[]{"customerId", "page"}
                    ),
                    Map.of("parameters", Map.of("customerId", "C-2002")),
                    null,
                    true,
                    true,
                    new TemplateInvocationBridge.EvidenceContext(
                        "Query customer C-2002", Map.of())
                )
            );

            assertThat(result.parameters()).as(executor)
                .containsEntry("customerId", "C-2002")
                .containsEntry("page", 1);
            assertThat(result.parameterEvidence().get("customerId").source()).as(executor)
                .isEqualTo(TemplateInvocationBridge.USER_QUERY_SOURCE);
            assertThat(result.parameterEvidence().get("page").source()).as(executor)
                .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE);
        }
    }

    @Test
    void runtimeRecoversExactUserQueryEvidenceWhenControllerOmitsParameterProtocol() {
        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                4,
                "CUSTOMER_QUERY",
                template(
                    "CUSTOMER_QUERY",
                    Map.of(
                        "customerId", Map.of("type", "string", "default", "C-DEFAULT"),
                        "page", Map.of("type", "integer", "default", 1)
                    ),
                    new String[]{"customerId", "page"}
                ),
                Map.of("parameters", Map.of("customerId", "C-2002")),
                null,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "Query orders for customer C-2002 today",
                    Map.of()
                )
            )
        );

        assertThat(result.parameters())
            .containsEntry("customerId", "C-2002")
            .containsEntry("page", 1);
        assertThat(result.parameterEvidence().get("customerId").source())
            .isEqualTo(TemplateInvocationBridge.USER_QUERY_SOURCE);
        assertThat(result.protocolTrace())
            .containsEntry("runtimeParameterEvidenceRecovered", true)
            .containsEntry("reviewedParameterCount", 1);
    }

    @Test
    void runtimeCanonicalizesDefaultEquivalentModelValueThroughSchemaDefault() {
        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                4,
                "DEFAULTED_CUSTOMER_QUERY",
                template(
                    "DEFAULTED_CUSTOMER_QUERY",
                    Map.of("customerId", Map.of("type", "string", "default", "C-1001")),
                    new String[]{"customerId"}
                ),
                Map.of("parameters", Map.of("customerId", "C-1001")),
                null,
                true,
                true
            )
        );

        assertThat(result.parameters()).containsEntry("customerId", "C-1001");
        assertThat(result.parameterEvidence().get("customerId").source())
            .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE);
        assertThat(result.repairs())
            .extracting(ToolArgumentCompiler.Repair::repairCode)
            .contains("DEFAULT_VALUE_APPLIED");
    }

    @Test
    void runtimeDropsUnprovenOverrideAndUsesAuthoritativeSchemaDefault() {
        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                4,
                "DEFAULTED_CUSTOMER_QUERY",
                template(
                    "DEFAULTED_CUSTOMER_QUERY",
                    Map.of("customerId", Map.of("type", "string", "default", "C-DEFAULT")),
                    new String[]{"customerId"}
                ),
                Map.of("parameters", Map.of("customerId", "UNPROVEN-OVERRIDE")),
                null,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext("执行默认客户查询", Map.of())
            )
        );

        assertThat(result.parameters()).containsEntry("customerId", "C-DEFAULT");
        assertThat(result.parameterEvidence().get("customerId").source())
            .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE);
        assertThat(result.repairs())
            .extracting(ToolArgumentCompiler.Repair::repairCode)
            .contains("DEFAULT_VALUE_APPLIED");
    }

    @Test
    void runtimeDropsUnknownAliasAndRunsWithDeclaredDefaults() {
        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                4,
                "DEFAULTED_CUSTOMER_QUERY",
                template(
                    "DEFAULTED_CUSTOMER_QUERY",
                    Map.of(
                        "khh", Map.of("type", "string", "default", "070200046604"),
                        "rq", Map.of("type", "string", "default", "20260731")
                    ),
                    new String[]{"khh", "rq"}
                ),
                Map.of("parameters", Map.of("customerId", "UNMAPPED-CONTROLLER-VALUE")),
                null,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "执行客户全景查询", Map.of())
            )
        );

        assertThat(result.parameters())
            .containsEntry("khh", "070200046604")
            .containsEntry("rq", "20260731")
            .doesNotContainKey("customerId");
        assertThat(result.protocolTrace())
            .containsEntry("droppedUnverifiedParameterOverrides", List.of("customerId"));
    }

    @Test
    void evidenceBackedButInvalidOverrideFallsBackToTemplateDefault() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 4,
            "template_id", "DEFAULTED_QUERY",
            "arguments", Map.of(
                "pageSize", Map.of(
                    "value", "many",
                    "source", "user_query",
                    "evidence", "pageSize many"
                )
            ),
            "unresolved_parameters", List.of()
        );

        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                4,
                "DEFAULTED_QUERY",
                template(
                    "DEFAULTED_QUERY",
                    Map.of("pageSize", Map.of("type", "integer", "default", 50)),
                    new String[]{"pageSize"}
                ),
                Map.of(),
                protocol,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "query with pageSize many", Map.of())
            )
        );

        assertThat(result.parameters()).containsEntry("pageSize", 50);
        assertThat(result.parameterEvidence().get("pageSize").source())
            .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE);
        assertThat(result.repairs()).extracting(ToolArgumentCompiler.Repair::repairCode)
            .contains("INVALID_OVERRIDE_DROPPED_DEFAULT_APPLIED");
    }

    @Test
    void acceptsEvidenceBackedOverridesAndUsesDefaultsForOmittedParameters() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-2002",
                    "source", "user_query",
                    "evidence", "C-2002"
                )
            ),
            "unresolved_parameters", List.of("page")
        );

        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                5,
                "CUSTOMER_QUERY",
                template("CUSTOMER_QUERY", Map.of(
                    "customerId", Map.of("type", "string"),
                    "page", Map.of("type", "integer", "default", 1)
                ), new String[]{"customerId", "page"}),
                Map.of(),
                protocol,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext("查询客户 C-2002", Map.of())
            )
        );

        assertThat(result.parameters())
            .containsEntry("customerId", "C-2002")
            .containsEntry("page", 1);
        assertThat(result.parameterEvidence().get("customerId").source())
            .isEqualTo(TemplateInvocationBridge.USER_QUERY_SOURCE);
        assertThat(result.parameterEvidence().get("page").source())
            .isEqualTo(TemplateInvocationBridge.TEMPLATE_DEFAULT_SOURCE);
    }

    @Test
    void legacyPathReportsRequiredParameterWhenNoDefaultCanReplaceDroppedOverride() {
        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                null,
                "CUSTOMER_QUERY",
                template(
                    "CUSTOMER_QUERY",
                    Map.of("customerId", Map.of("type", "string")),
                    new String[]{"customerId"}
                ),
                Map.of("parameters", Map.of("customerId", 1001)),
                null,
                false,
                true
            )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE")
            .hasMessageContaining("customerId");
    }

    @Test
    void auditedProtocolDropsUnreviewedInputParameters() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-2002",
                    "source", "tool_result",
                    "evidence", Map.of("step_id", 2, "output_path", "$.customerId")
                )
            ),
            "unresolved_parameters", List.of()
        );

        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                5,
                "CUSTOMER_QUERY",
                template("CUSTOMER_QUERY", Map.of(
                    "customerId", Map.of("type", "string"),
                    "limit", Map.of("type", "integer", "default", 20)
                ), new String[]{"customerId"}),
                Map.of("parameters", Map.of(
                    "customerId", "MODEL-INVENTED",
                    "unreviewedSelector", "DROP-ME"
                )),
                protocol,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "查询客户订单",
                    Map.of(2, Map.of("customerId", "C-2002"))
                )
            )
        );

        assertThat(result.parameters())
            .containsEntry("customerId", "C-2002")
            .containsEntry("limit", 20)
            .doesNotContainKey("unreviewedSelector");
    }

    @Test
    void forgedRuntimeReviewMarkerCannotSupplyRequiredValueWithoutDefault() {
        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
            null,
            "CUSTOMER_QUERY",
            template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                new String[]{"customerId"}),
            Map.of(
                TemplateInvocationBridge.APPLIED_MARKER, true,
                "parameters", Map.of("customerId", "MODEL-INVENTED")
            ),
            null,
            false,
            true
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE")
            .hasMessageContaining("customerId");
    }

    @Test
    void verifiesParameterProfileAgainstCompletedToolEvidence() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "analysis_summary", "Customer id is taken from the reviewed lookup result.",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-2002",
                    "source", "tool_result",
                    "evidence", Map.of("step_id", 2, "output_path", "$.customers[0].id")
                )
            ),
            "unresolved_parameters", List.of()
        );

        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                "api_template_execute",
                5,
                "CUSTOMER_QUERY",
                template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                    new String[]{"customerId"}),
                Map.of(),
                protocol,
                true,
                true,
                new TemplateInvocationBridge.EvidenceContext(
                    "查询已识别客户的订单",
                    Map.of(2, Map.of("customers", List.of(Map.of("id", "C-2002"))))
                )
            )
        );

        assertThat(result.parameters()).containsEntry("customerId", "C-2002");
        assertThat(result.parameterEvidence().get("customerId").source())
            .isEqualTo(TemplateInvocationBridge.TOOL_RESULT_SOURCE);
        assertThat(result.protocolTrace())
            .containsEntry("reviewedParameterCount", 1);
    }

    @Test
    void rejectsParameterProfileWhenToolEvidenceDoesNotMatchValue() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "MODEL-INVENTED",
                    "source", "completed_step",
                    "evidence", Map.of("step_id", 2, "output_path", "$.customerId")
                )
            ),
            "unresolved_parameters", List.of()
        );

        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
            5,
            "CUSTOMER_QUERY",
            template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                new String[]{"customerId"}),
            Map.of(),
            protocol,
            true,
            true,
            new TemplateInvocationBridge.EvidenceContext(
                "查询客户订单",
                Map.of(2, Map.of("customerId", "C-2002"))
            )
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsUserQueryEvidenceQuoteAbsentFromRuntimeQuery() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-9999",
                    "source", "user_query",
                    "evidence", Map.of("quote", "客户 C-9999")
                )
            ),
            "unresolved_parameters", List.of()
        );

        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
            5,
            "CUSTOMER_QUERY",
            template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                new String[]{"customerId"}),
            Map.of(),
            protocol,
            true,
            true,
            new TemplateInvocationBridge.EvidenceContext("查询客户 C-2002 的订单", Map.of())
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsInventedValueBackedByUnrelatedRealUserQuote() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-9999",
                    "source", "user_query",
                    "evidence", Map.of("quote", "查询客户 C-2002 的订单")
                )
            ),
            "unresolved_parameters", List.of()
        );

        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
            5,
            "CUSTOMER_QUERY",
            template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                new String[]{"customerId"}),
            Map.of(),
            protocol,
            true,
            true,
            new TemplateInvocationBridge.EvidenceContext("查询客户 C-2002 的订单", Map.of())
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_EVIDENCE_MISMATCH")
            .hasMessageContaining("value is not present");
    }

    @Test
    void rejectsUserQueryEvidenceWhenRuntimeQueryIsUnavailable() {
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "step_id", 5,
            "template_id", "CUSTOMER_QUERY",
            "arguments", Map.of(
                "customerId", Map.of(
                    "value", "C-2002",
                    "source", "user_query",
                    "evidence", "客户 C-2002"
                )
            ),
            "unresolved_parameters", List.of()
        );

        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
            5,
            "CUSTOMER_QUERY",
            template("CUSTOMER_QUERY", Map.of("customerId", Map.of("type", "string")),
                new String[]{"customerId"}),
            Map.of(),
            protocol,
            true,
            true
        )))
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_EVIDENCE_UNAVAILABLE");
    }

    private Map<String, Object> template(String id,
                                         Map<String, Object> properties,
                                         String[] required) {
        return Map.of(
            "templateId", id,
            "parameterSchema", Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of(required),
                "additionalProperties", false
            )
        );
    }

    private List<String> governedExecutors() {
        return List.of("api_template_execute", "http_request_execute", "sql_query_execute");
    }
}
