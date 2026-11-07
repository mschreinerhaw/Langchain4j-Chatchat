package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.config.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeModelConfigurationTest {
    @Test void freezesInferenceIdentityWithoutBindingCredentialsOrTransportTimeout(){
        var config=new ModelsConfig();var connection=new ModelsConfig.ModelConnectionConfig();
        connection.setModelName("provider-model");connection.setProtocol("openai");
        connection.setBaseUrl("https://test-user:test-value@example.invalid/v1?token=test-a");connection.setApiKey("test-a");
        config.getChatModels().put("selected",connection);var registry=new ModelResourceRegistry(config);
        String original=NativeModelConfiguration.fingerprint(registry,"selected");
        connection.setBaseUrl("https://other-user:other-test@example.invalid/v1?token=test-b");connection.setApiKey("test-b");connection.setRequestTimeoutSeconds(600);
        assertThat(NativeModelConfiguration.fingerprint(registry,"selected")).isEqualTo(original);
        connection.setModelName("different-provider-model");
        assertThat(NativeModelConfiguration.fingerprint(registry,"selected")).isNotEqualTo(original);
        connection.setModelName("provider-model");connection.setMaxRetries(connection.getMaxRetries()+1);
        assertThat(NativeModelConfiguration.fingerprint(registry,"selected")).isNotEqualTo(original);
    }
}
