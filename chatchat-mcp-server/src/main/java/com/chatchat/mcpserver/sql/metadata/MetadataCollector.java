package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import java.util.List;

public interface MetadataCollector {
    boolean supports(String databaseType);
    List<MetadataObject> collect(SqlDatasourceConfig datasource, List<String> namespaces) throws Exception;
}
