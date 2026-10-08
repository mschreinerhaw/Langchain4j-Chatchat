package com.chatchat.chat.interaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRuntimeSemanticPolicySourceTest {
    @Test
    void readsCurrentPolicyFromDatabase() throws Exception {
        RuntimeSemanticPolicyRepository repository = mock(RuntimeSemanticPolicyRepository.class);
        RuntimeSemanticPolicyEntity row = new RuntimeSemanticPolicyEntity();
        row.setPolicyKey("default");
        row.setPolicyJson(new ObjectMapper().writeValueAsString(Map.of(
            "toolRules", java.util.List.of(Map.of("role", "CUSTOM", "mode", "SUFFIX", "value", "custom_query")))));
        when(repository.findById("default")).thenReturn(Optional.of(row));

        var source = new DatabaseRuntimeSemanticPolicySource(repository, new ObjectMapper());
        assertThat(source.snapshot().hasRole("tenant_custom_query", "CUSTOM")).isTrue();
        row.setPolicyJson("{");
        assertThatThrownBy(source::snapshot).isInstanceOf(IllegalStateException.class);
    }
}
