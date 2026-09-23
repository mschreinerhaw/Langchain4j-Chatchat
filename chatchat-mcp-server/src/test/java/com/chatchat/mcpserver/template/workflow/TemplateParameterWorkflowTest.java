package com.chatchat.mcpserver.template.workflow;

import com.chatchat.common.knowledge.template.resolution.TemplateResolutionException;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateParameterWorkflowTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateParameterWorkflow workflow = new TemplateParameterWorkflow(
        new TemplateParameterValidator(objectMapper), objectMapper);

    @Test
    void resolvesExplicitRequestAndDefaultValuesWithProvenance() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "accountId": {"type": "string"},
                "market": {"type": "string"},
                "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 100}
              },
              "required": ["accountId", "market"]
            }
            """;

        TemplateParameterWorkflow.Resolution result = workflow.execute(
            new TemplateParameterWorkflow.Request(
                "account_positions",
                schema,
                Map.of("accountId", " A-100 "),
                Map.of("accountId", "ignored", "market", "CN", "undeclared", "drop")));

        assertThat(result.parameters())
            .containsEntry("accountId", "A-100")
            .containsEntry("market", "CN")
            .containsEntry("limit", 20)
            .doesNotContainKey("undeclared");
        assertThat(result.parameterSources())
            .containsEntry("accountId", "EXPLICIT_PARAMETERS")
            .containsEntry("market", "REQUEST_FIELD")
            .containsEntry("limit", "SCHEMA_DEFAULT");
        assertThat(result.traceSummary().toString())
            .contains("resolve.template-parameters.v1", "DECLARED_SCHEMA", "VALIDATE_REQUIRED_PARAMETERS");
    }

    @Test
    void preservesExplicitParametersWhenLegacyTemplateHasNoSchema() {
        TemplateParameterWorkflow.Resolution result = workflow.execute(
            new TemplateParameterWorkflow.Request(
                "legacy_query", null, Map.of("customerId", 7, "active", true), Map.of()));

        assertThat(result.parameters()).containsExactlyInAnyOrderEntriesOf(
            Map.of("customerId", 7, "active", true));
        assertThat(result.trace().mode()).isEqualTo("SCHEMALESS_PASSTHROUGH");
    }

    @Test
    void usesEligibleRequestFieldsForSchemalessApiTemplate() {
        TemplateParameterWorkflow.Resolution result = workflow.execute(
            new TemplateParameterWorkflow.Request(
                "legacy_api", "", Map.of(), Map.of("market", "SH", "page", 2)));

        assertThat(result.parameters()).containsExactlyInAnyOrderEntriesOf(
            Map.of("market", "SH", "page", 2));
        assertThat(result.parameterSources())
            .containsEntry("market", "REQUEST_FIELD")
            .containsEntry("page", "REQUEST_FIELD");
    }

    @Test
    void rejectsRequestWhenRequiredParameterCannotBeResolved() {
        String schema = """
            {"type":"object","properties":{"tradeDate":{"type":"string"}},"required":["tradeDate"]}
            """;

        assertThatThrownBy(() -> workflow.execute(new TemplateParameterWorkflow.Request(
            "daily_trade", schema, Map.of(), Map.of())))
            .isInstanceOf(TemplateResolutionException.class)
            .hasMessageContaining("tradeDate");
    }
}
