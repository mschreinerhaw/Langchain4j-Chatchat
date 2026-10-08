package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.execution.QueryTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QueryTemplatesTest {
    @Test void sqlValuesCannotBecomeExecutableFragments() {
        assertThat(QueryTemplates.sql("SELECT * FROM t WHERE name = {{name}} AND id = {{id}}",
            Map.of("name", "O'Reilly", "id", 42))).isEqualTo("SELECT * FROM t WHERE name = 'O''Reilly' AND id = 42");
        assertThatThrownBy(() -> QueryTemplates.sql("SELECT {{value}}", Map.of("value", "\\' UNION SELECT password")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryTemplates.sql("SELECT {{value}}", Map.of("value", List.of(1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryTemplates.sql("SELECT {{missing}}", Map.of())).hasMessageContaining("Missing");
    }
    @Test void jsonSubstitutionsPreserveArraysNumbersAndEscapedText() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var template = json.readTree("{\"query\":{\"knn\":{\"embedding\":{\"vector\":\"{{vector}}\",\"k\":\"{{k}}\"}}},\"filter\":\"{{text}}\"}");
        var result = QueryTemplates.json(template, Map.of("vector", List.of(0.1, 0.2), "k", 3, "text", "\"},\"match_all\":{}"), json);
        assertThat(result.at("/query/knn/embedding/vector").isArray()).isTrue();
        assertThat(result.at("/query/knn/embedding/k").intValue()).isEqualTo(3);
        assertThat(result.get("filter").isTextual()).isTrue();
        assertThat(template.at("/query/knn/embedding/vector").isTextual()).isTrue();
        assertThatThrownBy(() -> QueryTemplates.json(template, Map.of(), json)).hasMessageContaining("Missing");
    }
}
