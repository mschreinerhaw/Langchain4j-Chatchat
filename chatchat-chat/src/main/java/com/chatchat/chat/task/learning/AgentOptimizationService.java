package com.chatchat.chat.task.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Controlled feedback-to-change lifecycle. It never mutates a live Agent configuration directly. */
@Service
@RequiredArgsConstructor
public class AgentOptimizationService {

    private static final Set<String> ALLOWED_PATCH_FIELDS = Set.of(
        "systemPrompt", "workflowConfig", "routingSettings", "toolConfigs", "quickQuestions");
    private final AgentOptimizationProposalRepository repository;
    private final AgentExperienceRepository experienceRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProposalView propose(ProposeCommand command) {
        requireText(command.tenantId(), "tenantId");
        requireText(command.agentId(), "agentId");
        requireText(command.createdBy(), "createdBy");
        List<String> sources = command.sourceExperienceIds() == null ? List.of() : command.sourceExperienceIds();
        if (sources.isEmpty()) throw new IllegalArgumentException("At least one source experience is required");
        for (String source : sources) {
            AgentExperienceEntity experience = experienceRepository.findById(source)
                .orElseThrow(() -> new IllegalArgumentException("Experience not found: " + source));
            if (!command.tenantId().equals(experience.getTenantId())) {
                throw new IllegalArgumentException("Experience belongs to another tenant: " + source);
            }
        }
        validatePatch(command.patch());
        Instant now = Instant.now();
        AgentOptimizationProposalEntity entity = new AgentOptimizationProposalEntity();
        entity.setProposalId(UUID.randomUUID().toString());
        entity.setTenantId(command.tenantId());
        entity.setAgentId(command.agentId());
        entity.setProposalType(text(command.proposalType(), "PROMPT_OR_POLICY"));
        entity.setStatus("DRAFT");
        entity.setSourceExperienceIdsJson(write(sources));
        entity.setPatchJson(write(command.patch()));
        entity.setEvidenceJson(write(command.evidence() == null ? Map.of() : command.evidence()));
        entity.setCreatedBy(command.createdBy());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return view(repository.save(entity));
    }

    @Transactional
    public ProposalView validate(String tenantId, String proposalId, Map<String, Object> regressionReport) {
        AgentOptimizationProposalEntity entity = requireState(tenantId, proposalId, "DRAFT");
        if (!Boolean.TRUE.equals(regressionReport == null ? null : regressionReport.get("passed"))) {
            throw new IllegalArgumentException("OPTIMIZATION_REGRESSION_FAILED: passed=true is required");
        }
        Number total = regressionReport.get("total") instanceof Number n ? n : 0;
        if (total.intValue() <= 0) {
            throw new IllegalArgumentException("OPTIMIZATION_REGRESSION_EMPTY: at least one regression case is required");
        }
        entity.setRegressionReportJson(write(regressionReport));
        transition(entity, "VALIDATED");
        return view(repository.save(entity));
    }

    @Transactional
    public ProposalView approve(String tenantId, String proposalId, String reviewer) {
        AgentOptimizationProposalEntity entity = requireState(tenantId, proposalId, "VALIDATED");
        requireText(reviewer, "reviewer");
        entity.setReviewedBy(reviewer);
        transition(entity, "APPROVED");
        return view(repository.save(entity));
    }

    @Transactional
    public ProposalView startCanary(String tenantId, String proposalId, int percent) {
        AgentOptimizationProposalEntity entity = requireState(tenantId, proposalId, "APPROVED");
        if (percent < 1 || percent > 50) {
            throw new IllegalArgumentException("Canary percentage must be between 1 and 50");
        }
        entity.setCanaryPercent(percent);
        transition(entity, "CANARY");
        return view(repository.save(entity));
    }

    @Transactional
    public ProposalView completeCanary(String tenantId, String proposalId, Map<String, Object> metrics) {
        AgentOptimizationProposalEntity entity = requireState(tenantId, proposalId, "CANARY");
        int samples = metrics != null && metrics.get("sampleSize") instanceof Number n ? n.intValue() : 0;
        boolean passed = Boolean.TRUE.equals(metrics == null ? null : metrics.get("passed"));
        if (!passed || samples < 10) {
            entity.setCanaryMetricsJson(write(metrics == null ? Map.of() : metrics));
            transition(entity, "REJECTED");
            return view(repository.save(entity));
        }
        entity.setCanaryMetricsJson(write(metrics));
        transition(entity, "READY_FOR_ROLLOUT");
        return view(repository.save(entity));
    }

    @Transactional
    public ProposalView markRolledOut(String tenantId, String proposalId, String reviewer) {
        AgentOptimizationProposalEntity entity = requireState(tenantId, proposalId, "READY_FOR_ROLLOUT");
        requireText(reviewer, "reviewer");
        entity.setReviewedBy(reviewer);
        transition(entity, "ROLLED_OUT");
        return view(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<ProposalView> list(String tenantId, String agentId) {
        return repository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(tenantId, agentId)
            .stream().map(this::view).toList();
    }

    private AgentOptimizationProposalEntity requireState(String tenantId, String id, String expected) {
        AgentOptimizationProposalEntity entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Optimization proposal not found: " + id));
        if (!entity.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Optimization proposal belongs to another tenant");
        }
        if (!expected.equals(entity.getStatus())) {
            throw new IllegalStateException("Invalid optimization transition: " + entity.getStatus() + " -> " + expected);
        }
        return entity;
    }

    private void transition(AgentOptimizationProposalEntity entity, String status) {
        entity.setStatus(status);
        entity.setUpdatedAt(Instant.now());
    }

    private void validatePatch(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) throw new IllegalArgumentException("Optimization patch is required");
        List<String> forbidden = patch.keySet().stream().filter(key -> !ALLOWED_PATCH_FIELDS.contains(key)).toList();
        if (!forbidden.isEmpty()) {
            throw new IllegalArgumentException("Optimization patch contains forbidden fields: " + forbidden);
        }
    }

    private ProposalView view(AgentOptimizationProposalEntity entity) {
        return new ProposalView(entity.getProposalId(), entity.getTenantId(), entity.getAgentId(),
            entity.getProposalType(), entity.getStatus(), readList(entity.getSourceExperienceIdsJson()),
            readMap(entity.getPatchJson()), readMap(entity.getEvidenceJson()),
            readMap(entity.getRegressionReportJson()), entity.getCanaryPercent(),
            readMap(entity.getCanaryMetricsJson()), entity.getCreatedBy(), entity.getReviewedBy(),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to serialize optimization proposal", error); }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid optimization proposal JSON", error); }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid optimization source JSON", error); }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record ProposeCommand(String tenantId, String agentId, String proposalType,
                                 List<String> sourceExperienceIds, Map<String, Object> patch,
                                 Map<String, Object> evidence, String createdBy) { }

    public record ProposalView(String proposalId, String tenantId, String agentId, String proposalType,
                               String status, List<String> sourceExperienceIds, Map<String, Object> patch,
                               Map<String, Object> evidence, Map<String, Object> regressionReport,
                               Integer canaryPercent, Map<String, Object> canaryMetrics,
                               String createdBy, String reviewedBy, Instant createdAt, Instant updatedAt) { }
}
