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
                true
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
    void runtimeRequiresModelProtocolWhenSelectedTemplateHasRequiredArguments() {
        assertThatThrownBy(() -> bridge.prepare(new TemplateInvocationBridge.BridgeRequest(
            "api_template_execute",
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
            .isInstanceOf(TemplateInvocationBridge.TemplateBridgeException.class)
            .hasMessageContaining("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED");
    }

    @Test
    void legacyPathStillUsesSameSchemaCompilerWithoutModelProtocol() {
        TemplateInvocationBridge.BridgeResult result = bridge.prepare(
            new TemplateInvocationBridge.BridgeRequest(
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
            )
        );

        assertThat(result.parameters()).containsEntry("customerId", "1001");
        assertThat(result.modelProtocolApplied()).isFalse();
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
}
