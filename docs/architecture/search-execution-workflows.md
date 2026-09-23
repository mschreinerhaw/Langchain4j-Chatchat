# Search execution workflows

`web_search` and `enterprise_metadata_search` are MCP capability boundaries backed
by staged execution workflows. Their publishers define the public tool contract;
they do not own retrieval orchestration.

```text
Agent analysis workflow
  -> MCP capability invocation
  -> capability execution workflow
  -> ANALYZE
  -> PLAN
  -> EXECUTE
  -> VERIFY
  -> ASSEMBLE
  -> evidence-bearing MCP result
```

The shared lifecycle is implemented by `AbstractStagedExecutionWorkflow`. It is
framework neutral and can run through the Runtime kernel or an engine adapter.

## Web search

`WebSearchExecutionWorkflow` uses workflow ID `search.web.v1` and selects one of
two plans:

- `DISCOVERY`: analyze query, search governed financial data, search local news,
  use external web search as a supplement, merge and rank, then verify evidence.
- `DATASET_QUERY`: validate dataset scope, read the governed dataset, verify fact
  rows, then assemble evidence.

`RemoteNewsMcpToolProvider` owns publication and delegates execution to this
workflow. Financial and news services remain operators used by the workflow.

## Enterprise metadata search

`EnterpriseMetadataSearchWorkflow` uses workflow ID
`search.enterprise-metadata.v1` and selects one of two plans:

- `DISCOVERY`: normalize independent requirements, classify scenarios, retrieve
  all required metadata types, rank one candidate per requirement, verify
  cardinality, then assemble evidence.
- `FIELD_MATCH`: normalize fields, resolve source schema, retrieve candidates for
  each field, rank one candidate per field, verify coverage, then assemble
  evidence.

`EnterpriseMetadataMcpToolPublisher` owns the MCP schema and delegates execution
to the workflow. The request adapter, search service, matching service,
OpenSearch, and catalog remain replaceable operators below it.

Both results expose the workflow ID, selected mode, planned steps, and
verification status. This trace describes execution and does not add factual
claims beyond the returned evidence.

## SQL query execution

`SqlQueryExecutionWorkflow` uses workflow ID `execute.sql-query.v1`. Although it
is an execution capability rather than a search capability, it follows the same
staged lifecycle and selects one of three effective execution modes:

- `BUSINESS_QUERY`: validate the template contract, resolve the registered
  business query, bind parameters, execute its internal query workflow, verify
  the result, and assemble evidence.
- `SQL_QUERY`: route the logical datasource, classify the SQL, enforce the
  read-only policy through the SQL operator, execute one statement, and verify
  the result.
- `SQL_SCRIPT`: route the logical datasource, classify multiple statements,
  enforce the read-only policy through the script operator, execute the bounded
  script, and verify all result sets.

`SqlMcpToolPublisher` retains MCP publication, confirmation, concurrency limits,
and public schema metadata. It delegates the governed execution sequence to the
workflow. The query, script, routing, template, and database-query services stay
as replaceable operators below that workflow.
