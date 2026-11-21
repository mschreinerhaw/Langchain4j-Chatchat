package com.chatchat.api.runtime.coding.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NativePublishedTemplateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void extractsSqlRowsFromTheGovernedPublishedToolEnvelope() throws Exception {
        var data = NativePublishedTemplate.sqlData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{"success":true,"data":{
              "dataSchema":"sql_result.v1","data":{"complete":true,"rowCount":1,"rows":[{"warehouse":3}]}}},
              "rawData":{"rows":[{"warehouse":9}]}}
            """);
        assertThat(data.path("rows").get(0).path("warehouse").asInt()).isEqualTo(3);
        assertThat(data.path("complete").asBoolean()).isTrue();
    }

    @Test void doesNotReadRawOrMismatchedSqlRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{},"rawData":{"rowCount":1,"rows":[{}]}}
            """));
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"data":{"rowCount":2,"rows":[{}]}}
            """));
    }
}
