package com.chatchat.chat.dag;

import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Database-backed, fail-closed source for immutable DAG execution rules. */
@Service
@RequiredArgsConstructor
public class DagGovernanceContractService implements DagGovernanceContractProvider {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final DagGovernanceContractRepository repository;
    private final ObjectMapper objectMapper;
    private volatile ContractSnapshot activeContract;

    @PostConstruct
    public void initialize() {
        activeContract = loadOrBootstrap();
    }

    @Override
    public ContractSnapshot activeContract() {
        ContractSnapshot current = activeContract;
        if (current == null) {
            synchronized (this) {
                current = activeContract;
                if (current == null) {
                    current = loadOrBootstrap();
                    activeContract = current;
                }
            }
        }
        return current;
    }

    private ContractSnapshot loadOrBootstrap() {
        List<DagGovernanceContractEntity> active = repository
            .findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(CONTRACT_KEY);
        if (active.size() > 1) {
            throw new IllegalStateException("Multiple active immutable DAG governance contracts found for "
                + CONTRACT_KEY);
        }
        DagGovernanceContractEntity entity;
        if (active.isEmpty()) {
            if (repository.existsByContractKey(CONTRACT_KEY)) {
                throw new IllegalStateException("DAG governance contracts exist but none is active for "
                    + CONTRACT_KEY);
            }
            entity = bootstrapV1();
        } else {
            entity = active.get(0);
        }
        if (!entity.isImmutable()) {
            throw new IllegalStateException("Active DAG governance contract must be immutable: "
                + entity.getContractId());
        }
        Map<String, Object> rules = readRules(entity.getRulesJson());
        String canonicalJson = writeRules(rules);
        String checksum = checksum(canonicalJson);
        if (!checksum.equalsIgnoreCase(entity.getChecksumSha256())) {
            throw new IllegalStateException("DAG governance contract checksum mismatch: "
                + entity.getContractId());
        }
        validateSupportedContract(entity, rules);
        return new ContractSnapshot(
            entity.getContractId(), entity.getContractKey(), entity.getContractVersion(),
            rules, entity.getChecksumSha256()
        );
    }

    private DagGovernanceContractEntity bootstrapV1() {
        Map<String, Object> rules = DagGovernanceContractProvider.defaultV1Rules();
        String json = writeRules(rules);
        DagGovernanceContractEntity entity = new DagGovernanceContractEntity();
        entity.setContractId(INITIAL_VERSION);
        entity.setContractKey(CONTRACT_KEY);
        entity.setContractVersion(INITIAL_VERSION);
        entity.setRulesJson(json);
        entity.setChecksumSha256(checksum(json));
        entity.setEnabled(true);
        entity.setImmutable(true);
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException concurrentBootstrap) {
            return repository.findById(INITIAL_VERSION).orElseThrow(() -> concurrentBootstrap);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateSupportedContract(DagGovernanceContractEntity entity, Map<String, Object> rules) {
        if (!entity.getContractVersion().equals(rules.get("contractVersion"))) {
            throw new IllegalStateException("DAG governance contract version does not match rules: "
                + entity.getContractId());
        }
        if (!Boolean.TRUE.equals(rules.get("immutable"))) {
            throw new IllegalStateException("DAG governance rules must declare immutable=true: "
                + entity.getContractId());
        }
        requireTrue(rules, "topology", "requireUniqueNodeIds");
        requireTrue(rules, "topology", "rejectCycles");
        requireTrue(rules, "topology", "requireDeclaredDependencies");
        requireTrue(rules, "repair", "deterministicRepairFirst");
        requireFalse(rules, "repair", "modelMayChangeAuthoritativeTopology");
        requireTrue(rules, "repair", "requireRevalidationAfterRepair");
        requireTrue(rules, "retry", "boundedAttempts");
        requireTrue(rules, "persistence", "pinContractToExecutionSnapshot");
        requireFalse(rules, "persistence", "allowStartupOverwrite");
        Object execution = rules.get("execution");
        Object readyPolicy = execution instanceof Map<?, ?> map ? map.get("readyNodePolicy") : null;
        if (!"ALL_REQUIRED_DEPENDENCIES_COMMITTED".equals(readyPolicy)) {
            throw new IllegalStateException("Unsupported DAG ready-node policy in " + entity.getContractId());
        }
    }

    private void requireTrue(Map<String, Object> rules, String section, String key) {
        if (!Boolean.TRUE.equals(sectionValue(rules, section, key))) {
            throw new IllegalStateException("DAG governance invariant must be true: " + section + "." + key);
        }
    }

    private void requireFalse(Map<String, Object> rules, String section, String key) {
        if (!Boolean.FALSE.equals(sectionValue(rules, section, key))) {
            throw new IllegalStateException("DAG governance invariant must be false: " + section + "." + key);
        }
    }

    private Object sectionValue(Map<String, Object> rules, String section, String key) {
        Object value = rules.get(section);
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private Map<String, Object> readRules(String json) {
        try {
            Map<String, Object> rules = objectMapper.readValue(json, OBJECT_MAP);
            if (rules == null || rules.isEmpty()) {
                throw new IllegalStateException("DAG governance contract rules cannot be empty");
            }
            return Map.copyOf(new LinkedHashMap<>(rules));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid DAG governance contract JSON", ex);
        }
    }

    private String writeRules(Map<String, Object> rules) {
        try {
            return objectMapper.writeValueAsString(new java.util.TreeMap<>(rules));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize DAG governance contract", ex);
        }
    }

    private String checksum(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
