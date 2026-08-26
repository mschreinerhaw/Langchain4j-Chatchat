package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.orchestration.protocol.RuntimeProtocolConfiguration;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;
import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryReducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeProtocolRegistryTest {

    @Test
    void composesEveryRuntimeLayerBehindItsProtocolPort() {
        RuntimeProtocolRegistry registry = new RuntimeProtocolConfiguration()
            .runtimeProtocolRegistry(new ObjectMapper(), List.of());

        assertThat(registry.require(RuntimeEvidenceProtocol.class))
            .isInstanceOf(RuntimeEvidenceProtocol.class);
        assertThat(registry.require(RuntimeResultAnalysisProtocol.class))
            .isInstanceOf(RuntimeResultAnalysisProtocol.class);
        assertThat(registry.require(RuntimeAnalysisContextProtocol.class))
            .isInstanceOf(RuntimeAnalysisContextProtocol.class);
        assertThat(registry.require(RuntimeAnalysisSummaryProtocol.class))
            .isInstanceOf(RuntimeAnalysisSummaryProtocol.class);
        assertThat(registry.require(ModelSummaryDispatcher.class))
            .isInstanceOf(ModelSummaryDispatcher.class);
        assertThat(registry.require(ModelSummaryReducer.class))
            .isInstanceOf(ModelSummaryReducer.class);
        assertThat(registry.ports()).hasSize(6);
    }

    @Test
    void installsNewResultProtocolWithoutChangingTheRuntimeBridge() {
        RuntimeResultAnalysisAdapter adapter = new RuntimeResultAnalysisAdapter() {
            @Override public String id() { return "reasoning.extension.v1"; }
            @Override public int priority() { return 2_000; }
            @Override public boolean supports(AnalysisRequest request) {
                return request.payload() instanceof Map<?, ?> map
                    && "reasoning_extension.v1".equals(map.get("schemaVersion"));
            }
            @Override public AnalysisResult adapt(AnalysisRequest request) {
                return new AnalysisResult("reasoning_extension.v1", "REASONING_FACTS", List.of(
                    new AnalysisDataset(request.datasetReference(), Map.of("domain", "extension"),
                        List.of(Map.of("finding", "adapter-owned fact")))));
            }
        };
        RuntimeProtocolRegistry registry = new RuntimeProtocolConfiguration()
            .runtimeProtocolRegistry(new ObjectMapper(), List.of(adapter));

        Map<String, Object> projection = registry.require(RuntimeResultAnalysisProtocol.class)
            .analysisProjection("extension-result", Map.of("schemaVersion", "reasoning_extension.v1"));

        assertThat(projection)
            .containsEntry("schemaVersion", RuntimeResultAnalysisProtocol.PROJECTION_SCHEMA_VERSION)
            .containsEntry("adapterId", "reasoning.extension.v1");
        assertThat(projection.toString()).contains("adapter-owned fact", "domain=extension");
    }

    @Test
    void rejectsMissingAndDuplicateProtocolPorts() {
        RuntimeProtocolRegistry.Builder builder = RuntimeProtocolRegistry.builder()
            .register(RuntimeResultAnalysisProtocol.class, new NoOpResultProtocol());

        assertThatThrownBy(() -> builder.register(
            RuntimeResultAnalysisProtocol.class, new NoOpResultProtocol()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
        assertThatThrownBy(() -> builder.build().require(RuntimeAnalysisContextProtocol.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not registered");
    }

    private static final class NoOpResultProtocol implements RuntimeResultAnalysisProtocol {
        @Override public Map<String, Object> analysisProjection(String reference, Object payload) {
            return Map.of();
        }
        @Override public Map<String, Object> analysisProjection(
            String reference, Object payload, int maximumRecordChars) {
            return Map.of();
        }
        @Override public Map<String, Object> protocolAnalysisProjection(
            String reference, Object payload, int maximumRecordChars) {
            return Map.of();
        }
    }
}
