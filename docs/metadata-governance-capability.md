# Enterprise metadata governance capabilities

The metadata-governance analysis implementation remains an internal runtime
capability. The retired `enterprise_metadata_annotate_ddl` and
`enterprise_metadata_compare` tools are removed from the MCP tool surface at
application startup and are not republished.

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
