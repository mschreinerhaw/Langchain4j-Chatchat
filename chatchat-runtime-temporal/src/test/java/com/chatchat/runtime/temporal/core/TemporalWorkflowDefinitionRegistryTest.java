package com.chatchat.runtime.temporal.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalWorkflowDefinitionRegistryTest {

    @Test
    void idempotentRegistrationKeepsThePublishedContract() {
        TemporalWorkflowDefinitionRegistry registry = new TemporalWorkflowDefinitionRegistry();
        registry.register("echo-v1", String.class, String.class, (input, context) -> input);

        registry.register("echo-v1", String.class, String.class,
            (input, context) -> input.toUpperCase());

        assertThat(registry.required("echo-v1").inputType()).isEqualTo(String.class);
        assertThat(registry.required("echo-v1").outputType()).isEqualTo(String.class);
    }

    @Test
    void registrationRejectsInputOrOutputContractDrift() {
        TemporalWorkflowDefinitionRegistry registry = new TemporalWorkflowDefinitionRegistry();
        registry.register("echo-v1", String.class, String.class, (input, context) -> input);

        assertThatThrownBy(() -> registry.register(
            "echo-v1", Integer.class, String.class, (input, context) -> input.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered with another contract");
        assertThatThrownBy(() -> registry.register(
            "echo-v1", String.class, Integer.class, (input, context) -> input.length()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered with another contract");
    }

    @Test
    void missingWorkflowTypeFailsBeforeAnyExternalExecution() {
        TemporalWorkflowDefinitionRegistry registry = new TemporalWorkflowDefinitionRegistry();

        assertThatThrownBy(() -> registry.required("missing-v1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Workflow type is not registered: missing-v1");
    }
}
