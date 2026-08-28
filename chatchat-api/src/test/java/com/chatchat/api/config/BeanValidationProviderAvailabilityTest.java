package com.chatchat.api.config;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BeanValidationProviderAvailabilityTest {

    @Test
    void apiRuntimeProvidesJakartaBeanValidationImplementation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator()).isNotNull();
        }
    }
}
