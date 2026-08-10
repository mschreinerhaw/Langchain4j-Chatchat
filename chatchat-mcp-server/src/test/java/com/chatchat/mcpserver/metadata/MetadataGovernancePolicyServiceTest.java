package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataGovernancePolicyServiceTest {

    @Test
    void readsLegacyClaimCoverageWithoutReEmittingItsConformanceVerdicts() throws Exception {
        MetadataGovernancePolicy policy = new ObjectMapper().readValue("""
            {
              "version":"legacy-policy",
              "claimCoverage":{
                "scope":"ENTERPRISE_FIELD_METADATA",
                "supportedClaims":["legacy claim"],
                "notAssessedClaims":["complete table conformance"],
                "fullTableDesignConformanceSupported":false
              }
            }
            """, MetadataGovernancePolicy.class);

        Map<String, Object> coverage = MetadataGovernancePolicyService.evidenceCoverage(
            policy.getEvidenceCoverage(), policy.getVersion());

        assertThat(coverage)
            .containsEntry("contractVersion", "enterprise_metadata_evidence_coverage.v2")
            .containsEntry("evidenceRole", "STANDARD_REFERENCE_DATA")
            .doesNotContainKeys("supportedClaims", "notAssessedClaims", "fullTableDesignConformanceSupported");
    }

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
        changed.getEvidenceCoverage().setScope("ENTERPRISE_FIELD_METADATA");
        changed.getEvidenceCoverage().setReturnedEvidenceTypes(java.util.List.of(
            "custom standard field metadata"));

        MetadataGovernancePolicy saved = service.save(changed, 3L);

        assertThat(saved.getComparison().getMinimumFieldScore()).isEqualTo(0.72D);
        assertThat(service.status())
            .containsEntry("revision", 4L)
            .containsEntry("source", "database");
        assertThat(service.evidenceCoverage())
            .containsEntry("contractVersion", "enterprise_metadata_evidence_coverage.v2")
            .containsEntry("scope", "ENTERPRISE_FIELD_METADATA")
            .containsEntry("evidenceRole", "STANDARD_REFERENCE_DATA")
            .containsEntry("declarationSource", "metadata_governance_policy")
            .containsEntry("policyVersion", "test-policy-v1");
        assertThat((java.util.List<String>) service.evidenceCoverage().get("returnedEvidenceTypes"))
            .containsExactly("custom standard field metadata");
        assertThat(service.evidenceCoverage())
            .doesNotContainKeys("supportedClaims", "notAssessedClaims", "fullTableDesignConformanceSupported");
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
