package com.chatchat.chat.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeContractRuleSchemaMigratorTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void migratesLegacyJsonToTypedRowsBeforeRetiringTheColumn() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(true, false);
        when(jdbcTemplate.queryForObject(
            eq("select count(*) from runtime_summary_contract_rule where contract_id = ?"),
            eq(Integer.class), eq("summary.v1")
        )).thenReturn(0);

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("summary.v1");
        when(resultSet.getString(2)).thenReturn(
            "{\"contractVersion\":\"summary.v1\",\"immutable\":true}"
        );
        when(jdbcTemplate.query(
            eq("select contract_id, rules_json from runtime_summary_contract"), any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        RuntimeContractRuleSchemaMigrator migrator = new RuntimeContractRuleSchemaMigrator(
            jdbcTemplate, new ObjectMapper(), new ContractRuleRecordCodec()
        );
        migrator.migrateIfNeeded();

        ArgumentCaptor<List<Object[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(eq(
            "insert into runtime_summary_contract_rule (contract_id, storage_order, array_index, parent_path, "
                + "rule_key, rule_path, value_text, value_type) values (?, ?, ?, ?, ?, ?, ?, ?)"
        ), rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue().get(0))
            .containsExactly("summary.v1", 0, null, "", "contractVersion", "/contractVersion", "summary.v1", "STRING");
        verify(jdbcTemplate).execute("alter table runtime_summary_contract drop column rules_json");
    }
}
