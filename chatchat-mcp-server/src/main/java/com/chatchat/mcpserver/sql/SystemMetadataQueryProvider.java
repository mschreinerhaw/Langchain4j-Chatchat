package com.chatchat.mcpserver.sql;

import java.util.Set;

class SystemMetadataQueryProvider {

    boolean supportsMetadataIndexType(String datasourceType) {
        return Set.of("mysql", "mariadb", "tdsql", "tidb", "oceanbase", "postgresql", "sqlserver", "oracle", "dm", "inceptor")
            .contains(datasourceType);
    }

    MetadataSql databaseSql(String datasourceType) {
        String sql = switch (datasourceType) {
            case "postgresql" -> "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog', 'information_schema') ORDER BY schema_name";
            case "sqlserver" -> "SELECT name AS schema_name FROM sys.databases ORDER BY name";
            case "dm" -> """
                SELECT schema_name
                FROM (
                    SELECT DISTINCT owner AS schema_name FROM all_tables
                    UNION
                    SELECT DISTINCT owner AS schema_name FROM all_views
                ) dm_schemas
                WHERE schema_name NOT IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO', 'SYSDBO', 'SYSJOB', 'CTISYS')
                ORDER BY schema_name
                """;
            case "inceptor" -> """
                SELECT database_name AS schema_name
                FROM (
                    SELECT database_name FROM `system`.`tables_v`
                    UNION
                    SELECT database_name FROM `system`.`views_v`
                ) d
                ORDER BY database_name
                """;
            default -> "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY schema_name";
        };
        return new MetadataSql(sql, 0);
    }

    MetadataSql tableIndexSql(String datasourceType) {
        String sql = switch (datasourceType) {
            case "oracle" -> """
                SELECT owner AS table_schema,
                       table_name AS table_name,
                       'BASE TABLE' AS table_type,
                       num_rows AS table_rows,
                       comments AS table_comment
                FROM (
                    SELECT t.owner, t.table_name, t.num_rows, c.comments
                    FROM all_tables t
                    LEFT JOIN all_tab_comments c
                      ON c.owner = t.owner AND c.table_name = t.table_name
                    WHERE t.owner NOT IN ('SYS', 'SYSTEM')
                )
                ORDER BY owner, table_name
                """;
            case "sqlserver" -> """
                SELECT s.name AS table_schema,
                       t.name AS table_name,
                       'BASE TABLE' AS table_type,
                       CAST(NULL AS BIGINT) AS table_rows,
                       CAST(ep.value AS NVARCHAR(4000)) AS table_comment
                FROM sys.tables t
                INNER JOIN sys.schemas s ON s.schema_id = t.schema_id
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = t.object_id
                 AND ep.minor_id = 0
                 AND ep.name = 'MS_Description'
                ORDER BY s.name, t.name
                """;
            case "postgresql" -> """
                SELECT t.table_schema,
                       t.table_name,
                       t.table_type,
                       CAST(NULL AS BIGINT) AS table_rows,
                       obj_description(c.oid) AS table_comment
                FROM information_schema.tables t
                LEFT JOIN pg_catalog.pg_namespace n
                  ON n.nspname = t.table_schema
                LEFT JOIN pg_catalog.pg_class c
                  ON c.relnamespace = n.oid AND c.relname = t.table_name
                WHERE t.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY t.table_schema, t.table_name
                """;
            case "dm" -> """
                SELECT table_schema, table_name, table_type, table_rows, table_comment
                FROM (
                    SELECT t.owner AS table_schema,
                           t.table_name AS table_name,
                           'BASE TABLE' AS table_type,
                           CAST(NULL AS BIGINT) AS table_rows,
                           tc.comments AS table_comment
                    FROM all_tables t
                    LEFT JOIN all_tab_comments tc
                      ON tc.owner = t.owner
                     AND tc.table_name = t.table_name
                    WHERE t.owner NOT IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO', 'SYSDBO', 'SYSJOB', 'CTISYS')
                      AND (? IS NULL OR t.owner = ?)
                    UNION ALL
                    SELECT v.owner AS table_schema,
                           v.view_name AS table_name,
                           'VIEW' AS table_type,
                           CAST(NULL AS BIGINT) AS table_rows,
                           tc.comments AS table_comment
                    FROM all_views v
                    LEFT JOIN all_tab_comments tc
                      ON tc.owner = v.owner
                     AND tc.table_name = v.view_name
                    WHERE v.owner NOT IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO', 'SYSDBO', 'SYSJOB', 'CTISYS')
                      AND (? IS NULL OR v.owner = ?)
                ) dm_tables
                ORDER BY table_schema, table_name
                """;
            case "inceptor" -> """
                SELECT metadata_table.database_name AS table_schema,
                       metadata_table.table_name AS table_name,
                       metadata_table.table_type AS table_type,
                       CAST(NULL AS BIGINT) AS table_rows,
                       metadata_table.commentstring AS table_comment
                FROM (
                    SELECT `database_name`,
                           `table_name`,
                           cast('TABLE' AS string) AS `table_type`,
                           `commentstring`,
                           `create_time`
                    FROM `system`.`tables_v`
                    UNION ALL
                    SELECT `database_name`,
                           `view_name` AS `table_name`,
                           'VIEW' AS `table_type`,
                           NULL AS `commentstring`,
                           `create_time`
                    FROM `system`.`views_v`
                ) metadata_table
                WHERE (? IS NULL OR metadata_table.database_name = ?)
                ORDER BY metadata_table.database_name, metadata_table.table_name
                """;
            default -> """
                SELECT table_schema, table_name, table_type, table_rows, table_comment
                FROM information_schema.tables
                WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                ORDER BY table_schema, table_name
                """;
        };
        return new MetadataSql(sql, switch (datasourceType) {
            case "dm" -> 4;
            case "inceptor" -> 2;
            default -> 0;
        });
    }

