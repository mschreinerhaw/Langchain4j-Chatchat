package com.chatchat.mcpserver.datacapability.relational;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.execution.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RelationalQueryAdapter implements CapabilityAdapter {
    protected final SqlDatasourceConfigService connections;
    protected final SqlQueryExecuteService executor;
    private final SqlSafetyService safety;

    public RelationalQueryAdapter(SqlDatasourceConfigService connections, SqlQueryExecuteService executor, SqlSafetyService safety) {
        this.connections = connections; this.executor = executor; this.safety = safety;
    }
    @Override public CapabilityType type() { return CapabilityType.RELATIONAL; }
    @Override public void validate(CapabilityDefinition d) {
        connections.getById(d.connectionId());
        if (d.timeoutSeconds() > 60) throw new IllegalArgumentException("SQL query timeout cannot exceed 60 seconds");
        if (d.query() == null || d.query().isBlank()) throw new IllegalArgumentException("SQL query is required");
        safety.validateAndNormalize(d.query().replaceAll("\\{\\{\\s*[A-Za-z0-9_]+\\s*}}", "NULL"), d.maxRows());
    }
    @Override public QueryResult execute(CapabilityDefinition d, Map<String, Object> parameters) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("datasourceId", d.connectionId()); args.put("sql", QueryTemplates.sql(d.query(), parameters));
        args.put("timeoutSeconds", d.timeoutSeconds()); args.put("maxRows", d.maxRows());
        args.put("purpose", "data_capability:" + d.code());
        if (type() == CapabilityType.TRINO) {
            args.put("catalog", d.options().get("catalog")); args.put("schema", d.options().get("schema"));
        }
        SqlQueryResult result = executor.execute(args);
        if (!result.success()) throw new IllegalStateException(result.errorMessage());
        return new QueryResult(result.rows(), result.possiblyTruncated(), Map.of("columns", result.columns()));
    }
}
