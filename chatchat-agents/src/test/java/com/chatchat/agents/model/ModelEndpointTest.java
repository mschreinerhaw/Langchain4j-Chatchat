package com.chatchat.agents.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelEndpointTest {

    @Test
    void detectsWorkspaceMultimodalGenerationEndpoint() {
        ModelEndpoint endpoint = ModelEndpoint.resolve(
            "https://llm-example.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            "auto");

        assertThat(endpoint.protocol()).isEqualTo(ModelEndpoint.Protocol.DASHSCOPE_NATIVE);
        assertThat(endpoint.multimodal()).isTrue();
    }

    @Test
    void acceptsOpenAiBaseAndNormalizesFullCompletionUrl() {
        assertThat(ModelEndpoint.resolve("https://example.test/v1", "auto").url())
            .isEqualTo("https://example.test/v1");
        assertThat(ModelEndpoint.resolve("https://example.test/v1/chat/completions", "auto").url())
            .isEqualTo("https://example.test/v1");
    }

    @Test
    void explicitProtocolSupportsPrivateGatewayWithoutRecognizablePath() {
        ModelEndpoint endpoint = ModelEndpoint.resolve("https://model.internal/invoke", "dashscope-multimodal");

        assertThat(endpoint.protocol()).isEqualTo(ModelEndpoint.Protocol.DASHSCOPE_NATIVE);
        assertThat(endpoint.multimodal()).isTrue();
    }
}
