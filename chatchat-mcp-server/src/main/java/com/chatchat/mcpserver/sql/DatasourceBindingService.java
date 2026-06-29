package com.chatchat.mcpserver.sql;

import org.springframework.stereotype.Service;

@Service
public class DatasourceBindingService {

    public double datasourceAffinity(SqlDatasourceConfig datasource, TableLocation candidate) {
        if (datasource == null || candidate == null || candidate.datasourceId() == null) {
            return 0.0;
        }
        return candidate.datasourceId().equals(datasource.getId()) ? 1.0 : 0.0;
    }
}
