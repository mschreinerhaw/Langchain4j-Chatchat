package com.chatchat.chat.skills.domain.adapter;

import com.chatchat.runtime.skill.api.skill.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Deterministic executable protocol conversion after the model's advisory knowledge compilation. */
public final class ExternalSkillProtocolConverter {
    private final ObjectMapper mapper;
    public ExternalSkillProtocolConverter(ObjectMapper mapper) { this.mapper = mapper; }

    public RuntimeSkillIr convert(AdaptedExternalSkill source, RuntimeSkillIr knowledge) {
        Map<String, Object> root = object(source.metadata().get("frontMatter"));
        Map<String, Object> protocol = root.containsKey("runtime") ? object(root.get("runtime")) : root;
        if (protocol.containsKey("schemaVersion") && !"skill_protocol.v1".equals(protocol.get("schemaVersion")))
            throw new IllegalArgumentException("Unsupported executable skill protocol version");
        var requirements = requirements(root, protocol);
        List<String> capabilities = protocol.containsKey("capabilities") ? strings(protocol.get("capabilities"))
            : knowledge.capabilities().stream().filter(value -> value.matches("[A-Za-z][A-Za-z0-9_.-]{0,119}")).toList();
        if (capabilities.size() > 32 || capabilities.stream().anyMatch(value -> !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,119}")))
            throw new IllegalArgumentException("Capabilities must be bounded stable identifiers");
        List<String> dependencies = strings(protocol.get("requiresCapabilities"));
        if (dependencies.size() > 32 || dependencies.stream().anyMatch(value -> !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,119}")))
            throw new IllegalArgumentException("Capability dependencies must be bounded stable identifiers");
        String domain = protocol.get("domain") instanceof String declared ? declared : knowledge.capability().domain();
        return new RuntimeSkillIr(RuntimeSkillIr.SCHEMA_VERSION, knowledge.identity(), knowledge.description(),
            knowledge.trigger(), new RuntimeSkillIr.SkillCapability(domain, capabilities), knowledge.instruction(), knowledge.io(),
            new RuntimeSkillIr.SkillExecutionRequirement(dependencies, requirements),
            knowledge.safety(), knowledge.source(), new RuntimeSkillIr.SkillCompilationMetadata(RuntimeSkillIr.COMPILER_VERSION,
                knowledge.compilation().model(), knowledge.compilationMode()), knowledge.markdownInstructions());
    }

    private SkillRequirements requirements(Map<String, Object> root, Map<String, Object> protocol) {
        Map<String, Object> declared = object(root.getOrDefault("requirements", root.getOrDefault("permissions", Map.of())));
        List<SkillDataRequirement> data = typed(protocol.get("requiredData"), new TypeReference<List<SkillDataRequirement>>() {});
        List<SkillAnalysisStep> steps = typed(protocol.get("analysisSteps"), new TypeReference<List<SkillAnalysisStep>>() {});
        return new SkillRequirements(values(root, declared, "allowedDocuments", "allowed_documents", "documents", "documentIds"),
            values(root, declared, "allowedKnowledgeBases", "allowed_knowledge_bases", "knowledgeBases", "knowledgeBaseIds"),
            values(root, declared, "allowedMcp", "allowed_mcp", "mcpTools", "mcp_tool_ids"),
            values(root, declared, "allowedAgents", "allowed_agents", "agents", "agentIds"),
            protocol.containsKey("workflows") ? strings(protocol.get("workflows"))
                : values(root, declared, "workflows", "workflowIds", "workflow_ids"), data, steps);
    }

    private <T> List<T> typed(Object value, TypeReference<List<T>> type) {
        if (value == null) return List.of();
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("Executable declarations must be arrays");
        return mapper.convertValue(value, type);
    }
    private List<String> values(Map<String, Object> root, Map<String, Object> nested, String... keys) {
        for (var map : List.of(root, nested)) for (String key : keys) if (map.containsKey(key)) return strings(map.get(key));
        return List.of();
    }
    private List<String> strings(Object value) {
        if (value == null) return List.of();
        if (value instanceof String text) return Arrays.stream(text.split("[,\\n]")).map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
            throw new IllegalArgumentException("Expected a list of identifiers");
        return list.stream().map(String.class::cast).map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException("Expected a protocol object");
        return (Map<String, Object>) map;
    }
}
