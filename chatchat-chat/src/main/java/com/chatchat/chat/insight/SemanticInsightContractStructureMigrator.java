package com.chatchat.chat.insight;

import com.chatchat.agents.orchestration.analysis.insight.SemanticInsightRecipeCatalog;
import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Idempotently materializes legacy JSON contracts into maintainable relational rows. */
@Component
@RequiredArgsConstructor
public class SemanticInsightContractStructureMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SemanticInsightContractStructureMigrator.class);

    private final SemanticInsightContractRepository contractRepository;
    private final SemanticInsightFieldRepository fieldRepository;
    private final SemanticInsightRecipeRepository recipeRepository;
    private final SemanticInsightRecipeParameterRepository parameterRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = 0;
        for (SemanticInsightContractEntity header : contractRepository.findAll()) {
            if (fieldRepository.existsByContractId(header.getContractId())
                || recipeRepository.existsByContractId(header.getContractId())
                || header.getContractJson() == null || header.getContractJson().isBlank()) continue;
            try {
                if (migrate(header)) migrated++;
            } catch (RuntimeException ex) {
                log.warn("semantic_contract_structure_migration_failed contractId={} reason={}",
                    header.getContractId(), ex.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("semantic_contract_structure_migration_completed migratedContracts={}", migrated);
        }
    }

    private boolean migrate(SemanticInsightContractEntity header) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                header.getContractJson(), new TypeReference<Map<String, Object>>() {});
            SemanticInsightContract contract = SemanticInsightContract.fromMap(payload);
            if (contract == null || contract.fields().isEmpty()) return false;
            List<SemanticInsightFieldEntity> fields = new ArrayList<>();
            for (int index = 0; index < contract.fields().size(); index++) {
                SemanticInsightContract.Field source = contract.fields().get(index);
                SemanticInsightFieldEntity row = new SemanticInsightFieldEntity();
                row.setFieldId(stableId("field", header.getContractId(), source.semantic()));
                row.setContractId(header.getContractId()); row.setDisplayOrder(index);
                row.setPhysicalField(source.field()); row.setSemanticKey(source.semantic());
                row.setDisplayLabel(source.label()); row.setUnit(source.unit());
                row.setAggregation(source.aggregation()); row.setSensitive(source.sensitive());
                fields.add(row);
            }
            List<SemanticInsightRecipeEntity> recipes = new ArrayList<>();
            List<SemanticInsightRecipeParameterEntity> parameters = new ArrayList<>();
            for (int recipeIndex = 0; recipeIndex < contract.recipes().size(); recipeIndex++) {
                SemanticInsightContract.Recipe source = contract.recipes().get(recipeIndex);
                List<String> issues = SemanticInsightRecipeCatalog.validate(source);
                if (!issues.isEmpty()) {
                    throw new IllegalArgumentException(
                        "invalid recipe " + source.id() + ": " + String.join(", ", issues));
                }
                String recipeRowId = stableId("recipe", header.getContractId(), source.id());
                SemanticInsightRecipeEntity recipe = new SemanticInsightRecipeEntity();
                recipe.setRecipeId(recipeRowId); recipe.setContractId(header.getContractId());
                recipe.setRecipeKey(source.id()); recipe.setDisplayOrder(recipeIndex);
                recipe.setOperator(source.operator()); recipe.setLabel(source.label()); recipe.setEnabled(true);
                recipe.setPresentationMode(source.presentation().mode());
                recipe.setConclusionEligible(source.presentation().conclusionEligible());
                recipe.setPresentationPriority(source.presentation().priority());
                recipe.setSectionKey(source.presentation().section());
                recipe.setRelevanceHint(source.presentation().relevanceHint());
                recipes.add(recipe);
                int parameterIndex = 0;
                for (Map.Entry<String, Object> entry : source.parameters().entrySet()) {
                    SemanticInsightRecipeCatalog.Parameter definition =
                        SemanticInsightRecipeCatalog.parameter(source.operator(), entry.getKey());
                    parameters.add(parameter(recipeRowId, entry.getKey(), entry.getValue(),
                        definition.type(), parameterIndex++));
                }
            }
            fieldRepository.saveAll(fields);
            recipeRepository.saveAll(recipes);
            parameterRepository.saveAll(parameters);
            if ((header.getDatasetAlias() == null || header.getDatasetAlias().isBlank())
                && contract.datasetAlias() != null && !contract.datasetAlias().isBlank()) {
                header.setDatasetAlias(contract.datasetAlias());
                contractRepository.save(header);
            }
            return true;
        } catch (Exception ex) {
            throw new IllegalArgumentException("legacy contract could not be normalized", ex);
        }
    }

    private SemanticInsightRecipeParameterEntity parameter(
        String recipeId, String key, Object value, String expectedType, int order
    ) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            throw new IllegalArgumentException("nested recipe parameter is not maintainable: " + key);
        }
        SemanticInsightRecipeParameterEntity row = new SemanticInsightRecipeParameterEntity();
        row.setParameterId(stableId("parameter", recipeId, key));
        row.setRecipeId(recipeId); row.setParameterKey(key); row.setDisplayOrder(order);
        switch (expectedType) {
            case "BOOLEAN" -> {
                row.setValueType("BOOLEAN");
                row.setBooleanValue(value instanceof Boolean bool
                    ? bool : Boolean.parseBoolean(String.valueOf(value)));
            }
            case "INTEGER" -> {
                row.setValueType("INTEGER");
                row.setIntegerValue(Long.parseLong(String.valueOf(value)));
            }
            case "NUMBER" -> {
                row.setValueType("DECIMAL");
                row.setDecimalValue(new BigDecimal(String.valueOf(value)));
            }
            default -> {
                row.setValueType("STRING");
                row.setStringValue(value == null ? null : String.valueOf(value));
            }
        }
        return row;
    }

    private String stableId(String type, String owner, String key) {
        return type + "-" + UUID.nameUUIDFromBytes(
            (type + "|" + owner + "|" + key).getBytes(StandardCharsets.UTF_8));
    }
}
