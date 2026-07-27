# Enterprise Metadata Capability

`enterprise_metadata_search` is a read-only MCP capability that exposes standard
fields, standard roots and code dictionaries maintained in the metadata database
to the existing Agent Runtime.

## Runtime boundary

- It is not a table-creation Agent, SQL Agent or data-governance workflow.
- Agent Runtime discovers it through the existing MCP tool registry and
  `applicability` metadata.
- Planner, reasoning loop, hypothesis handling and plan evolution contain no
  special branch for this capability.
- Metadata rows are not compiled into Runtime code. Excel is an import format;
  Runtime retrieval reads the database-backed catalog and index.
- Returned `evidenceObjects[]` form the factual boundary. The Agent must not add
  field names or definitions that are absent from the returned records.

## Database master data and Excel import

The database is the source of truth:

- `mcp_metadata_standard_field`: standard field definitions;
- `mcp_metadata_standard_term`: standard roots and abbreviations;
- `mcp_metadata_standard_dictionary`: dictionary headers;
- `mcp_metadata_standard_dictionary_item`: dictionary code items.

Excel is used only to populate or update these tables. The built-in import
template locations are:

```text
file:../标准字段词根/**/*.xlsx
file:../../标准字段词根/**/*.xlsx
```

The loader identifies supported content by column headers:

- standard fields: `内部编码` and `数据类型`;
- standard roots: `词根编码` and `英文全称`;
- code dictionaries: `内部标识符` and `代码描述`.

No workbook filename convention is required. Import is an upsert by stable
business ID and does not delete database rows that are absent from a workbook.

```text
Excel template -> standard metadata tables -> scenario classification
               -> enterprise_metadata_catalog (BM25 + KNN)
```

`application.yml` does not carry the built-in metadata defaults. Defaults live
in the metadata capability template (`EnterpriseMetadataProperties`) and can
still be overridden with normal Spring Boot external configuration.

## Search and evidence contract

Input:

```json
{
  "query": "客户资产",
  "types": ["metadata_field"],
  "statuses": ["标准"],
  "scenarios": ["customer_account", "asset_position"],
  "limit": 20
}
```

Output schema: `enterprise_metadata_search_result.v2`.

The result contains:

- `results[]`: structured database-backed metadata records with source
  traceability;
- `evidenceObjects[]`: stable evidence IDs, metadata evidence types, confidence
  and source-quality declarations;
- `evidencePolicy`: explicit no-sample-value and returned-record-only rules.

When OpenSearch is enabled, database records are refreshed into the configured
physical index. If OpenSearch is unavailable, the same database-derived catalog
remains usable through its immutable in-memory snapshot.

## Hybrid KNN retrieval and scenario taxonomy

`enterprise_metadata_catalog` uses an OpenSearch `knn_vector` field and HNSW
index. Each field, standard root and code dictionary is enriched with:

- `scenarioCodes` and `scenarioNames`: multi-label business classification;
- `scenarioDescription`: business usage description;
- `semanticText`: metadata meaning plus scenario context used to build vectors;
- `scenarioVector`: normalized, deterministic scenario-aware feature vector.

Search runs BM25 and filtered KNN retrieval, normalizes both result sets and
merges them using configurable weights. Results expose `bm25Score`, `knnScore`
and `retrievalMode`.

The taxonomy database is also the source of truth:

- `mcp_metadata_domain`: enterprise data domains;
- `mcp_metadata_scenario`: business scenarios, metadata-type scope and fallback;
- `mcp_metadata_term_mapping`: independent terms, weights, match types and priorities.

Taxonomy changes invalidate the local cache and rebuild the OpenSearch catalog.
Agent Runtime continues to discover only `enterprise_metadata_search`; it does
not contain scenario-specific branches.

## Operations

Authenticated MCP administrators can import templates and synchronize the index:

```text
GET  /api/v1/admin/enterprise-metadata/status
POST /api/v1/admin/enterprise-metadata/import   # Excel -> database -> index
POST /api/v1/admin/enterprise-metadata/refresh  # database -> index
GET  /api/v1/admin/enterprise-metadata/taxonomy/domains
POST /api/v1/admin/enterprise-metadata/taxonomy/domains
GET  /api/v1/admin/enterprise-metadata/taxonomy/scenarios
POST /api/v1/admin/enterprise-metadata/taxonomy/scenarios
GET  /api/v1/admin/enterprise-metadata/taxonomy/terms
POST /api/v1/admin/enterprise-metadata/taxonomy/terms
```
