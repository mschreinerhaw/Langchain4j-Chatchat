package com.chatchat.api.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

/** Operator-published bindings. Neither HTTP requests nor imported Skill files can set these. */
@ConfigurationProperties("chatchat.skill-data")
public record SkillDataWorkflowProperties(List<Binding> bindings) {
    public SkillDataWorkflowProperties { bindings = bindings == null ? List.of() : List.copyOf(bindings); }

    public record Binding(boolean enabled, String tenantId, String domainSkillId, String contractId,
                          String workflowId, String version, String executionSkillId, String templateId,
                          String assetName, String environment, Map<String, String> parameterBindings,
                          Map<String, String> fields, Map<String, String> semantics) {
        public Binding {
            parameterBindings = parameterBindings == null ? Map.of() : Map.copyOf(parameterBindings);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            semantics = semantics == null ? Map.of() : Map.copyOf(semantics);
            if (enabled && (java.util.stream.Stream.of(tenantId, domainSkillId, contractId, workflowId,
                    version, executionSkillId, templateId, assetName, environment)
                    .anyMatch(value -> value == null || value.isBlank()) || fields.isEmpty()))
                throw new IllegalArgumentException("Enabled data bindings require identity, version, template and canonical fields");
        }
    }
}
