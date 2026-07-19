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
        assertThat(body.path("sessionId").asText()).isEqualTo("session-from-gateway");
        assertThat(body.path("namespace").asText()).isEqualTo("livedata");
        assertThat(body.path("head").path("x-ams-token").asText()).isEqualTo("token-from-gateway");
        var headers = new ObjectMapper().readTree(mapped.getHeadersJson());
        assertThat(headers.path("sessionId").asText()).isEqualTo("session-from-gateway");
        assertThat(headers.path("x-ams-token").asText()).isEqualTo("token-from-gateway");
        assertThat(headers.path("Content-Type").asText()).isEqualTo("application/json;charset=UTF-8");
    }
}
