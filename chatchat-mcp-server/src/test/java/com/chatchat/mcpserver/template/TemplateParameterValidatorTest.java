package com.chatchat.mcpserver.template;

import com.chatchat.common.knowledge.template.resolution.TemplateResolutionEventType;
import com.chatchat.common.knowledge.template.resolution.TemplateResolutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TemplateParameterValidatorTest {

    private final TemplateParameterValidator validator = new TemplateParameterValidator(new ObjectMapper());

    @Test void invalidOptionalOverrideFallsBackOrIsOmittedAtTheExecutionBoundary() {
        var result=validator.validateDeclaredOnly("opaque", """
            {"type":"object","properties":{"limit":{"type":"integer","default":10,"maximum":20},
            "enabled":{"type":"boolean"},"day":{"type":"string","format":"date"}}}
            """,Map.of("limit",100,"enabled","perhaps","day","20260230","foreign","drop"),Map.of());
        assertThat(result).containsExactlyEntriesOf(Map.of("limit",10));
    }

    @Test void emptyPublishedSchemaDoesNotPassThroughUnknownValues() {
        assertThat(validator.validateDeclaredOnly("empty","{}",Map.of("foreign","drop"),Map.of())).isEmpty();
    }

    @Test void directRequiredDefaultsAreAppliedBeforeMissingChecks() {
        assertThat(validator.validate("defaulted","""
            {"type":"object","properties":{"enabled":{"type":"boolean","default":false}},"required":["enabled"]}
            """,Map.of())).containsEntry("enabled",false);
    }

    @Test void illegalDefaultReturnsAConfigurationErrorWithoutEchoingUserValues() {
        var failure=catchThrowableOfType(()->validator.validate("opaque","""
            {"type":"object","properties":{"size":{"type":"integer","default":"bad"}}}
            """,Map.of("size","private-invalid-value")),TemplateParameterValidator.ParameterValidationException.class);
        assertThat(failure.getMessage()).contains("INVALID_TEMPLATE_DEFAULT","size").doesNotContain("private-invalid-value");
        assertThat(failure.validationErrors()).singleElement().satisfies(error->assertThat(error.receivedValue()).isNull());
    }

    @Test
    void appliesSupportedSchemaDefaultSpellingsDuringDirectExecution() {
        Map<String, Object> parameters = validator.validateDeclaredOnly(
            "defaulted_query",
            """
                {
                  "type": "object",
                  "properties": {
                    "customerId": { "type": "string", "defaultValue": "C-DEFAULT" },
                    "tradeDate": { "type": "string", "default_value": "20260731" }
                  },
                  "required": ["customerId", "tradeDate"]
                }
                """,
            Map.of(),
            Map.of()
        );

        assertThat(parameters)
            .containsEntry("customerId", "C-DEFAULT")
            .containsEntry("tradeDate", "20260731");
    }

    @Test
    void reportsAllMissingParametersAsOneStructuredResolutionEvent() {
        TemplateResolutionException failure = catchThrowableOfType(
            () -> validator.validate("customer_profile_v1", """
                {"type":"object","properties":{"customerId":{"type":"string"},
                "region":{"type":"string"}},"required":["customerId","region"]}
                """, Map.of()),
            TemplateResolutionException.class);

        assertThat(failure.event().type()).isEqualTo(TemplateResolutionEventType.TEMPLATE_PARAMETERS_MISSING);
        assertThat(failure.event().templateId()).isEqualTo("customer_profile_v1");
        assertThat(failure.event().missingParameters()).containsExactly("customerId", "region");
    }
}
