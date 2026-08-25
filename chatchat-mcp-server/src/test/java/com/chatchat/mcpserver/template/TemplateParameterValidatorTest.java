package com.chatchat.mcpserver.template;

import com.chatchat.common.knowledge.template.TemplateResolutionEventType;
import com.chatchat.common.knowledge.template.TemplateResolutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TemplateParameterValidatorTest {

    private final TemplateParameterValidator validator = new TemplateParameterValidator(new ObjectMapper());

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
