package com.chatchat.chat.skills;

import com.chatchat.chat.contract.ContractRuleRecordCodec;
import com.chatchat.chat.contract.RuntimeContractRuleSchemaMigrator;
import com.fasterxml.jackson.core.JsonProcessingException;
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

/** Loads immutable runtime summary contracts from the database. */
@Service
@RequiredArgsConstructor
public class SummaryContractService {

    public static final String RECORD_ANALYSIS_CONTRACT_KEY = "record_grounded_analysis";
    public static final String INITIAL_CONTRACT_VERSION = "record_grounded_analysis.v1";
    private final SummaryContractRepository repository;
    private final ObjectMapper objectMapper;
    private final ContractRuleRecordCodec ruleCodec;
    private final RuntimeContractRuleSchemaMigrator ruleSchemaMigrator;
    private volatile ActiveContract activeContract;

    @PostConstruct
    public void initialize() {
        activeContract = loadOrBootstrap();
    }

    public Map<String, Object> recordAnalysisPolicy() {
        ActiveContract current = activeContract;
        if (current == null) {
            synchronized (this) {
                current = activeContract;
                if (current == null) {
                    current = loadOrBootstrap();
                    activeContract = current;
                }
            }
        }
        return current.rules();
    }

    public ActiveContract activeRecordAnalysisContract() {
        recordAnalysisPolicy();
        return activeContract;
    }

    private ActiveContract loadOrBootstrap() {
        ruleSchemaMigrator.migrateIfNeeded();
        List<SummaryContractEntity> active = repository
            .findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(RECORD_ANALYSIS_CONTRACT_KEY);
        if (active.size() > 1) {
            throw new IllegalStateException("Multiple active immutable summary contracts found for "
                + RECORD_ANALYSIS_CONTRACT_KEY);
        }
        SummaryContractEntity entity;
        if (active.isEmpty()) {
            if (repository.existsByContractKey(RECORD_ANALYSIS_CONTRACT_KEY)) {
                throw new IllegalStateException("Summary contracts exist but none is active for "
                    + RECORD_ANALYSIS_CONTRACT_KEY);
            }
            entity = bootstrapV1();
        } else {
            entity = active.get(0);
        }
        if (!entity.isImmutable()) {
            throw new IllegalStateException("Active summary contract must be immutable: " + entity.getContractId());
        }
        Map<String, Object> rules = ruleCodec.assemble(entity.getRuleNodes());
        String canonicalJson = writeRules(rules);
        String checksum = checksum(canonicalJson);
        if (!checksum.equalsIgnoreCase(entity.getChecksumSha256())) {
            throw new IllegalStateException("Summary contract checksum mismatch: " + entity.getContractId());
        }
        return new ActiveContract(
            entity.getContractId(), entity.getContractKey(), entity.getContractVersion(),
            Map.copyOf(rules), entity.getChecksumSha256()
        );
    }

    private SummaryContractEntity bootstrapV1() {
        Map<String, Object> rules = initialV1Rules();
        String json = writeRules(rules);
        SummaryContractEntity entity = new SummaryContractEntity();
        entity.setContractId(INITIAL_CONTRACT_VERSION);
        entity.setContractKey(RECORD_ANALYSIS_CONTRACT_KEY);
        entity.setContractVersion(INITIAL_CONTRACT_VERSION);
        entity.setRuleNodes(new java.util.ArrayList<>(ruleCodec.flatten(rules)));
        entity.setChecksumSha256(checksum(json));
        entity.setEnabled(true);
        entity.setImmutable(true);
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException concurrentBootstrap) {
            return repository.findById(INITIAL_CONTRACT_VERSION).orElseThrow(() -> concurrentBootstrap);
        }
    }

    private Map<String, Object> initialV1Rules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("contractVersion", INITIAL_CONTRACT_VERSION);
        rules.put("requireRecordGroundedAnalysis", true);
        rules.put("requireCompleteRecordCoverage", true);
        rules.put("iterativeSummarizationWhenOversized", true);
        rules.put("allowExecutionMetadataOnlyAnswer", false);
        rules.put("completionCondition", "PROCESSED_RECORD_COUNT_EQUALS_RETURNED_RECORD_COUNT");
        rules.put("immutable", true);
        return rules;
    }

    private String writeRules(Map<String, Object> rules) {
        try {
            return objectMapper.writeValueAsString(new java.util.TreeMap<>(rules));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize summary contract", ex);
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

    public record ActiveContract(
        String contractId,
        String contractKey,
        String contractVersion,
        Map<String, Object> rules,
        String checksumSha256
    ) { }
}
