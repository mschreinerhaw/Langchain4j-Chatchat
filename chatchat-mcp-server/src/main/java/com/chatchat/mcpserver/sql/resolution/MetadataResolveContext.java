package com.chatchat.mcpserver.sql.resolution;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;

public record MetadataResolveContext(
    String tableName,
    String preferredSchema,
    SqlDatasourceConfig datasource
) {
}
