package com.chatchat.agents.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProtocolCatalogTest {

    @Test
    void exposesOwnedCrossLayerProtocolsFromOneEntryPoint() {
        assertThat(AgentProtocolCatalog.current())
            .containsKeys(
                AgentProtocolCatalog.INTERPRETATION_EXECUTION,
                AgentProtocolCatalog.TEMPLATE_PARAMETER,
                AgentProtocolCatalog.RUNTIME_TEMPLATE_BINDING,
                AgentProtocolCatalog.RUNTIME_DEPENDENCY_EVIDENCE,
                AgentProtocolCatalog.TARGET_FILTERS,
                AgentProtocolCatalog.ROUTING_TRACE,
                AgentProtocolCatalog.RUNTIME_ARGUMENT_RESOLUTION,
                AgentProtocolCatalog.RUNTIME_ANSWER_CANDIDATE
            )
            .allSatisfy((id, descriptor) -> {
                assertThat(descriptor.id()).isEqualTo(id);
                assertThat(descriptor.owner()).isNotBlank();
                assertThat(descriptor.flow()).contains("->");
                assertThat(descriptor.purpose()).isNotBlank();
            });
    }

    @Test
    void createsReplayableProtocolTraceForTemplateBridge() {
        Map<String, Object> trace = AgentProtocolCatalog.trace(
            "interpretation_plan_template_bridge",
            "ORDER_QUERY",
            "sql_query_execute",
            true
        );

        assertThat(trace)
            .containsEntry("catalogVersion", AgentProtocolCatalog.CATALOG_VERSION)
            .containsEntry("executionProtocol", AgentProtocolCatalog.INTERPRETATION_EXECUTION)
            .containsEntry("parameterProtocol", AgentProtocolCatalog.TEMPLATE_PARAMETER)
            .containsEntry("templateBindingProtocol", AgentProtocolCatalog.RUNTIME_TEMPLATE_BINDING)
            .containsEntry("templateId", "ORDER_QUERY")
            .containsEntry("executorTool", "sql_query_execute")
            .containsEntry("modelParametersReviewed", true);
    }
}
