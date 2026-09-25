package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ProjectedAnalysisEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Only evidence carrying an explicit remoteProjection crosses the agent boundary. */
@Component
public class RemoteAgentEvidenceProjector {
    private final ObjectMapper mapper;

    public RemoteAgentEvidenceProjector(ObjectMapper mapper) { this.mapper = mapper; }

    public AgentExecutionRequest project(AgentExecutionRequest request) {
        List<ProjectedAnalysisEvidence> projected = new ArrayList<>();
        request.evidence().evidence().forEach(evidence -> {
            if (evidence instanceof ProjectedAnalysisEvidence approved) {
                projected.add(approved);
                return;
            }
            Object value = evidence.attributes().get("remoteProjection");
            if (value == null) return;
            String content;
            if (value instanceof String text) content = text;
            else if (value instanceof Map<?, ?> map) {
                try { content = mapper.writeValueAsString(map); }
                catch (JsonProcessingException error) {
                    throw new IllegalArgumentException("remoteProjection must be JSON serializable", error);
                }
            } else throw new IllegalArgumentException("remoteProjection must be text or a JSON object");
            if (content.isBlank() || content.length() > 100_000)
                throw new IllegalArgumentException("remoteProjection must contain 1..100000 characters");
            projected.add(new ProjectedAnalysisEvidence(evidence.evidenceId(), evidence.capability(), content,
                Map.of("sourceType", evidence.getClass().getSimpleName())));
        });
        EvidenceBundle bundle = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION, List.copyOf(projected),
            request.evidence().limitations(), Map.of("projection", "remote-explicit-v1"));
        return new AgentExecutionRequest(request.schemaVersion(), request.executionId(), request.capability(),
            request.task(), bundle, request.capabilityGrants(), request.constraints(), request.outputContract(),
            request.scope(), Map.of());
    }
}
