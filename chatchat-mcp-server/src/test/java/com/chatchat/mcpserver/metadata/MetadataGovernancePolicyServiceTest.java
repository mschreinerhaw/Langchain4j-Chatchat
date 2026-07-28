package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataGovernancePolicyServiceTest {

    @Test
    void dynamicallyReloadsSavedDatabasePolicyAndEnforcesRevision() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MetadataGovernancePolicyRepository repository =
            mock(MetadataGovernancePolicyRepository.class);
        AtomicReference<MetadataGovernancePolicyEntity> stored = new AtomicReference<>(
            entity(objectMapper, EnterpriseMetadataTestProperties.policy(), 3));
        when(repository.findFirstByEnabledTrueOrderByRevisionDesc())
            .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.findByCode(MetadataGovernancePolicyService.POLICY_CODE))
            .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.save(any(MetadataGovernancePolicyEntity.class)))
            .thenAnswer(invocation -> {
                MetadataGovernancePolicyEntity value = invocation.getArgument(0);
                stored.set(value);
                return value;
            });
        MetadataGovernancePolicyService service =
            new MetadataGovernancePolicyService(repository, objectMapper);

        assertThat(service.refresh().revision()).isEqualTo(3);
        MetadataGovernancePolicy changed = EnterpriseMetadataTestProperties.policy();
        changed.getComparison().setMinimumFieldScore(0.72D);

        MetadataGovernancePolicy saved = service.save(changed, 3L);

        assertThat(saved.getComparison().getMinimumFieldScore()).isEqualTo(0.72D);
        assertThat(service.status())
            .containsEntry("revision", 4L)
            .containsEntry("source", "database");
        assertThatThrownBy(() -> service.save(changed, 3L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("revision conflict");
    }

    @Test
    void rejectsIncompletePolicyBeforeWritingDatabase() {
        MetadataGovernancePolicyService service =
            new MetadataGovernancePolicyService(
                mock(MetadataGovernancePolicyRepository.class),
                new ObjectMapper());

        assertThatThrownBy(() -> service.save(new MetadataGovernancePolicy(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }

    private MetadataGovernancePolicyEntity entity(ObjectMapper objectMapper,
                                                   MetadataGovernancePolicy policy,
                                                   long revision) throws Exception {
        MetadataGovernancePolicyEntity entity = new MetadataGovernancePolicyEntity();
        entity.setId("policy-1");
        entity.setCode(MetadataGovernancePolicyService.POLICY_CODE);
        entity.setRevision(revision);
        entity.setPolicyJson(objectMapper.writeValueAsString(policy));
        entity.setEnabled(true);
        return entity;
    }
}
