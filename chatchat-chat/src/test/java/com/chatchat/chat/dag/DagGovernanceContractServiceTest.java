package com.chatchat.chat.dag;

import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.chat.contract.ContractRuleRecordCodec;
import com.chatchat.chat.contract.RuntimeContractRuleSchemaMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DagGovernanceContractServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bootstrapsV1OnlyWhenContractKeyNeverExisted() {
        DagGovernanceContractRepository repository = mock(DagGovernanceContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            DagGovernanceContractProvider.CONTRACT_KEY)).thenReturn(List.of());
        when(repository.saveAndFlush(any(DagGovernanceContractEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        DagGovernanceContractService service = service(repository);

        service.initialize();

        assertThat(service.activeContract().contractVersion())
            .isEqualTo(DagGovernanceContractProvider.INITIAL_VERSION);
        assertThat(service.activeContract().rules()).containsEntry("immutable", true);
        verify(repository).saveAndFlush(any(DagGovernanceContractEntity.class));
    }

    @Test
    void loadsValidDatabaseContractWithoutOverwritingIt() throws Exception {
        Map<String, Object> rules = DagGovernanceContractProvider.defaultV1Rules();
        String json = objectMapper.writeValueAsString(new TreeMap<>(rules));
        DagGovernanceContractEntity entity = entity(json, sha256(json));
        DagGovernanceContractRepository repository = mock(DagGovernanceContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            DagGovernanceContractProvider.CONTRACT_KEY)).thenReturn(List.of(entity));
        DagGovernanceContractService service = service(repository);

        service.initialize();

        assertThat(service.activeContract().checksumSha256()).isEqualTo(sha256(json));
        assertThat(service.activeContract().toRuntimeAttribute())
            .containsEntry("contractId", DagGovernanceContractProvider.INITIAL_VERSION);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refusesChecksumTampering() {
        DagGovernanceContractRepository repository = mock(DagGovernanceContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            DagGovernanceContractProvider.CONTRACT_KEY))
            .thenReturn(List.of(entity("{\"immutable\":true}", "bad-checksum")));
        DagGovernanceContractService service = service(repository);

        assertThatThrownBy(service::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void refusesUnsupportedMutableTopologyRuleEvenWithValidChecksum() throws Exception {
        Map<String, Object> rules = new java.util.LinkedHashMap<>(
            DagGovernanceContractProvider.defaultV1Rules());
        rules.put("repair", Map.of(
            "deterministicRepairFirst", true,
            "modelMayChangeAuthoritativeTopology", true,
            "requireAuditEvent", true,
            "requireRevalidationAfterRepair", true
        ));
        String json = objectMapper.writeValueAsString(new TreeMap<>(rules));
        DagGovernanceContractRepository repository = mock(DagGovernanceContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            DagGovernanceContractProvider.CONTRACT_KEY)).thenReturn(List.of(entity(json, sha256(json))));
        DagGovernanceContractService service = service(repository);

        assertThatThrownBy(service::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("modelMayChangeAuthoritativeTopology");
    }

    @Test
    void neverRecreatesOrOverwritesInactiveHistory() {
        DagGovernanceContractRepository repository = mock(DagGovernanceContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            DagGovernanceContractProvider.CONTRACT_KEY)).thenReturn(List.of());
        when(repository.existsByContractKey(DagGovernanceContractProvider.CONTRACT_KEY)).thenReturn(true);
        DagGovernanceContractService service = service(repository);

        assertThatThrownBy(service::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("none is active");
        verify(repository, never()).saveAndFlush(any());
    }

    private DagGovernanceContractEntity entity(String json, String checksum) {
        DagGovernanceContractEntity entity = new DagGovernanceContractEntity();
        entity.setContractId(DagGovernanceContractProvider.INITIAL_VERSION);
        entity.setContractKey(DagGovernanceContractProvider.CONTRACT_KEY);
        entity.setContractVersion(DagGovernanceContractProvider.INITIAL_VERSION);
        try {
            entity.setRuleNodes(new java.util.ArrayList<>(new ContractRuleRecordCodec().flatten(
                objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { })
            )));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        entity.setChecksumSha256(checksum);
        entity.setEnabled(true);
        entity.setImmutable(true);
        return entity;
    }

    private DagGovernanceContractService service(DagGovernanceContractRepository repository) {
        return new DagGovernanceContractService(
            repository, objectMapper, new ContractRuleRecordCodec(), mock(RuntimeContractRuleSchemaMigrator.class)
        );
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
