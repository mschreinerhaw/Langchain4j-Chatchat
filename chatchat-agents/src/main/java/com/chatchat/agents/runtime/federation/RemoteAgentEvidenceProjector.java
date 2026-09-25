package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ProjectedAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
        boolean domainPackage = "analysis_package.v1".equals(
            request.metadata().get(AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY));
        request.evidence().evidence().forEach(evidence -> {
            if (evidence instanceof ProjectedAnalysisEvidence approved) {
                projected.add(approved);
                return;
            }
            Object value = evidence.attributes().get("remoteProjection");
            if (domainPackage && evidence instanceof DocumentAnalysisEvidence document) {
                String excerpt = document.content().length() > 8000
                    ? document.content().substring(0, 8000) : document.content();
                value = Map.of("documentId", safe(document.documentId()), "documentName", safe(document.documentName()),
                    "citation", document.citation() == null ? "" : document.citation(),
                    "content", excerpt, "truncated", excerpt.length() < document.content().length());
            } else if (domainPackage && value == null && evidence instanceof ToolAnalysisEvidence tool) {
                value = Map.of("toolName", tool.toolName(), "data", sanitizedJson(tool.content()));
            } else if (domainPackage && evidence instanceof StructuredDataEvidence data) {
                JsonNode payload = sanitizedJson(data.content()).path("data");
                if (!payload.path("rows").isArray() || payload.path("rows").size() != data.rows())
                    throw new IllegalArgumentException("Structured evidence rows are not complete");
                value = Map.of("assetName", safe(data.dataset()), "templateId", safe(data.query()),
                    "rowCount", data.rows(), "rows", payload.path("rows"));
            }
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
        if (domainPackage && (projected.size() > 24
            || projected.stream().mapToInt(item -> item.content().length()).sum() > 150_000))
            throw new IllegalArgumentException("Domain analysis evidence exceeds remote projection limits");
        EvidenceBundle bundle = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION, List.copyOf(projected),
            request.evidence().limitations(), Map.of("projection", "remote-explicit-v1"));
        Map<String, Object> safeMetadata = new java.util.LinkedHashMap<>();
        for (String key : List.of(AgentExecutionRequest.MODE_METADATA_KEY,
            AgentExecutionRequest.COLLABORATION_TASK_METADATA_KEY,
            AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY)) {
            Object value = request.metadata().get(key);
            if (value instanceof String text && !text.isBlank()) safeMetadata.put(key, text);
        }
        return new AgentExecutionRequest(request.schemaVersion(), request.executionId(), request.capability(),
            request.task(), bundle, request.capabilityGrants(), request.constraints(), request.outputContract(),
            request.scope(), safeMetadata);
    }

    private JsonNode sanitizedJson(String content) {
        try {
            JsonNode node = mapper.readTree(content);
            if (node == null) throw new IllegalArgumentException("Evidence JSON is empty");
            redactSecrets(node);
            return node;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Evidence must be valid JSON before remote transfer", invalid);
        }
    }

    private void redactSecrets(JsonNode node) {
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            List<String> remove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if (name.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(password|secret|token|api[_-]?key|authorization|credential|bearer).*"))
                    remove.add(name);
            });
            remove.forEach(object::remove);
            object.elements().forEachRemaining(this::redactSecrets);
        } else if (node.isArray()) node.elements().forEachRemaining(this::redactSecrets);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
