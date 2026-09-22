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
| Skills | Tenant-visible published, clean skill rows plus resource grants | BM25/vector OpenSearch on bound IDs, then planning router | Published, clean skill row and resource grants before content reaches the model |

The `resource_grant` table supplies a shared RBAC overlay for `KNOWLEDGE`, `MCP_TOOL`, and `SKILL`. A grant belongs to a tenant and resource ID (or `*`), targets a tenant, user, or role, and has `ALLOW` or `DENY`. Role membership is resolved from the database; caller-supplied role names never grant access. Deny wins for ordinary users; an enabled `super_admin` role bypasses this overlay. An expired or disabled grant cannot allow access. When a resource has no configured grants, its existing domain ACL remains in force, so older resources remain accessible under their prior rules. When at least one grant is configured for a resource, a matching active allow is required.

Grants are managed through `/api/v1/enterprise/resource-grants` under the `system:resource-grants:manage` permission. Apply the matching `V20260922_01__resource_grants.sql` migration before using the endpoint on existing databases. Invocation or content expansion must still check permission against current state. A resource type may use an unrestricted scope only when its adapter has another authoritative permission gate; an empty allowed set must use a restricted scope.

For example, `POST /api/v1/enterprise/resource-grants` with `{"tenantId":"tenant-a","resourceType":"SKILL","resourceId":"skill-1","principalType":"ROLE","principalId":"role-analyst","effect":"ALLOW","enabled":true}` grants a role one Skill. The endpoint also supports `GET ?tenantId=...&resourceType=...`, `PUT /{id}`, and `DELETE /{id}`. Tenant administrators can only manage grants in their own tenant; platform administrators can manage all tenants.

Use the document ID for `KNOWLEDGE`, the published domain skill ID for `SKILL`, and the MCP `localToolName` for `MCP_TOOL`. MCP's existing `mcp_tool_permission` rules still apply; the shared grants narrow that scope at recall and runtime invocation. Skills search only within the published IDs bound to the selected agent or role. If OpenSearch is unavailable, bound Skills remain available under database authorization.

The common pipeline does not fetch documents, execute tools, or load skill content. These operations remain with RocksDB, MCP Runtime, and the skill repository respectively.
