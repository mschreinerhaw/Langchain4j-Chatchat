package com.chatchat.agents.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ModelFailureCategoryTest {
    @Test void usesTypedCausesWithoutInferringReasoningDelaysFromEnglishMessages() {
        assertThat(ModelFailureCategory.classify(new IllegalStateException(new java.net.UnknownHostException())))
            .isEqualTo(ModelFailureCategory.MODEL_NETWORK_FAILURE);
        assertThat(ModelFailureCategory.classify(new dev.langchain4j.exception.HttpException(429,"redacted")))
            .isEqualTo(ModelFailureCategory.MODEL_RATE_LIMITED);
        assertThat(ModelFailureCategory.classify(new dev.langchain4j.exception.HttpException(413,"redacted")))
            .isEqualTo(ModelFailureCategory.MODEL_REQUEST_TOO_LARGE);
        assertThat(ModelFailureCategory.classify(new RuntimeStreamingChatModel.FirstTokenTimeoutException(new java.util.concurrent.TimeoutException())))
            .isEqualTo(ModelFailureCategory.MODEL_FIRST_TOKEN_TIMEOUT);
        assertThat(ModelFailureCategory.classify(new IllegalStateException("slow reasoning context too large")))
            .isEqualTo(ModelFailureCategory.MODEL_REQUEST_FAILED);
    }
}
