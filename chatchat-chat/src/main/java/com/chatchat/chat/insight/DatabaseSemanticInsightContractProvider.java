package com.chatchat.chat.insight;

import com.chatchat.agents.orchestration.SemanticInsightContract;
import com.chatchat.agents.orchestration.SemanticInsightContractProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Database-only, tenant-isolated and fail-closed formula resolution. */
@Service
@RequiredArgsConstructor
public class DatabaseSemanticInsightContractProvider implements SemanticInsightContractProvider {
    private final SemanticInsightContractRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public Resolution resolve(Request request) {
        if (request == null || blank(request.tenantId()))
            return new Resolution("rejected", "tenant_required", List.of());
        if (!request.explicitlyRequested())
            return new Resolution("skipped", "analysis_not_explicitly_requested", List.of());
        Instant now = Instant.now();
        List<SemanticInsightContract> resolved = new ArrayList<>();
        for (SemanticInsightContractEntity entity : repository
            .findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
                request.tenantId(), "PUBLISHED")) {
            if (!"EXPLICIT_ONLY".equals(normalize(entity.getActivationMode()))) continue;
            if (entity.getEffectiveFrom() != null && now.isBefore(entity.getEffectiveFrom())) continue;
            if (entity.getEffectiveTo() != null && !now.isBefore(entity.getEffectiveTo())) continue;
            if (!request.requestedContractIds().isEmpty()
                && request.requestedContractIds().stream().noneMatch(entity.getContractId()::equals)) continue;
            if (request.requestedContractIds().isEmpty() && hasNoApplicabilityBinding(entity)) continue;
            if (!matches(entity.getAgentId(), request.agentId())
                || !matches(entity.getToolName(), request.toolName())
                || !matches(entity.getDatasetKey(), baseDataset(request.datasetReference()))
                || !matches(entity.getTaskType(), request.taskType())) continue;
            SemanticInsightContract contract = parse(entity);
            if (contract != null) resolved.add(contract);
        }
        return resolved.isEmpty()
            ? new Resolution("skipped", "no_published_applicable_contract", List.of())
            : new Resolution("resolved", "database_contract_matched", resolved);
    }

    private SemanticInsightContract parse(SemanticInsightContractEntity entity) {
        try {
            Map<String, Object> payload = objectMapper.readValue(entity.getContractJson(),
                new TypeReference<Map<String, Object>>() {});
            Map<String, Object> governed = new LinkedHashMap<>(payload);
            governed.put("tenantId", entity.getTenantId());
            governed.put("contractId", entity.getContractId());
            governed.put("version", entity.getContractVersion());
            governed.put("status", "published");
            return SemanticInsightContract.fromMap(governed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matches(String binding, String actual) {
        return blank(binding) || !blank(actual) && binding.trim().equalsIgnoreCase(actual.trim());
    }
    private boolean hasNoApplicabilityBinding(SemanticInsightContractEntity entity) {
        return blank(entity.getAgentId()) && blank(entity.getToolName())
            && blank(entity.getDatasetKey()) && blank(entity.getTaskType());
    }
    private String baseDataset(String value) {
        return value == null ? null : value.replaceFirst("#occurrence-[0-9]+$", "");
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
