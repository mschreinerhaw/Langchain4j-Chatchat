package com.chatchat.api.runtime;

import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisToolContractIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ToolMetadata contract(List<ToolParameter> parameters) {
        return ToolMetadata.builder().id("source_reader").parameters(parameters).requiresAuth(true)
            .metadata(Map.of("inputSchema", Map.of("properties", Map.of("cursor", Map.of("type", "string"), "limit", Map.of("type", "integer")))))
            .build();
    }
    @Test void parameterMappingOrderDoesNotChangeIdentityOrMutateThePublishedContract() {
        var a = ToolParameter.builder().name("cursor").type("string").build();
        var b = ToolParameter.builder().name("limit").type("integer").build();
        var original = contract(List.of(b, a));
        assertThat(AnalysisToolContractIdentity.fingerprint(original, mapper))
            .isEqualTo(AnalysisToolContractIdentity.fingerprint(contract(List.of(a, b)), mapper));
        assertThat(original.getParameters()).containsExactly(b, a);
        assertThat(AnalysisToolContractIdentity.legacyFingerprint(original, mapper))
            .isNotEqualTo(AnalysisToolContractIdentity.legacyFingerprint(contract(List.of(a, b)), mapper));
    }
    @Test void authorizationAndParameterTypeChangesStillInvalidateIdentity() {
        var metadata = contract(List.of(ToolParameter.builder().name("limit").type("integer").build()));
        String fingerprint = AnalysisToolContractIdentity.fingerprint(metadata, mapper);
        metadata.setRequiresAuth(false);
        assertThat(AnalysisToolContractIdentity.matches(fingerprint, AnalysisToolContractIdentity.SCHEMA_VERSION, metadata, mapper)).isFalse();
        metadata.setRequiresAuth(true); metadata.getParameters().get(0).setType("string");
        assertThat(AnalysisToolContractIdentity.matches(fingerprint, AnalysisToolContractIdentity.SCHEMA_VERSION, metadata, mapper)).isFalse();
    }
    @Test void legacyAndUnknownVersionsDoNotBypassTheFullContractCheck() {
        var metadata = contract(List.of(ToolParameter.builder().name("limit").type("integer").build()));
        String legacy = AnalysisToolContractIdentity.legacyFingerprint(metadata, mapper);
        assertThat(AnalysisToolContractIdentity.matches(legacy, null, metadata, mapper)).isTrue();
        assertThat(AnalysisToolContractIdentity.matches(legacy, "future", metadata, mapper)).isFalse();
        metadata.setRiskLevel("high");
        assertThat(AnalysisToolContractIdentity.matches(legacy, null, metadata, mapper)).isFalse();
    }
}
