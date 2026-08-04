package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.tools.livedata.LivedataApiDefinition;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LivedataApiConfigMapperTest {

    @Test
    void mapsApiWithoutAmsTokenWhenTokenParameterIsNotExposed() throws Exception {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setServiceBaseUrl("http://192.168.195.224:5006");
        properties.setAmsToken(null);
        properties.setExposeAmsTokenParameter(false);
        LivedataApiConfigMapper mapper = new LivedataApiConfigMapper(new ObjectMapper(), () -> properties);
        LivedataApiDefinition definition = new LivedataApiDefinition(
            "source-1", "edayQuqtMoni", "edayQuqtMoni", "{}", null, null,
            "com.apex.livedata.edayQuqtMoni", "call", 0, "1", "1");

        ApiServiceConfig mapped = mapper.toApiServiceConfig(definition);

        assertThat(mapped.getToolName()).isEqualTo("livedata_edayQuqtMoni");
        assertThat(mapped.getUrlTemplate())
            .isEqualTo("http://192.168.195.224:5006/service/com.apex.livedata.edayQuqtMoni/call");
        assertThat(new ObjectMapper().readTree(mapped.getBodyTemplate()).path("head").path("x-ams-token").asText())
            .isEmpty();
    }

    @Test
    void generatedGatewayInheritsLivedataCredentialsFromSelectedGateway() throws Exception {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setServiceBaseUrl("http://localhost:5006");
        properties.setDefaultNamespace("fallback");
        LivedataApiConfigMapper mapper = new LivedataApiConfigMapper(new ObjectMapper(), () -> properties);
        LivedataApiDefinition definition = new LivedataApiDefinition(
            "source-1", "edayQuqtMoni", "edayQuqtMoni", "{}", null, null,
            "com.apex.livedata.edayQuqtMoni", "call", 0, "1", "1");
        HttpEndpointConfig selectedGateway = new HttpEndpointConfig();
        selectedGateway.setHeadersJson("""
            {
              "sessionId": "session-from-gateway",
              "x-ams-token": "token-from-gateway",
              "namespace": "livedata"
            }
            """);

        HttpEndpointConfig mapped = mapper.toGatewayConfig(definition, selectedGateway);

        var body = new ObjectMapper().readTree(mapped.getBodyTemplate());
        assertThat(body.path("sessionId").asText()).isEqualTo("{{__livedata_session_id}}");
        assertThat(body.path("namespace").asText()).isEqualTo("livedata");
        assertThat(body.path("head").path("x-ams-token").asText()).isEqualTo("token-from-gateway");
        var headers = new ObjectMapper().readTree(mapped.getHeadersJson());
        assertThat(headers.has("sessionId")).isFalse();
        assertThat(headers.has("namespace")).isFalse();
        assertThat(headers.path("x-ams-token").asText()).isEqualTo("token-from-gateway");
        assertThat(headers.path("Content-Type").asText()).isEqualTo("application/json;charset=UTF-8");
    }

    @Test
    void mapsLivedataKeyAsParameterNameAndPreservesLabelAndDefault() throws Exception {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setServiceBaseUrl("http://localhost:5006");
        LivedataApiConfigMapper mapper = new LivedataApiConfigMapper(new ObjectMapper(), () -> properties);
        LivedataApiDefinition definition = new LivedataApiDefinition(
            "736941719500824576",
            "cx_ds_by_tab",
            "cx_ds_by_tab",
            """
                [{"key":"tab_name","name":"表名","type":"string","value":"TJGMXLS","isRequire":0,"description":""}]
                """,
            null,
            "livedata",
            "com.apex.livedata.cx_ds_by_tab",
            "call",
            0,
            "1",
            "1"
        );

        HttpEndpointConfig gateway = mapper.toGatewayConfig(definition);
        ApiServiceConfig service = mapper.toApiServiceConfig(definition);

        var body = new ObjectMapper().readTree(gateway.getBodyTemplate());
        assertThat(body.path("data").path("tab_name").asText()).isEqualTo("{{tab_name}}");
        var schema = new ObjectMapper().readTree(service.getInputSchemaJson());
        assertThat(schema.path("properties").path("tab_name").path("description").asText()).isEqualTo("表名");
        assertThat(schema.path("properties").path("tab_name").path("default").asText()).isEqualTo("TJGMXLS");
        assertThat(schema.path("required").isEmpty()).isTrue();
    }

    @Test
    void mapsResponseColumnsToApiServiceOutputSchema() throws Exception {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setServiceBaseUrl("http://localhost:5006");
        LivedataApiConfigMapper mapper = new LivedataApiConfigMapper(new ObjectMapper(), () -> properties);
        LivedataApiDefinition definition = new LivedataApiDefinition(
            "source-1", "orders", "Order query", "[]", null, "livedata",
            "OrderService", "query", 0, "1", "1",
            """
                [{"key":"order_id","name":"Order ID","type":"varchar","isRequire":1},
                 {"fieldName":"total_amount","description":"Order total","dataType":"decimal"}]
                """
        );

        ApiServiceConfig mapped = mapper.toApiServiceConfig(definition);

        var schema = new ObjectMapper().readTree(mapped.getOutputSchemaJson());
        assertThat(schema.path("properties").path("order_id").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("order_id").path("description").asText()).isEqualTo("Order ID");
        assertThat(schema.path("properties").path("total_amount").path("type").asText()).isEqualTo("number");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("order_id");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void leavesOutputSchemaEmptyWhenResponseColumnsAreEmptyOrMalformed() {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setServiceBaseUrl("http://localhost:5006");
        LivedataApiConfigMapper mapper = new LivedataApiConfigMapper(new ObjectMapper(), () -> properties);

        for (String responseColumns : new String[] { null, "", "not-json", "[]" }) {
            LivedataApiDefinition definition = new LivedataApiDefinition(
                "source-1", "orders", "Order query", "[]", null, "livedata",
                "OrderService", "query", 0, "1", "1", responseColumns
            );

            assertThat(mapper.toApiServiceConfig(definition).getOutputSchemaJson()).isNull();
        }
    }
}