    MetadataSql columnIndexSql(String datasourceType) {
        String sql = switch (datasourceType) {
            case "oracle" -> """
                SELECT c.owner AS table_schema,
                       c.table_name AS table_name,
                       c.column_name AS column_name,
                       c.data_type AS data_type,
                       c.data_type AS column_type,
                       CAST(NULL AS VARCHAR2(20)) AS column_key,
                       cc.comments AS column_comment,
                       c.nullable AS is_nullable,
                       c.column_id AS ordinal_position
                FROM all_tab_columns c
                LEFT JOIN all_col_comments cc
                  ON cc.owner = c.owner
                 AND cc.table_name = c.table_name
                 AND cc.column_name = c.column_name
                WHERE c.owner NOT IN ('SYS', 'SYSTEM')
                ORDER BY c.owner, c.table_name, c.column_id
                """;
            case "postgresql" -> """
                SELECT c.table_schema, c.table_name, c.column_name, c.data_type,
                       c.data_type AS column_type, CAST(NULL AS VARCHAR) AS column_key,
                       pg_catalog.col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass::oid, c.ordinal_position) AS column_comment,
                       c.is_nullable, c.ordinal_position
                FROM information_schema.columns c
                WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY c.table_schema, c.table_name, c.ordinal_position
                """;
            case "sqlserver" -> """
                SELECT c.table_schema, c.table_name, c.column_name, c.data_type,
                       c.data_type AS column_type, CAST(NULL AS VARCHAR) AS column_key,
                       CAST(ep.value AS NVARCHAR(4000)) AS column_comment,
                       c.is_nullable, c.ordinal_position
                FROM information_schema.columns c
                LEFT JOIN sys.schemas s ON s.name = c.table_schema
                LEFT JOIN sys.tables t ON t.name = c.table_name AND t.schema_id = s.schema_id
                LEFT JOIN sys.columns sc ON sc.object_id = t.object_id AND sc.name = c.column_name
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = sc.object_id
                 AND ep.minor_id = sc.column_id
                 AND ep.name = 'MS_Description'
                ORDER BY c.table_schema, c.table_name, c.ordinal_position
                """;
            case "dm" -> """
                SELECT c.owner AS table_schema,
                       c.table_name AS table_name,
                       c.column_name AS column_name,
                       c.data_type AS data_type,
                       c.data_type AS column_type,
                       CAST(NULL AS VARCHAR(20)) AS column_key,
                       cc.comments AS column_comment,
                       CASE WHEN c.nullable = 'Y' THEN 'YES' ELSE 'NO' END AS is_nullable,
                       c.column_id AS ordinal_position
                FROM all_tab_columns c
                LEFT JOIN all_col_comments cc
                  ON cc.owner = c.owner
                 AND cc.table_name = c.table_name
                 AND cc.column_name = c.column_name
                WHERE c.owner NOT IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO', 'SYSDBO', 'SYSJOB', 'CTISYS')
                  AND (? IS NULL OR c.owner = ?)
                ORDER BY c.owner, c.table_name, c.column_id
                """;
            case "inceptor" -> inceptorColumnIndexSql();
            default -> """
                SELECT table_schema, table_name, column_name, data_type, column_type, column_key,
                       column_comment, is_nullable, ordinal_position
                FROM information_schema.columns
                WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                ORDER BY table_schema, table_name, ordinal_position
                """;
        };
        return new MetadataSql(sql, switch (datasourceType) {
            case "dm" -> 2;
            case "inceptor" -> 6;
            default -> 0;
        });
    }

