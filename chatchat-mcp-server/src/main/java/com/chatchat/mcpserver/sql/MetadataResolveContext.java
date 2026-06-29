package com.chatchat.mcpserver.sql;

public record MetadataResolveContext(
    String tableName,
    String preferredSchema,
    SqlDatasourceConfig datasource
) {
}
