package com.chatchat.chat.skills.release;

import com.chatchat.chat.skills.SkillDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentReleaseService {

    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    private final AgentReleaseRepository repository;
    private final AgentReleaseQualityGate qualityGate;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentReleaseView prepare(SkillDefinition skill) {
        AgentReleaseQualityReport report = qualityGate.evaluate(skill);
        if (!report.passed()) {
            String failed = report.checks().stream().filter(check -> !check.passed())
                .map(AgentReleaseQualityReport.Check::id).reduce((a, b) -> a + "," + b).orElse("unknown");
            throw new IllegalArgumentException("AGENT_RELEASE_QUALITY_GATE_FAILED: " + failed);
        }
        int version = repository.findTopByAgentIdOrderByReleaseVersionDesc(skill.id())
            .map(AgentReleaseEntity::getReleaseVersion).orElse(0) + 1;
        String artifact = artifactJson(skill, version);
        AgentReleaseEntity entity = new AgentReleaseEntity();
        entity.setReleaseId(UUID.randomUUID().toString());
        entity.setAgentId(skill.id());
        entity.setReleaseVersion(version);
        entity.setStatus(STATUS_APPROVED);
        entity.setArtifactChecksum(sha256(artifact));
        entity.setArtifactJson(artifact);
        entity.setQualityReportJson(write(report));
        entity.setCreatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    @Transactional
    public AgentReleaseView markPublished(String releaseId) {
        AgentReleaseEntity release = repository.findById(releaseId)
            .orElseThrow(() -> new IllegalArgumentException("Agent release not found: " + releaseId));
        if (!STATUS_APPROVED.equals(release.getStatus()) && !STATUS_PUBLISHED.equals(release.getStatus())) {
            throw new IllegalStateException("Agent release is not approved: " + releaseId);
        }
        release.setStatus(STATUS_PUBLISHED);
        if (release.getPublishedAt() == null) release.setPublishedAt(Instant.now());
        return toView(repository.save(release));
    }

    @Transactional(readOnly = true)
    public Optional<SkillDefinition> resolvePublished(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        return repository.findTopByAgentIdAndStatusOrderByReleaseVersionDesc(agentId, STATUS_PUBLISHED)
            .map(entity -> readSkill(entity.getArtifactJson()));
    }

    @Transactional(readOnly = true)
    public List<AgentReleaseView> list(String agentId) {
        return repository.findByAgentIdOrderByReleaseVersionDesc(agentId).stream().map(this::toView).toList();
    }

    private String artifactJson(SkillDefinition skill, int version) {
        Map<String, Object> skillMap = objectMapper.convertValue(skill, Map.class);
        skillMap.put("marketStatus", "published");
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("contractVersion", "agent_release_v1");
        artifact.put("agentId", skill.id());
        artifact.put("releaseVersion", version);
        artifact.put("skill", skillMap);
        artifact.put("modelProfile", Map.of("modelName", text(skill.modelName(), "system-default")));
        artifact.put("toolBindings", Map.of(
            "serviceIds", safe(skill.boundMcpServiceIds()),
            "toolNames", safe(skill.boundMcpToolNames()),
            "toolConfigs", safe(skill.toolConfigs())));
        artifact.put("knowledgeBindings", Map.of(
            "documentIds", safe(skill.boundDocumentIds()),
            "documentTags", safe(skill.boundDocumentTags())));
        return canonicalWrite(artifact);
    }

    @SuppressWarnings("unchecked")
    private SkillDefinition readSkill(String artifactJson) {
        try {
            Map<String, Object> artifact = objectMapper.readValue(artifactJson, Map.class);
            return objectMapper.convertValue(artifact.get("skill"), SkillDefinition.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid Agent release artifact", error);
        }
    }

    private AgentReleaseView toView(AgentReleaseEntity entity) {
        return new AgentReleaseView(entity.getReleaseId(), entity.getAgentId(), entity.getReleaseVersion(),
            entity.getStatus(), entity.getArtifactChecksum(),
            read(entity.getQualityReportJson(), AgentReleaseQualityReport.class),
            entity.getCreatedAt(), entity.getPublishedAt());
    }

    private String canonicalWrite(Object value) {
        try {
            return objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to create Agent release artifact", error);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize Agent release", error);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize Agent release", error);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private List<?> safe(List<?> value) {
        return value == null ? List.of() : value;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record AgentReleaseView(String releaseId,
                                   String agentId,
                                   int version,
                                   String status,
                                   String checksum,
                                   AgentReleaseQualityReport qualityReport,
                                   Instant createdAt,
                                   Instant publishedAt) {
    }
}