    private String inceptorColumnIndexSql() {
        return """
            SELECT metadata_column.ordinal_position,
                   metadata_column.table_schema,
                   metadata_column.db_name,
                   metadata_column.table_name,
                   metadata_column.column_name,
                   metadata_column.col_name,
                   metadata_column.column_comment,
                   metadata_column.data_type,
                   metadata_column.column_type,
                   metadata_column.column_key,
                   metadata_column.is_nullable,
                   metadata_column.`length`,
                   metadata_column.`scale`,
                   metadata_column.default_value,
                   metadata_column.partition_keys,
                   metadata_column.bucket_column,
                   metadata_column.is_range_partitioned
            FROM (
                SELECT b.column_id AS ordinal_position,
                       a.database_name AS table_schema,
                       a.database_name AS db_name,
                       a.`table_name` AS table_name,
                       b.`column_name` AS column_name,
                       b.`column_name` AS col_name,
                       b.`commentstring` AS column_comment,
                       b.`column_type` AS data_type,
                       b.`column_type` AS column_type,
                       (CASE WHEN b.`unique_constraint` = TRUE THEN 'PRI' ELSE '' END) AS column_key,
                       (CASE WHEN b.`nullable` = true THEN 'YES' ELSE 'NO' END) AS is_nullable,
                       b.`column_length` AS `length`,
                       b.`column_scale` AS `scale`,
                       b.`default_value` AS default_value,
                       (SELECT collect_list(column_name)
                        FROM `system`.`partition_keys_v` c
                        WHERE a.`database_name` = c.`database_name`
                          AND a.`table_name` = c.`table_name`) AS partition_keys,
                       (SELECT bucket_column
                        FROM `system`.`buckets_v` c
                        WHERE a.`database_name` = c.`database_name`
                          AND a.`table_name` = c.`table_name`) AS bucket_column,
                       (CASE WHEN (SELECT sum(1)
                                   FROM `system`.`partition_keys_v` c
                                   WHERE a.`database_name` = c.`database_name`
                                     AND a.`table_name` = c.`table_name`
                                     AND is_range_partitioned = true) > 0
                             THEN true ELSE false END) AS is_range_partitioned
                FROM (
                    SELECT `database_name`,
                           `table_name`,
                           `table_format`,
                           `table_location`,
                           `transactional`,
                           cast('TABLE' AS string) AS `table_type`,
                           `commentstring`,
                           `create_time`
                    FROM `system`.`tables_v`
                    UNION ALL
                    SELECT `database_name`,
                           `view_name` AS `table_name`,
                           NULL AS `table_format`,
                           NULL AS `table_location`,
                           'false' AS `transactional`,
                           'VIEW' AS `table_type`,
                           NULL AS `commentstring`,
                           `create_time`
                    FROM `system`.`views_v`
                ) a
                JOIN `system`.`columns_v` b
                  ON (? IS NULL OR a.`database_name` = ?)
                 AND (? IS NULL OR b.`database_name` = ?)
                 AND a.`database_name` = b.`database_name`
                 AND a.`table_name` = b.`table_name`
                UNION ALL
                SELECT 999 AS ordinal_position,
                       `database_name` AS table_schema,
                       `database_name` AS db_name,
                       `table_name` AS table_name,
                       column_name AS column_name,
                       column_name AS col_name,
                       `commentstring` AS column_comment,
                       `column_type` AS data_type,
                       `column_type` AS column_type,
                       (CASE WHEN `unique_constraint` = TRUE THEN 'PRI' ELSE '' END) AS column_key,
                       (CASE WHEN `nullable` = true THEN 'YES' ELSE 'NO' END) AS is_nullable,
                       `column_length` AS `length`,
                       0 AS `scale`,
                       `default_value` AS default_value,
                       ARRAY(column_name) AS partition_keys,
                       '' AS bucket_column,
                       `is_range_partitioned` AS is_range_partitioned
                FROM `system`.`partition_keys_v`
                WHERE (? IS NULL OR `database_name` = ?)
            ) metadata_column
            ORDER BY metadata_column.table_schema, metadata_column.table_name, metadata_column.ordinal_position
            """;
    }

    record MetadataSql(String sql, int databaseNameParameterCount) {
    }
}
