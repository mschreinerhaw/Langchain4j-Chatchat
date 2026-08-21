package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void omittedParameterWithoutSchemaDefaultIsLeftToTheRunningScript() {
        String schema = """
            {"type":"object","properties":{"customerId":{"type":"string"},"mode":{"type":"string","default":"safe"}},
             "required":["customerId","mode"]}
            """;

        assertThat(resolver.resolve(schema, Map.of()))
            .containsExactlyInAnyOrderEntriesOf(Map.of("mode", "safe"))
            .doesNotContainKey("customerId");
    }

    @Test
    void parsesStringValuesAccordingToThePublishedInputSchema() {
        String schema = """
            {"type":"object","properties":{
              "limit":{"type":"integer"},"ratio":{"type":"number"},"enabled":{"type":"boolean"},
              "columns":{"type":"array"},"filters":{"type":"object"},"source_file":{"type":"FILE"}
            }}
            """;

        Map<String, Object> result = resolver.resolve(schema, Map.of(
            "limit", "25", "ratio", "1.5", "enabled", "true",
            "columns", "[\"name\",\"amount\"]", "filters", "{\"region\":\"CN\"}",
            "source_file", "file_123"
        ));

        assertThat(result.get("limit")).isEqualTo(25L);
        assertThat(result.get("ratio")).isEqualTo(1.5d);
        assertThat(result.get("enabled")).isEqualTo(true);
        assertThat(result.get("columns")).isEqualTo(List.of("name", "amount"));
        assertThat(result.get("filters")).isEqualTo(Map.of("region", "CN"));
        assertThat(result.get("source_file")).isEqualTo("file_123");
    }

    @Test
    void rejectsValuesThatCannotBeParsedAsTheDeclaredType() {
        String schema = "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}";

        assertThatThrownBy(() -> resolver.resolve(schema, Map.of("limit", "many")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit")
            .hasMessageContaining("整数");
    }
}
