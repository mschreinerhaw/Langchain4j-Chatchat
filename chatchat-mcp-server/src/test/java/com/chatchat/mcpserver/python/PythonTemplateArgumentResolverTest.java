package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonTemplateArgumentResolverTest {
    private final PythonTemplateArgumentResolver resolver = new PythonTemplateArgumentResolver(new ObjectMapper());

    @Test
    void suppliedValuesOverrideDefaultsAndOmittedValuesKeepDefaults() {
        String schema = """
            {"type":"object","additionalProperties":false,
             "properties":{"region":{"type":"string","default":"CN"},"limit":{"type":"integer","default":10}},
             "required":["region","limit"]}
            """;

        assertThat(resolver.resolve(schema, Map.of("limit", 25, "ignored", "value")))
            .containsExactlyInAnyOrderEntriesOf(Map.of("region", "CN", "limit", 25));
    }

    @Test
    void onlyRequiredParameterWithoutDefaultBlocksExecution() {
        String schema = """
            {"type":"object","properties":{"customerId":{"type":"string"},"mode":{"type":"string","default":"safe"}},
             "required":["customerId","mode"]}
            """;

        assertThatThrownBy(() -> resolver.resolve(schema, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customerId")
            .hasMessageNotContaining("mode]");
    }
}
