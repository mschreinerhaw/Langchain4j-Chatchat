package com.chatchat.agents.runtime.toolcall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateExecutionContractSelectorTest {

    private final TemplateExecutionContractSelector selector = new TemplateExecutionContractSelector();

    @Test
    void selectsOnlyTheRequestedTemplateWithAMatchingExecutorAndUsableSchema() {
        Map<String, Object> selected = template("customer-assets", "sql_query_execute",
            Map.of("type", "object", "properties", Map.of("customerId", Map.of("type", "string")),
                "required", List.of("customerId")));
        TemplateExecutionContractSelector.Selection result = selector.select(
            List.of(selected, template("wrong", "api_template_execute", Map.of())),
            "customer-assets", "mcp_runtime_12345678_sql_query_execute", null);

        assertThat(result.selected()).isTrue();
        assertThat(result.templateId()).isEqualTo("customer-assets");
        assertThat(result.rejections()).extracting(TemplateExecutionContractSelector.CandidateRejection::code)
            .contains("TEMPLATE_EXECUTOR_MISMATCH");
    }

    @Test
    void rejectsAnExactIdWhenItsExecutorDoesNotMatch() {
        TemplateExecutionContractSelector.Selection result = selector.select(
            List.of(template("customer-assets", "api_template_execute", Map.of())),
            "customer-assets", "sql_query_execute", null);

        assertThat(result.selected()).isFalse();
        assertThat(result.code()).isEqualTo("TEMPLATE_EXECUTION_CONTRACT_REJECTED");
        assertThat(result.rejections()).singleElement()
            .extracting(TemplateExecutionContractSelector.CandidateRejection::code)
            .isEqualTo("TEMPLATE_EXECUTOR_MISMATCH");
    }

    @Test
    void replacesAnInventedHintOnlyWhenDiscoveryHasOneExecutableContract() {
        TemplateExecutionContractSelector.Selection result = selector.select(
            List.of(template("runtime-authorized", "sql_query_execute", Map.of())),
            "model-invented", "sql_query_execute", null);

        assertThat(result.selected()).isTrue();
        assertThat(result.templateId()).isEqualTo("runtime-authorized");
    }

    @Test
    void rejectsAmbiguousExecutableCandidatesInsteadOfPickingTheFirst() {
        TemplateExecutionContractSelector.Selection result = selector.select(
            List.of(template("one", "sql_query_execute", Map.of()),
                template("two", "sql_query_execute", Map.of())),
            null, "sql_query_execute", null);

        assertThat(result.selected()).isFalse();
        assertThat(result.code()).isEqualTo("TEMPLATE_SELECTION_AMBIGUOUS");
        assertThat(result.executableCandidateCount()).isEqualTo(2);
    }

    @Test
    void acceptsEnvelopeExecutorButRejectsMalformedParameterSchema() {
        Map<String, Object> valid = Map.of("templateId", "valid", "parameterSchema",
            Map.of("type", "object", "properties", Map.of()));
        Map<String, Object> invalid = Map.of("templateId", "invalid", "parameterSchema",
            Map.of("type", "object", "properties", List.of(), "required", List.of("customerId")));

        TemplateExecutionContractSelector.Selection validResult = selector.select(
            List.of(valid), null, "sql_query_execute", "sql_query_execute");
        TemplateExecutionContractSelector.Selection invalidResult = selector.select(
            List.of(invalid), null, "sql_query_execute", "sql_query_execute");

        assertThat(validResult.selected()).isTrue();
        assertThat(invalidResult.selected()).isFalse();
        assertThat(invalidResult.rejections()).singleElement()
            .extracting(TemplateExecutionContractSelector.CandidateRejection::code)
            .isEqualTo("TEMPLATE_PARAMETER_SCHEMA_INVALID");
    }

    @Test
    void rejectsDisabledTemplates() {
        Map<String, Object> disabled = Map.of(
            "templateId", "disabled-template",
            "executionTool", "sql_query_execute",
            "enabled", true,
            "executable", false);

        TemplateExecutionContractSelector.Selection result = selector.select(
            List.of(disabled), "disabled-template", "sql_query_execute", null);

        assertThat(result.selected()).isFalse();
        assertThat(result.rejections()).singleElement()
            .extracting(TemplateExecutionContractSelector.CandidateRejection::code)
            .isEqualTo("TEMPLATE_NOT_EXECUTABLE");
    }

    private Map<String, Object> template(String id, String executor, Map<String, Object> schema) {
        return schema.isEmpty()
            ? Map.of("templateId", id, "executionTool", executor)
            : Map.of("templateId", id, "executionTool", executor, "parameterSchema", schema);
    }
}
