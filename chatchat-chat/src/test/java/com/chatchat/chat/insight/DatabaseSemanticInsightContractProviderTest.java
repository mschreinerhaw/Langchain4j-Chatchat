package com.chatchat.chat.insight;

import com.chatchat.agents.orchestration.SemanticInsightContractProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseSemanticInsightContractProviderTest {
    private final SemanticInsightContractRepository repository = mock(SemanticInsightContractRepository.class);
    private final DatabaseSemanticInsightContractProvider provider =
        new DatabaseSemanticInsightContractProvider(repository, new ObjectMapper());

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
