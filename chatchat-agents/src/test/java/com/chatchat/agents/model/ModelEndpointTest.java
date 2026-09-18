package com.chatchat.agents.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(ModelEndpoint.resolve("http://10.6.65.11:30000/v1/chat/completions", "openai").url())
            .isEqualTo("http://10.6.65.11:30000/v1");
    }

    @Test
    void rejectsRelativeModelUrlsWithActionableErrors() {
        assertThatThrownBy(() -> ModelEndpoint.resolve("127.0.0.1:31005/v1", "openai"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute http:// or https:// URL");
    }

    @Test
    void explicitProtocolSupportsPrivateGatewayWithoutRecognizablePath() {
        ModelEndpoint endpoint = ModelEndpoint.resolve("https://model.internal/invoke", "dashscope-multimodal");

        assertThat(endpoint.protocol()).isEqualTo(ModelEndpoint.Protocol.DASHSCOPE_NATIVE);
        assertThat(endpoint.multimodal()).isTrue();
    }

    @Test
    void detectsNativeEndpointBehindARewrittenPrefix() {
        ModelEndpoint endpoint = ModelEndpoint.resolve(
            "https://gateway.example/tenant/route/multimodal-generation/generation?workspace=test",
            "auto");

        assertThat(endpoint.protocol()).isEqualTo(ModelEndpoint.Protocol.DASHSCOPE_NATIVE);
        assertThat(endpoint.multimodal()).isTrue();
    }
}
