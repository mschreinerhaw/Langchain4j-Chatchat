# Unified authorized retrieval

`AuthorizedRetrieval` in `chatchat-common` defines the shared boundary for Knowledge, MCP tools, and Skills:

1. The domain resolves a `Scope` from its authoritative database. The scope carries tenant, user, role IDs, and allowed resource IDs.
2. The retrieval callback searches only within that scope. An empty restricted scope stops before the index is called.
3. Every returned ID is intersected with the scope again, then checked against authoritative state before it is returned.

OpenSearch scores relevance. It is never the source of permissions or publication state. Each adapter owns its resource-specific rules:

| Resource | Database scope | Recall | Final authority |
| --- | --- | --- | --- |
| Knowledge | Document visibility and `knowledge_ir_unit` facts | Document/chunk index | Version check, document ACL, RocksDB source text |
| MCP | Tenant/user/role grants and online tool rows | BM25/vector tool index | Current tool row and grants; invocation requires its own policy check |
| Skills | Tenant-visible published skill rows | Bound skill IDs and planning router | Published, clean skill row before content reaches the model |

RBAC expansion belongs in each domain's scope resolver: map role grants to resource IDs, include deny and expiration rules, then pass only those IDs to the shared pipeline. Invocation or content expansion must still check its own permission against current state. A resource type may use an unrestricted scope only when its adapter has another authoritative permission gate; an empty allowed set must use a restricted scope.

The common pipeline does not fetch documents, execute tools, or load skill content. These operations remain with RocksDB, MCP Runtime, and the skill repository respectively.
