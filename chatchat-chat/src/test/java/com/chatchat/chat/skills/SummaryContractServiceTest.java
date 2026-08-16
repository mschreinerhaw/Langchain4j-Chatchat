package com.chatchat.chat.skills;

import com.chatchat.chat.contract.ContractRuleRecordCodec;
import com.chatchat.chat.contract.RuntimeContractRuleSchemaMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryContractServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bootstrapsV1OnlyWhenDatabaseHasNoContract() {
        SummaryContractRepository repository = mock(SummaryContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY)).thenReturn(List.of());
        when(repository.saveAndFlush(any(SummaryContractEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SummaryContractService service = service(repository);

        service.initialize();

        assertThat(service.recordAnalysisPolicy())
            .containsEntry("contractVersion", "record_grounded_analysis.v1")
            .containsEntry("immutable", true);
        verify(repository).saveAndFlush(any(SummaryContractEntity.class));
    }

    @Test
    void databaseContractIsAuthoritativeAndNeverOverwrittenAtStartup() throws Exception {
        String json = objectMapper.writeValueAsString(new java.util.TreeMap<>(java.util.Map.of(
            "contractVersion", "record_grounded_analysis.v9",
            "immutable", true,
            "requireCompleteRecordCoverage", true
        )));
        SummaryContractEntity entity = entity(json, sha256(json));
        entity.setContractVersion("record_grounded_analysis.v9");
        entity.setContractId("record_grounded_analysis.v9");
        SummaryContractRepository repository = mock(SummaryContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY)).thenReturn(List.of(entity));
        SummaryContractService service = service(repository);

        service.initialize();

        assertThat(service.activeRecordAnalysisContract().contractVersion())
            .isEqualTo("record_grounded_analysis.v9");
        assertThat(service.recordAnalysisPolicy()).containsEntry("contractVersion", "record_grounded_analysis.v9");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refusesTamperedImmutableContract() {
        SummaryContractRepository repository = mock(SummaryContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY))
            .thenReturn(List.of(entity("{\"immutable\":true}", "bad-checksum")));
        SummaryContractService service = service(repository);

        assertThatThrownBy(service::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void doesNotRecreateOrOverwriteWhenOnlyInactiveVersionsExist() {
        SummaryContractRepository repository = mock(SummaryContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY)).thenReturn(List.of());
        when(repository.existsByContractKey(SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY)).thenReturn(true);
        SummaryContractService service = service(repository);

        assertThatThrownBy(service::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("none is active");
        verify(repository, never()).saveAndFlush(any());
    }

    private SummaryContractEntity entity(String json, String checksum) {
        SummaryContractEntity entity = new SummaryContractEntity();
        entity.setContractId("record_grounded_analysis.v1");
        entity.setContractKey(SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY);
        entity.setContractVersion("record_grounded_analysis.v1");
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

    private SummaryContractService service(SummaryContractRepository repository) {
        return new SummaryContractService(
            repository, objectMapper, new ContractRuleRecordCodec(), mock(RuntimeContractRuleSchemaMigrator.class)
        );
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
