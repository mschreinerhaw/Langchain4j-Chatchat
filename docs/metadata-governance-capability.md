# Enterprise metadata governance capabilities

The MCP server exposes one read-only metadata-governance tool. It uses the
maintained enterprise metadata catalog as its factual boundary and never
executes DDL.

## `enterprise_metadata_annotate_ddl`

Input: one `CREATE TABLE` statement in `ddl`.

The result parses every physical column and returns:

- the matched standard field and match confidence;
- standard term/root matches for physical-name tokens;
- associated maintained dictionary items;
- unmatched name tokens;
- catalog counts and status as retrieval evidence.

The output schema is `metadata_ddl_annotation.v1` and always declares
`executionStatus=NOT_EXECUTED`.

## Runtime governance boundary

Business matching behavior is stored in `mcp_metadata_governance_policy`.
The versioned policy document contains the metadata contract, retrieval scoring,
annotation thresholds, penalties, dictionary limits, nullable mappings,
difference severities, and messages.

The Runtime reads the active database policy through
`MetadataGovernancePolicyService`. An empty database is initialized once from
the bootstrap policy resource; subsequent changes are maintained in the
database and take effect through the administration API:

```text
GET  /api/v1/admin/enterprise-metadata/governance-policy
PUT  /api/v1/admin/enterprise-metadata/governance-policy
POST /api/v1/admin/enterprise-metadata/governance-policy/refresh
```

Updates support an `expectedRevision` value to prevent lost concurrent changes.
Runtime code contains only versioned protocol fields and stable difference
codes; it does not branch on concrete business fields, dictionaries, tables, or
user-query keywords.
