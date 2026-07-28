package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MetadataGovernancePolicyService {

    public static final String POLICY_CODE = "enterprise_metadata_governance";
    private static final List<String> REQUIRED_DIFFERENCE_CODES = List.of(
        "STANDARD_FIELD_MISSING",
        "TECHNICAL_NAME_MISMATCH",
        "DATA_TYPE_MISMATCH",
        "NULLABILITY_MISMATCH",
        "TERM_NOT_STANDARD",
        "DICTIONARY_MAPPING_MISSING"
    );

    private final MetadataGovernancePolicyRepository repository;
    private final ObjectMapper objectMapper;
    private final AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>();

    public MetadataGovernancePolicyService(MetadataGovernancePolicyRepository repository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 80)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        if (repository.findFirstByEnabledTrueOrderByRevisionDesc().isEmpty()) {
            MetadataGovernancePolicy policy = readBootstrapPolicy();
            saveInternal(policy, null);
        }
        refresh();
    }

    public MetadataGovernancePolicy current() {
        PolicySnapshot current = snapshot.get();
        if (current == null) {
            return refresh().policy();
        }
        return current.policy();
    }

    public Map<String, Object> status() {
        PolicySnapshot current = snapshot.get();
        if (current == null) current = refresh();
        return Map.of(
            "code", current.code(),
            "revision", current.revision(),
            "version", current.policy().getVersion(),
            "source", "database"
        );
    }

    @Transactional
    public MetadataGovernancePolicy save(MetadataGovernancePolicy policy, Long expectedRevision) {
        validate(policy);
        saveInternal(policy, expectedRevision);
        return refresh().policy();
    }

    @Transactional(readOnly = true)
    public PolicySnapshot refresh() {
        MetadataGovernancePolicyEntity entity = repository
            .findFirstByEnabledTrueOrderByRevisionDesc()
            .orElseThrow(() -> new IllegalStateException(
                "No active enterprise metadata governance policy is stored in the database"));
        MetadataGovernancePolicy policy = read(entity.getPolicyJson());
        validate(policy);
        PolicySnapshot loaded = new PolicySnapshot(entity.getCode(), entity.getRevision(), policy);
        snapshot.set(loaded);
        return loaded;
    }

    private void saveInternal(MetadataGovernancePolicy policy, Long expectedRevision) {
        validate(policy);
        MetadataGovernancePolicyEntity entity = repository.findByCode(POLICY_CODE)
            .orElseGet(MetadataGovernancePolicyEntity::new);
        long currentRevision = entity.getRevision();
        if (expectedRevision != null && expectedRevision != currentRevision) {
            throw new IllegalStateException(
                "Governance policy revision conflict: expected=" + expectedRevision
                    + ", actual=" + currentRevision);
        }
        entity.setCode(POLICY_CODE);
        entity.setRevision(currentRevision + 1);
        entity.setPolicyJson(write(policy));
        entity.setEnabled(true);
        repository.save(entity);
    }

    private MetadataGovernancePolicy readBootstrapPolicy() {
        try {
            ClassPathResource resource =
                new ClassPathResource("metadata-governance-default-policy.json");
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            return read(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load metadata governance bootstrap policy", ex);
        }
    }

    private MetadataGovernancePolicy read(String json) {
        try {
            return objectMapper.readValue(json, MetadataGovernancePolicy.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid metadata governance policy JSON", ex);
        }
    }

    private String write(MetadataGovernancePolicy policy) {
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize metadata governance policy", ex);
        }
    }

    private void validate(MetadataGovernancePolicy policy) {
        if (policy == null) throw new IllegalArgumentException("policy is required");
        required(policy.getVersion(), "version");
        MetadataGovernancePolicy.MetadataContract contract = policy.getMetadataContract();
        if (contract == null) throw new IllegalArgumentException("metadataContract is required");
        required(contract.getFieldType(), "metadataContract.fieldType");
        required(contract.getTermType(), "metadataContract.termType");
        required(contract.getDictionaryType(), "metadataContract.dictionaryType");
        if (contract.getRequiredBundle() == null || contract.getRequiredBundle().isEmpty()) {
            throw new IllegalArgumentException("metadataContract.requiredBundle is required");
        }
        MetadataGovernancePolicy.SearchPolicy search = policy.getSearch();
        if (search == null || search.getTermExpansionLimit() < 1
            || search.getConfidenceMaximum() <= 0) {
            throw new IllegalArgumentException("search policy is incomplete");
        }
        MetadataGovernancePolicy.ComparisonPolicy comparison = policy.getComparison();
        if (comparison == null || comparison.getMaximumDictionaryMatches() < 1) {
            throw new IllegalArgumentException("comparison policy is incomplete");
        }
        for (String code : REQUIRED_DIFFERENCE_CODES) {
            required(comparison.getDifferenceSeverities().get(code),
                "comparison.differenceSeverities." + code);
            required(comparison.getDifferenceMessages().get(code),
                "comparison.differenceMessages." + code);
        }
    }

    private void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record PolicySnapshot(String code, long revision, MetadataGovernancePolicy policy) {
    }
}
