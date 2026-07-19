package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiServiceConfig;
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
}
