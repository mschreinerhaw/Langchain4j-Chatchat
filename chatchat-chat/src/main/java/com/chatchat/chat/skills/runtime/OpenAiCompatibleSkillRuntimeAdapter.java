package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.model.ModelEndpoint;
import com.chatchat.common.config.ModelCatalogOverride;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Executes an explicitly selected, published OpenAI-compatible database model connection. */
@Component
public class OpenAiCompatibleSkillRuntimeAdapter implements AgentRuntimeAdapter {
    private final LangChain4jSkillRuntimeAdapter delegate;
    private final ModelCatalogOverride models;

    public OpenAiCompatibleSkillRuntimeAdapter(LangChain4jSkillRuntimeAdapter delegate,
                                                ObjectProvider<ModelCatalogOverride> models) {
        this.delegate = delegate;
        this.models = models.getIfAvailable();
    }

    @Override public String adapterId() { return "openai-compatible-agent-runtime"; }
    @Override public int priority() { return 110; }
    @Override public boolean supports(String engine) {
        return "OPENAI_COMPATIBLE".equals(normalize(engine));
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Validation validation = validate(request == null ? Map.of() : request.attributes());
        if (!validation.ready()) return new ExecutionResult(validation.status(), "", validation.details());
        ExecutionResult result = delegate.execute(request);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("adapterId", adapterId());
        metadata.put("modelName", validation.modelName());
        return new ExecutionResult(result.status(), result.output(), metadata);
    }

    @Override
    public HealthResult health(HealthRequest request) {
        Validation validation = validate(request == null ? Map.of() : request.attributes());
        return new HealthResult(validation.status(), validation.details());
    }

    private Validation validate(Map<String, Object> attributes) {
        String modelName = text(attributes.get("modelName"));
        if (modelName.isBlank()) return invalid("MODEL_REQUIRED", modelName, "modelName is required");
        if (models == null || !models.hasChatModels())
            return invalid("MODEL_CATALOG_UNAVAILABLE", modelName,
                "Published database model catalog is unavailable");
        try {
            var resolved = models.resolveChatModel(modelName);
            if (resolved == null || resolved.config() == null)
                return invalid("MODEL_NOT_AVAILABLE", modelName,
                    "Selected model is not published and enabled in the database catalog");
            ModelEndpoint endpoint = ModelEndpoint.resolve(
                resolved.config().getBaseUrl(), resolved.config().getProtocol());
            if (endpoint.protocol() != ModelEndpoint.Protocol.OPENAI)
                return invalid("MODEL_PROTOCOL_MISMATCH", modelName,
                    "Selected model is not OpenAI-compatible");
            return new Validation(true, "READY", resolved.configuredKey(), Map.of(
                "adapterId", adapterId(), "modelName", resolved.configuredKey(),
                "providerModel", resolved.providerModelName(), "protocol", "OPENAI_COMPATIBLE"));
        } catch (IllegalArgumentException error) {
            return invalid("MODEL_NOT_AVAILABLE", modelName, error.getMessage());
        }
    }

    private Validation invalid(String status, String modelName, String message) {
        return new Validation(false, status, modelName, Map.of(
            "adapterId", adapterId(), "modelName", modelName, "message", message == null ? "" : message));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }

    private record Validation(boolean ready, String status, String modelName, Map<String, Object> details) { }
}
