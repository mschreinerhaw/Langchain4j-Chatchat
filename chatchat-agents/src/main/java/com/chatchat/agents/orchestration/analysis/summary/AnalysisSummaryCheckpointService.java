package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.DataAnalysisPosition;
import com.chatchat.common.runtime.summary.DataAnalysisSummaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** Owns lossless analysis-summary checkpoint identity, restore and persistence. */
@Slf4j
public final class AnalysisSummaryCheckpointService {

    private final ObjectMapper objectMapper;
    private AnalysisEvidenceSpillStore store;

    public AnalysisSummaryCheckpointService(ObjectMapper objectMapper,
                                            AnalysisEvidenceSpillStore store) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.store = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }

    public void setStore(AnalysisEvidenceSpillStore store) {
        this.store = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }

    public String inputSha256(String contentSha256,
                              DataAnalysisPosition position,
                              Map<String, Object> governedContext,
                              String query,
                              boolean modelSummaryRequired) {
        return ModelProtocolJson.sha256Hex(Map.of(
            "bridgeSchemaVersion", DataAnalysisSummaryProtocol.BRIDGE_SCHEMA_VERSION,
            "contentSha256", firstNonBlank(contentSha256, ""),
            "position", position == null ? Map.of() : position.toMap(),
            "governedContext", governedContext == null ? Map.of() : governedContext,
            "userObjective", firstNonBlank(query, ""),
            "modelSummaryRequired", modelSummaryRequired));
    }

    public AnalysisSummaryResult restore(GovernanceIsolationScope scope,
                                         String checkpointKey,
                                         String inputSha256) {
        try {
            return store.readCheckpoint(scope, checkpointKey, inputSha256)
                .map(json -> deserialize(scope, json))
                .orElse(null);
        } catch (Exception ex) {
            log.warn("Analysis summary checkpoint is unusable and will be recomputed. checkpointKey={} error={}",
                checkpointKey, ex.getMessage());
            return null;
        }
    }

    public void persist(GovernanceIsolationScope scope,
                        String checkpointKey,
                        String inputSha256,
                        AnalysisSummaryResult summary) {
        try {
            store.checkpoint(scope, checkpointKey, inputSha256, objectMapper.writeValueAsString(summary));
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Analysis summary checkpoint cannot be persisted losslessly: " + checkpointKey, ex);
        }
    }

    private AnalysisSummaryResult deserialize(GovernanceIsolationScope scope, String json) {
        try {
            AnalysisSummaryResult result = objectMapper.readValue(json, AnalysisSummaryResult.class);
            scope.requireSamePartition(result.isolationScope());
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
