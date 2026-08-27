# Metadata package layout

`com.chatchat.mcpserver.metadata` owns enterprise metadata ingestion, catalog access,
search, taxonomy management, governance, evidence integration, and MCP tool exposure.

| Package | Responsibility |
| --- | --- |
| `metadata.config` | Enterprise metadata configuration properties |
| `metadata.catalog` | In-memory catalog, metadata records, status, and refresh administration |
| `metadata.ingestion` | Workbook/database loading and metadata import workflows |
| `metadata.search` | Search, matching, classification, vectorization, and request adaptation |
| `metadata.taxonomy` | Domains, scenarios, standards, term mappings, repositories, and taxonomy administration |
| `metadata.governance` | Governance policies, analysis, persistence, administration, and schema migration |
| `metadata.evidence` | Metadata evidence contracts, protocol normalization, and catalog-backed provider |
| `metadata.tool` | MCP tool publication for search and governance capabilities |

## Placement rules

1. Keep the `metadata` root free of concrete types.
2. Place entities and repositories beside the metadata capability whose state they persist.
3. Keep source loading and import orchestration in `ingestion`; read and matching behavior belongs in `search`.
4. Keep MCP registration adapters in `tool` and evidence contracts/adapters in `evidence`.
5. Avoid generic `model`, `service`, `persistence`, and `util` buckets when a functional owner exists.
