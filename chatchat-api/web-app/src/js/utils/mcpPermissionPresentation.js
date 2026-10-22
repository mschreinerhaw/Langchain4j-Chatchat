// Presentation only. Runtime authentication, business-resource and tool policies stay server-owned.
// These are the first-party transport's fixed discovery/execution capabilities, not published business tools.
const internalCapabilities = new Set([
  "api_asset_query",
  "api_requirement_analyze",
  "api_service_query",
  "api_template_execute",
  "api_template_query",
  "business_query_template_search",
  "calculator",
  "database_asset_search",
  "database_capability_query",
  "database_ops_template_search",
  "database_query",
  "database_query_template_query",
  "data_query_query",
  "document_search",
  "email_send",
  "enterprise_metadata_annotate_ddl",
  "enterprise_metadata_compare",
  "enterprise_metadata_match",
  "enterprise_metadata_search",
  "file_system",
  "http_capability_query",
  "http_endpoint_asset_query",
  "http_endpoint_template_query",
  "http_request_execute",
  "http_requirement_analyze",
  "jmx_capability_query",
  "jmx_monitor_execute",
  "jmx_template_query",
  "linux_command_execute",
  "microservice_asset_query",
  "python_analysis_query",
  "python_asset_query",
  "python_data_file_query",
  "python_template_execute",
  "python_template_query",
  "server_capability_query",
  "sql_metadata_search",
  "sql_query_execute",
  "sql_schema_context_query",
  "sql_script_execute",
  "sql_template_analysis_execute",
  "ssh_asset_query",
  "ssh_template_query",
  "web_search",
]);
export function isInternalMcpCapability(tool) {
  return (
    tool.serviceId === "chatchat-mcp-server" &&
    (tool.resourceType === "transport" ||
      tool.resourceType === "service" ||
      internalCapabilities.has(tool.remoteToolName))
  );
}
export function hasExplicitToolRule(id, catalog) {
  return (
    (catalog.grants || []).some((g) => g.localToolName === id) ||
    (catalog.resourceGrants || []).some((g) => g.resourceType === "MCP_TOOL" && g.resourceId === id)
  );
}
