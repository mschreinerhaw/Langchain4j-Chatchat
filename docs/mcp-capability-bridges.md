# MCP capability bridges

Public MCP tools are organized around business intents. Discovery, disambiguation and execution protocol steps stay behind a domain bridge unless a separate authorization or confirmation boundary is required.

A bridge does not collapse semantic selection into automatic top-1 execution and does not implement execution loops. It only consolidates discovery tools. The model reviews all returned candidates, then Agent Runtime invokes the original executor through its governed ordered batch envelope, with one child call per accepted template. Retrieval score is evidence only and never replaces the model's business-semantic decision.

| Domain | Public bridge | Internalized steps | Separate tools retained |
| --- | --- | --- | --- |
| Python analysis | `python_analysis_query` | asset, template and data-file discovery/binding | `python_template_execute` |
| API service | `api_service_query` | asset/template discovery and requirement matching | `api_template_execute` |
| Business data query | `data_query_query` | business SQL template and metadata discovery | `sql_query_execute` |
| Server operations | `server_capability_query` | SSH host and command-template discovery | `linux_command_execute` |
| HTTP integration | `http_capability_query`, `http_requirement_analyze` | endpoint/template discovery and HTTP requirement coverage | `http_request_execute` |
| Java/JMX monitoring | `jmx_capability_query` | JMX monitoring-template discovery | `jmx_monitor_execute` |
| Database operations | `database_capability_query` | datasource and database-operation template discovery | `sql_query_execute` |
| Documents | `document_search` | already intent-level | none |

Notification sends and metadata-governance mutations remain separate side-effect tools. A bridge must not weaken tenant scope, user scope, template governance, confirmation, auditing, or target redaction.

There is intentionally no generic `ops_capability_query`. Choosing a public query tool is the business-domain decision; a request cannot override that decision with `targetKind`. Shared publisher code is an implementation detail and does not merge the MCP contracts.
