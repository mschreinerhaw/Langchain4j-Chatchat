package com.chatchat.mcpserver.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
