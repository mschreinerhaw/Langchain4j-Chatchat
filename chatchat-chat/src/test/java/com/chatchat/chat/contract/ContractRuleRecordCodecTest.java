package com.chatchat.chat.contract;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRuleRecordCodecTest {

    private final ContractRuleRecordCodec codec = new ContractRuleRecordCodec();

    @Test
    void preservesNestedObjectsArraysScalarTypesAndOrder() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("contractVersion", "runtime.v1");
        rules.put("authorityOrder", List.of("USER", "RUNTIME", "MODEL"));
        rules.put("topology", Map.of("rejectCycles", true, "maxDepth", 8));

        List<ContractRuleNodeValue> rows = codec.flatten(rules);
        Map<String, Object> reconstructed = codec.assemble(rows);

        assertThat(rows).extracting(ContractRuleNodeValue::getRulePath)
            .contains("/authorityOrder", "/authorityOrder/0", "/topology/rejectCycles");
        assertThat(rows).filteredOn(row -> "/authorityOrder".equals(row.getRulePath()))
            .extracting(ContractRuleNodeValue::getValueType).containsExactly("ARRAY");
        assertThat(reconstructed).isEqualTo(rules);
        assertThat(reconstructed.get("authorityOrder")).isEqualTo(List.of("USER", "RUNTIME", "MODEL"));
    }
}
