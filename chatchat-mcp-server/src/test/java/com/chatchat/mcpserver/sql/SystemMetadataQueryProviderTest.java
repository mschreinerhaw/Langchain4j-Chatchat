package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemMetadataQueryProviderTest {

    private final SystemMetadataQueryProvider provider = new SystemMetadataQueryProvider();

    @Test
    void inceptorMetadataSqlUsesSystemViewsAndDatabaseScopeParameters() {
        SystemMetadataQueryProvider.MetadataSql tableSql = provider.tableIndexSql("inceptor");
        SystemMetadataQueryProvider.MetadataSql columnSql = provider.columnIndexSql("inceptor");

        assertThat(provider.supportsMetadataIndexType("inceptor")).isTrue();
        assertThat(tableSql.databaseNameParameterCount()).isEqualTo(2);
        assertThat(tableSql.sql())
            .contains("`system`.`tables_v`")
            .contains("`system`.`views_v`")
            .contains("metadata_table.database_name = ?");

        assertThat(columnSql.databaseNameParameterCount()).isEqualTo(6);
        assertThat(columnSql.sql())
            .contains("`system`.`columns_v`")
            .contains("`system`.`partition_keys_v`")
            .contains("collect_list(column_name)")
            .contains("metadata_column.table_schema");
    }

    @Test
    void mysqlMetadataSqlKeepsInformationSchemaWithoutScopeParameters() {
        SystemMetadataQueryProvider.MetadataSql tableSql = provider.tableIndexSql("mysql");
        SystemMetadataQueryProvider.MetadataSql columnSql = provider.columnIndexSql("mysql");

        assertThat(tableSql.databaseNameParameterCount()).isZero();
        assertThat(columnSql.databaseNameParameterCount()).isZero();
        assertThat(tableSql.sql()).contains("information_schema.tables");
        assertThat(columnSql.sql()).contains("information_schema.columns");
    }

    @Test
    void mysqlCompatibleDatabasesReuseInformationSchemaMetadataSql() {
        for (String databaseType : java.util.List.of("oceanbase", "tdsql", "tidb")) {
            SystemMetadataQueryProvider.MetadataSql tableSql = provider.tableIndexSql(databaseType);
            SystemMetadataQueryProvider.MetadataSql columnSql = provider.columnIndexSql(databaseType);

            assertThat(provider.supportsMetadataIndexType(databaseType)).isTrue();
            assertThat(tableSql.databaseNameParameterCount()).isZero();
            assertThat(columnSql.databaseNameParameterCount()).isZero();
            assertThat(tableSql.sql()).contains("information_schema.tables");
            assertThat(columnSql.sql()).contains("information_schema.columns");
        }
    }

    @Test
    void dmMetadataSqlUsesDamengDictionaryViews() {
        SystemMetadataQueryProvider.MetadataSql databaseSql = provider.databaseSql("dm");
        SystemMetadataQueryProvider.MetadataSql tableSql = provider.tableIndexSql("dm");
        SystemMetadataQueryProvider.MetadataSql columnSql = provider.columnIndexSql("dm");

        assertThat(provider.supportsMetadataIndexType("dm")).isTrue();
        assertThat(databaseSql.sql()).contains("all_tables").contains("all_views");
        assertThat(tableSql.databaseNameParameterCount()).isEqualTo(4);
        assertThat(tableSql.sql())
            .contains("all_tables")
            .contains("all_views")
            .contains("all_tab_comments")
            .contains("t.owner = ?")
            .contains("v.owner = ?");
        assertThat(columnSql.databaseNameParameterCount()).isEqualTo(2);
        assertThat(columnSql.sql())
            .contains("all_tab_columns")
            .contains("all_col_comments")
            .contains("column_id")
            .contains("c.owner = ?");
    }
}
