package com.chatchat.chat.insight;

import com.chatchat.agents.orchestration.analysis.SemanticInsightContractProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseSemanticInsightContractProviderTest {
    private final SemanticInsightContractRepository repository = mock(SemanticInsightContractRepository.class);
    private final SemanticInsightFieldRepository fieldRepository = mock(SemanticInsightFieldRepository.class);
    private final SemanticInsightRecipeRepository recipeRepository = mock(SemanticInsightRecipeRepository.class);
    private final SemanticInsightRecipeParameterRepository parameterRepository =
        mock(SemanticInsightRecipeParameterRepository.class);
    private final DatabaseSemanticInsightContractProvider provider =
        new DatabaseSemanticInsightContractProvider(repository, new ObjectMapper(),
            fieldRepository, recipeRepository, parameterRepository);

    @Test
    void ordinaryAgentRuntimeDoesNotQueryOrExecuteFormulaContracts() {
        var result = provider.resolve(request("tenant-a", false, List.of(), "finance-agent", "positions"));
        assertThat(result.reason()).isEqualTo("analysis_not_explicitly_requested");
        verify(repository, never())
            .findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc("tenant-a", "PUBLISHED");
    }

    @Test
    void resolvesOnlyPublishedTenantScopedAndBoundContract() {
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(
                contract("contract-a", "tenant-a", "finance-agent", "positions", null),
                contract("wrong-agent", "tenant-a", "other-agent", "positions", null),
                contract("expired", "tenant-a", "finance-agent", "positions", Instant.now().minusSeconds(1))));
        var result = provider.resolve(request("tenant-a", true, List.of(), "finance-agent", "positions"));
        assertThat(result.resolved()).isTrue();
        assertThat(result.contracts()).extracting(contract -> contract.contractId()).containsExactly("contract-a");
        assertThat(result.contracts().get(0).tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void requestedContractIdCannotBypassAgentBinding() {
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(
                contract("wanted", "tenant-a", "other-agent", "positions", null)));
        var result = provider.resolve(request("tenant-a", true, List.of("wanted"), "finance-agent", "positions"));
        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).isEqualTo("no_published_applicable_contract");
    }

    @Test
    void genericRequestDoesNotApplyAnUnboundContractToEveryAgent() {
        SemanticInsightContractEntity unbound = contract("unbound", "tenant-a", null, null, null);
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(unbound));
        var result = provider.resolve(request("tenant-a", true, List.of(), "any-agent", "any-dataset"));
        assertThat(result.resolved()).isFalse();
    }

    @Test
    void matchesProviderNamespacedDatasetToRuntimeReference() {
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(
                contract("contract-a", "tenant-a", "finance-agent", "provider_kind:positions", null)));

        var result = provider.resolve(request(
            "tenant-a", true, List.of("contract-a"), "finance-agent", "positions#occurrence-2"));

        assertThat(result.resolved()).isTrue();
        assertThat(result.contracts()).extracting(contract -> contract.contractId())
            .containsExactly("contract-a");
    }

    @Test
    void doesNotCollapseTwoDifferentProviderNamespaces() {
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(
                contract("contract-a", "tenant-a", "finance-agent", "provider_one:positions", null)));

        var result = provider.resolve(request(
            "tenant-a", true, List.of("contract-a"), "finance-agent", "provider_two:positions"));

        assertThat(result.resolved()).isFalse();
    }

    @Test
    void loadsMaintainableStructuredRowsInsteadOfLegacyJson() {
        SemanticInsightContractEntity header = contract(
            "contract-a", "tenant-a", "runtime-agent", "measurements", null);
        header.setDatasetAlias("snapshot");
        header.setContractJson("{not-valid-json");
        when(repository.findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            "tenant-a", "PUBLISHED")).thenReturn(List.of(header));

        SemanticInsightFieldEntity field = new SemanticInsightFieldEntity();
        field.setFieldId("field-a"); field.setContractId("contract-a");
        field.setPhysicalField("RAW_VALUE"); field.setSemanticKey("measure");
        field.setDisplayLabel("Measured value"); field.setUnit("items"); field.setAggregation("SUM");
        when(fieldRepository.findByContractIdOrderByDisplayOrderAscFieldIdAsc("contract-a"))
            .thenReturn(List.of(field));

        SemanticInsightRecipeEntity recipe = new SemanticInsightRecipeEntity();
        recipe.setRecipeId("recipe-row-a"); recipe.setContractId("contract-a");
        recipe.setRecipeKey("measure-total"); recipe.setOperator("SUM"); recipe.setLabel("Total measure");
        recipe.setEnabled(true); recipe.setPresentationMode("SUPPORTING");
        recipe.setConclusionEligible(false); recipe.setPresentationPriority(20);
        recipe.setSectionKey("details"); recipe.setRelevanceHint("Use only when totals are requested");
        when(recipeRepository.findByContractIdOrderByDisplayOrderAscRecipeIdAsc("contract-a"))
            .thenReturn(List.of(recipe));

        SemanticInsightRecipeParameterEntity parameter = new SemanticInsightRecipeParameterEntity();
        parameter.setParameterId("parameter-a"); parameter.setRecipeId("recipe-row-a");
        parameter.setParameterKey("metric"); parameter.setValueType("STRING");
        parameter.setStringValue("measure");
        when(parameterRepository.findByRecipeIdInOrderByRecipeIdAscDisplayOrderAscParameterIdAsc(
            List.of("recipe-row-a"))).thenReturn(List.of(parameter));

        var result = provider.resolve(new SemanticInsightContractProvider.Request(
            "tenant-a", "runtime-agent", "analysis", "asset-tool", "measurements",
            true, List.of("contract-a")));

        assertThat(result.resolved()).isTrue();
        var resolved = result.contracts().get(0);
        assertThat(resolved.datasetAlias()).isEqualTo("snapshot");
        assertThat(resolved.fields()).extracting(fieldValue -> fieldValue.semantic())
            .containsExactly("measure");
        assertThat(resolved.recipes()).hasSize(1);
        assertThat(resolved.recipes().get(0).parameters()).containsEntry("metric", "measure");
        assertThat(resolved.recipes().get(0).presentation().toMap())
            .containsEntry("mode", "SUPPORTING")
            .containsEntry("conclusionEligible", false)
            .containsEntry("priority", 20)
            .containsEntry("section", "details");
    }

    private SemanticInsightContractProvider.Request request(String tenant, boolean explicit,
                                                             List<String> ids, String agent, String dataset) {
        return new SemanticInsightContractProvider.Request(tenant, agent, "analysis", "asset-tool",
            dataset, explicit, ids);
    }

    private SemanticInsightContractEntity contract(String id, String tenant, String agent,
                                                     String dataset, Instant expiresAt) {
        SemanticInsightContractEntity entity = new SemanticInsightContractEntity();
        entity.setContractId(id); entity.setTenantId(tenant); entity.setContractKey("asset-summary");
        entity.setContractVersion("1"); entity.setStatus("PUBLISHED"); entity.setEnabled(true);
        entity.setActivationMode("EXPLICIT_ONLY"); entity.setAgentId(agent); entity.setDatasetKey(dataset);
        entity.setEffectiveTo(expiresAt);
        entity.setContractJson("""
            {"tenantId":"forged-tenant","contractId":"forged-id","version":"0","status":"draft",
             "datasetAlias":"positions",
             "fields":[{"field":"marketValue","semantic":"market_value","aggregation":"SUM"}],
             "recipes":[{"id":"market-value-total","operator":"SUM","metric":"market_value"}]}
            """);
        return entity;
    }
}
