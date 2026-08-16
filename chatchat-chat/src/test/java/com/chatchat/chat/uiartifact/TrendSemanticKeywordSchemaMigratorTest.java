package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendSemanticKeywordSchemaMigratorTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void splitsLegacyJsonIntoTenantKeywordRowsBeforeDroppingTheColumn() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TrendSemanticKeywordRepository repository = mock(TrendSemanticKeywordRepository.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(repository.findByTenantIdOrderBySortOrderAscKeywordAsc("tenant-a")).thenReturn(List.of());

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("tenant-a");
        when(resultSet.getString(2)).thenReturn("[\" 涨跌幅 \",\"P&L\",\"涨跌幅\"]");
        when(jdbcTemplate.query(
            eq("select tenant_id, keywords_json from ui_trend_semantic_config"), any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        TrendSemanticKeywordSchemaMigrator migrator = new TrendSemanticKeywordSchemaMigrator(
            jdbcTemplate, new ObjectMapper(), repository
        );
        migrator.migrateIfNeeded();

        var rowsCaptor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAllAndFlush(rowsCaptor.capture());
        List<TrendSemanticKeywordEntity> rows = new java.util.ArrayList<>();
        rowsCaptor.getValue().forEach(value -> rows.add((TrendSemanticKeywordEntity) value));
        assertThat(rows).extracting(TrendSemanticKeywordEntity::getTenantId).containsOnly("tenant-a");
        assertThat(rows).extracting(TrendSemanticKeywordEntity::getKeyword).containsExactly("涨跌幅", "p&l");
        assertThat(rows).extracting(TrendSemanticKeywordEntity::getSortOrder).containsExactly(0, 1);
        verify(jdbcTemplate).execute("alter table ui_trend_semantic_config drop column keywords_json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesFreshNormalizedSchemaUntouched() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TrendSemanticKeywordRepository repository = mock(TrendSemanticKeywordRepository.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);

        TrendSemanticKeywordSchemaMigrator migrator = new TrendSemanticKeywordSchemaMigrator(
            jdbcTemplate, new ObjectMapper(), repository
        );
        migrator.migrateIfNeeded();

        verify(repository, never()).saveAllAndFlush(any());
        verify(jdbcTemplate, never()).execute("alter table ui_trend_semantic_config drop column keywords_json");
    }
}
