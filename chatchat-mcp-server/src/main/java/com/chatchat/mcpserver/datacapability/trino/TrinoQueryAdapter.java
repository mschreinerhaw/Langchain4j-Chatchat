package com.chatchat.mcpserver.datacapability.trino;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.relational.RelationalQueryAdapter;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.execution.*;
import org.springframework.stereotype.Component;

@Component
public class TrinoQueryAdapter extends RelationalQueryAdapter {
    public TrinoQueryAdapter(SqlDatasourceConfigService c, SqlQueryExecuteService e, SqlSafetyService s) { super(c, e, s); }
    @Override public CapabilityType type() { return CapabilityType.TRINO; }
    @Override public void validate(CapabilityDefinition d) {
        super.validate(d);
        if (!connections.getById(d.connectionId()).getJdbcUrl().startsWith("jdbc:trino:"))
            throw new IllegalArgumentException("Trino requires a jdbc:trino: datasource");
        for (String key : new String[]{"catalog", "schema"}) {
            Object value = d.options().get(key);
            if (!(value instanceof String text) || !text.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("A valid Trino " + key + " is required");
        }
    }
}
