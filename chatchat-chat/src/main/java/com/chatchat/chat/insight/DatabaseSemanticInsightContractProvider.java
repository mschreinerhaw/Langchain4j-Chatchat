package com.chatchat.chat.insight;

import com.chatchat.agents.orchestration.analysis.SemanticInsightContract;
import com.chatchat.agents.orchestration.analysis.SemanticInsightContractProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Database-only, tenant-isolated and fail-closed formula resolution. */
@Service
public class DatabaseSemanticInsightContractProvider implements SemanticInsightContractProvider {
    private final SemanticInsightContractRepository repository;
    private final ObjectMapper objectMapper;
    private final SemanticInsightFieldRepository fieldRepository;
    private final SemanticInsightRecipeRepository recipeRepository;
    private final SemanticInsightRecipeParameterRepository parameterRepository;

    public DatabaseSemanticInsightContractProvider(
        SemanticInsightContractRepository repository,
        ObjectMapper objectMapper,
        SemanticInsightFieldRepository fieldRepository,
        SemanticInsightRecipeRepository recipeRepository,
        SemanticInsightRecipeParameterRepository parameterRepository
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.fieldRepository = fieldRepository;
        this.recipeRepository = recipeRepository;
        this.parameterRepository = parameterRepository;
    }

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
                || !matchesDataset(entity.getDatasetKey(), baseDataset(request.datasetReference()))
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
            List<SemanticInsightFieldEntity> fields =
                fieldRepository.findByContractIdOrderByDisplayOrderAscFieldIdAsc(entity.getContractId());
            List<SemanticInsightRecipeEntity> recipes =
                recipeRepository.findByContractIdOrderByDisplayOrderAscRecipeIdAsc(entity.getContractId());
            if (!fields.isEmpty() || !recipes.isEmpty()) {
                return structured(entity, fields, recipes);
            }
            if (blank(entity.getContractJson())) return null;
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

    private SemanticInsightContract structured(
        SemanticInsightContractEntity entity,
        List<SemanticInsightFieldEntity> fieldRows,
        List<SemanticInsightRecipeEntity> recipeRows
    ) {
        List<SemanticInsightContract.Field> fields = fieldRows.stream()
            .map(row -> new SemanticInsightContract.Field(
                row.getPhysicalField(), row.getSemanticKey(), row.getDisplayLabel(), row.getUnit(),
                row.isSensitive(), row.getAggregation()))
            .toList();
        List<SemanticInsightRecipeEntity> enabledRecipes = recipeRows.stream()
            .filter(SemanticInsightRecipeEntity::isEnabled).toList();
        Map<String, List<SemanticInsightRecipeParameterEntity>> parametersByRecipe =
            new LinkedHashMap<>();
        if (!enabledRecipes.isEmpty()) {
            parameterRepository.findByRecipeIdInOrderByRecipeIdAscDisplayOrderAscParameterIdAsc(
                enabledRecipes.stream().map(SemanticInsightRecipeEntity::getRecipeId).toList())
                .forEach(row -> parametersByRecipe
                    .computeIfAbsent(row.getRecipeId(), ignored -> new ArrayList<>()).add(row));
        }
        List<SemanticInsightContract.Recipe> recipes = enabledRecipes.stream().map(row -> {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parametersByRecipe.getOrDefault(row.getRecipeId(), List.of()).forEach(parameter -> {
                Object value = parameter.typedValue();
                if (value != null) parameters.put(parameter.getParameterKey(), value);
            });
            return new SemanticInsightContract.Recipe(
                row.getRecipeKey(), row.getOperator(), row.getLabel(), parameters,
                new SemanticInsightContract.Presentation(
                    row.getPresentationMode(), row.isConclusionEligible(),
                    row.getPresentationPriority(), row.getSectionKey(), row.getRelevanceHint()));
        }).toList();
        return new SemanticInsightContract(
            SemanticInsightContract.SCHEMA_VERSION, entity.getTenantId(), entity.getContractId(),
            entity.getContractVersion(), entity.getStatus(), entity.getDatasetAlias(), fields, recipes);
    }

    private boolean matches(String binding, String actual) {
        return blank(binding) || !blank(actual) && binding.trim().equalsIgnoreCase(actual.trim());
    }
    private boolean matchesDataset(String binding, String actual) {
        if (matches(binding, actual)) return true;
        if (blank(binding) || blank(actual)) return false;
        boolean bindingNamespaced = hasTransportNamespace(binding);
        boolean actualNamespaced = hasTransportNamespace(actual);
        return bindingNamespaced != actualNamespaced
            && resourceKey(binding).equalsIgnoreCase(resourceKey(actual));
    }
    private boolean hasNoApplicabilityBinding(SemanticInsightContractEntity entity) {
        return blank(entity.getAgentId()) && blank(entity.getToolName())
            && blank(entity.getDatasetKey()) && blank(entity.getTaskType());
    }
    private String baseDataset(String value) {
        return value == null ? null : value.replaceFirst("#occurrence-[0-9]+$", "");
    }
    /** Accepts a transport namespace without coupling Runtime to a concrete provider name. */
    private String resourceKey(String value) {
        String normalized = value == null ? "" : value.trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) return normalized;
        String namespace = normalized.substring(0, separator);
        return namespace.matches("[A-Za-z][A-Za-z0-9_.-]*")
            ? normalized.substring(separator + 1).trim() : normalized;
    }
    private boolean hasTransportNamespace(String value) {
        return !resourceKey(value).equals(value == null ? "" : value.trim());
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
