package com.chatchat.chat.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Migrates legacy immutable contract JSON blobs into typed rule records. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeContractRuleSchemaMigrator {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContractRuleRecordCodec codec;
    private volatile boolean checked;

    public synchronized void migrateIfNeeded() {
        if (checked) {
            return;
        }
        migrateTable("runtime_summary_contract", "runtime_summary_contract_rule");
        migrateTable("runtime_dag_governance_contract", "runtime_dag_governance_contract_rule");
        checked = true;
    }

    private void migrateTable(String contractTable, String ruleTable) {
        if (!columnExists(contractTable, "rules_json")) {
            return;
        }
        List<LegacyContract> contracts = jdbcTemplate.query(
            "select contract_id, rules_json from " + contractTable,
            (resultSet, rowNumber) -> new LegacyContract(resultSet.getString(1), resultSet.getString(2))
        );
        for (LegacyContract contract : contracts) {
            List<ContractRuleNodeValue> nodes = parse(contract);
            Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from " + ruleTable + " where contract_id = ?", Integer.class, contract.contractId()
            );
            int existingCount = existing == null ? 0 : existing;
            if (existingCount == 0) {
                insertNodes(ruleTable, contract.contractId(), nodes);
                existingCount = nodes.size();
            }
            if (existingCount != nodes.size()) {
                throw new IllegalStateException("Incomplete normalized rule records for " + contract.contractId());
            }
        }
        jdbcTemplate.execute("alter table " + contractTable + " drop column rules_json");
        log.info("Migrated {}.rules_json to typed records in {}", contractTable, ruleTable);
    }

    private List<ContractRuleNodeValue> parse(LegacyContract contract) {
        try {
            Map<String, Object> rules = objectMapper.readValue(contract.rulesJson(), OBJECT_MAP);
            return codec.flatten(rules);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid legacy contract rules JSON: " + contract.contractId(), ex);
        }
    }

    private void insertNodes(String ruleTable, String contractId, List<ContractRuleNodeValue> nodes) {
        String sql = "insert into " + ruleTable + " (contract_id, storage_order, array_index, parent_path, "
            + "rule_key, rule_path, value_text, value_type) values (?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> values = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            ContractRuleNodeValue node = nodes.get(index);
            values.add(new Object[] {
                contractId, index, node.getArrayIndex(), node.getParentPath(), node.getRuleKey(),
                node.getRulePath(), node.getValueText(), node.getValueType()
            });
        }
        jdbcTemplate.batchUpdate(sql, values);
    }

    private boolean columnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
                while (columns.next()) {
                    if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private record LegacyContract(String contractId, String rulesJson) { }
}
