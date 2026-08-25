package com.chatchat.common.mcp.audit;

import java.util.List;

/** Standard contract for database servers, SQL assets and governed database query tools. */
public final class DatabaseMcpServiceContract extends AbstractMcpDomainServiceContract {
    public DatabaseMcpServiceContract() { super("database", "database_query", "sql", "jdbc", "datasource"); }
    @Override public String contractId() { return "runtime-os/database-mcp-service"; }
    @Override public String domainCode() { return "database"; }

    @Override
    public List<McpContractRequirement> requirements() {
        return List.of(
            required(McpContractSource.INPUT_SCHEMA, "type", "DATABASE_INPUT_SCHEMA_MISSING",
                "Database MCP tools must publish a typed input schema", "REDISCOVER_OR_REPUBLISH_CONTRACT"),
            required(McpContractSource.OUTPUT_SCHEMA, "type", "DATABASE_OUTPUT_SCHEMA_MISSING",
                "Database MCP tools must describe their result envelope", "PUBLISH_OUTPUT_SCHEMA"),
            required(McpContractSource.GOVERNANCE, "operationType", "DATABASE_OPERATION_POLICY_MISSING",
                "Database execution must declare its operation type", "REVIEW_DATABASE_GOVERNANCE"),
            required(McpContractSource.METADATA, "contractVersion", "DATABASE_CONTRACT_VERSION_MISSING",
                "Database discovery evidence must carry a contract version", "REFRESH_CONTRACT_SNAPSHOT")
        );
    }
}
