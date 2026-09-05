package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.common.runtime.summary.analysis.semantic.model.CapabilityEvidenceClaimContract;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticOperation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adapts extensible producer metadata to the common semantic admission contract. */
public final class CapabilityEvidenceClaimCompiler {

    public CapabilityEvidenceClaimContract.Capability compile(String fallbackCapabilityId,
                                                               Map<String, Object> semanticContract) {
        Map<String, Object> contract = map(semanticContract);
        Map<String, Object> capability = map(contract.get("capability"));
        String capabilityId = first(capability, "capabilityId", "id", "code", "name");
        if (capabilityId.isBlank()) capabilityId = clean(fallbackCapabilityId);

        Set<SemanticOperation> operations = new LinkedHashSet<>();
        collectOperations(contract, operations);
        if (containsNonEmptyKey(contract, "allowedDerivedMeasures")) operations.add(SemanticOperation.DERIVE);
        if (containsNonEmptyKey(contract, "allowedInferences")) operations.add(SemanticOperation.INFER);
        if (containsNonEmptyKey(contract, "proxyRelationships")) operations.add(SemanticOperation.PROXY);
        operations.add(SemanticOperation.OBSERVE);

        Set<String> semanticBasis = new LinkedHashSet<>();
        collectValuesForKeys(contract, Set.of(
            "semanticBasis", "semanticStatements", "allowedDerivedMeasures", "allowedInferences",
            "proxyRelationships", "authorization", "formula", "expression", "rule"), semanticBasis);
        Set<String> fields = new LinkedHashSet<>();
        collectValuesForKeys(contract.get("schema"), Set.of(
            "field", "fieldName", "name", "key", "code", "technicalName"), fields);

        return new CapabilityEvidenceClaimContract.Capability(
            capabilityId, clean(contract.get("semanticAuthority")), operations, semanticBasis, fields,
            valuesFor(contract, "unit", "units", "outputUnit"),
            valuesFor(contract, "grain", "grains", "dataGrain"),
            valuesFor(contract, "timeScope", "timeScopes", "timeRange"),
            valuesFor(contract, "populationScope", "populationScopes", "coverageScope"));
    }

    private Set<String> valuesFor(Object value, String... keys) {
        Set<String> result = new LinkedHashSet<>();
        collectValuesForKeys(value, Set.of(keys), result);
        return result;
    }

    private void collectOperations(Object value, Set<SemanticOperation> target) {
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null && Set.of("allowedOperations", "operations", "operation")
                    .contains(String.valueOf(key))) collectOperationValues(item, target);
                collectOperations(item, target);
            });
        } else if (value instanceof Iterable<?> source) source.forEach(item -> collectOperations(item, target));
    }

    private void collectOperationValues(Object value, Set<SemanticOperation> target) {
        if (value instanceof Iterable<?> source) source.forEach(item -> addOperation(item, target));
        else addOperation(value, target);
    }

    private void addOperation(Object value, Set<SemanticOperation> target) {
        SemanticOperation operation = SemanticOperation.from(clean(value));
        if (operation != null) target.add(operation);
    }

    private boolean containsNonEmptyKey(Object value, String expected) {
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (expected.equals(String.valueOf(entry.getKey())) && !empty(entry.getValue())) return true;
                if (containsNonEmptyKey(entry.getValue(), expected)) return true;
            }
        } else if (value instanceof Iterable<?> source) {
            for (Object item : source) if (containsNonEmptyKey(item, expected)) return true;
        }
        return false;
    }

    private void collectValuesForKeys(Object value, Set<String> keys, Set<String> target) {
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null && keys.contains(String.valueOf(key))) collectScalarValues(item, target);
                collectValuesForKeys(item, keys, target);
            });
        } else if (value instanceof Iterable<?> source) source.forEach(item -> collectValuesForKeys(item, keys, target));
    }

    private void collectScalarValues(Object value, Set<String> target) {
        if (value instanceof Map<?, ?> source) source.values().forEach(item -> collectScalarValues(item, target));
        else if (value instanceof Iterable<?> source) source.forEach(item -> collectScalarValues(item, target));
        else {
            String scalar = clean(value);
            if (!scalar.isBlank()) target.add(scalar);
        }
    }

    private String first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = clean(source.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private boolean empty(Object value) {
        if (value == null) return true;
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value instanceof Iterable<?> iterable) return !iterable.iterator().hasNext();
        return clean(value).isBlank();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String clean(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
