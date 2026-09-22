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

The `resource_grant` table supplies a shared RBAC overlay for `KNOWLEDGE`, `KNOWLEDGE_BASE`, `MCP_TOOL`, `SKILL`, and `AGENT_SKILL`. A grant belongs to a tenant and resource ID (or `*`), targets a tenant, user, or role, and has `ALLOW` or `DENY`. Role membership is resolved from the database; caller-supplied role names never grant access. Deny wins for ordinary users; an enabled `super_admin` role bypasses this overlay. An expired or disabled grant cannot allow access. When a resource has no configured grants, its existing domain ACL remains in force, so older resources remain accessible under their prior rules. When at least one grant is configured for a resource, a matching active allow is required.

Grants are managed through `/api/v1/enterprise/resource-grants` under the `system:resource-grants:manage` permission. Apply the matching `V20260922_01__resource_grants.sql` migration before using the endpoint on existing databases. Invocation or content expansion must still check permission against current state. A resource type may use an unrestricted scope only when its adapter has another authoritative permission gate; an empty allowed set must use a restricted scope.

For example, `POST /api/v1/enterprise/resource-grants` with `{"tenantId":"tenant-a","resourceType":"SKILL","resourceId":"skill-1","principalType":"ROLE","principalId":"role-analyst","effect":"ALLOW","enabled":true}` grants a role one Skill. The endpoint also supports `GET ?tenantId=...&resourceType=...`, `PUT /{id}`, and `DELETE /{id}`. Tenant administrators can only manage grants in their own tenant; platform administrators can manage all tenants.

Use the document ID for `KNOWLEDGE`, the published domain skill ID for `SKILL`, the Agent configuration ID for `AGENT_SKILL`, and the MCP `localToolName` for `MCP_TOOL`. MCP's existing `mcp_tool_permission` rules still apply; the shared grants narrow that scope at recall and runtime invocation. Skills search only within the published IDs bound to the selected agent or role. Once a tenant configures `SKILL` grants, Domain Skills require an explicit matching grant. If OpenSearch is unavailable, bound Skills remain available under database authorization.

The resource type currently implies the action: `SKILL` and `AGENT_SKILL` mean `USE`, `KNOWLEDGE` and `KNOWLEDGE_BASE` mean `READ`, and `MCP_TOOL` means `EXECUTE`. Management is controlled separately by the API permission codes.

## Agent Skill knowledge scope

`skill_resource_scope` binds an Agent Skill to `DOCUMENT` IDs or `KNOWLEDGE_BASE` identifiers. At present, a knowledge base identifier is a document category/tag stored in `knowledge_ir_unit.tags_json`; the resolver expands it to document IDs and verifies the exact tag value. An Agent Skill with any managed bindings uses those rows. An Agent Skill without managed bindings continues to use its existing `boundDocumentIds` and `boundDocumentTags` settings.

For each run, the database resolves the user's enabled tenant roles, checks the `AGENT_SKILL` grant, expands the Skill's document requirements, and intersects them with explicit `KNOWLEDGE`/`KNOWLEDGE_BASE` grants when such grants are configured for the tenant. Once any `AGENT_SKILL` grant is configured in a tenant, Agent Skills require an explicit matching grant; the legacy permissive behavior applies only before that tenant enables Skill grants. Existing document visibility still applies when the search reads source text. A restricted empty intersection is carried as a reserved, non-existent document ID so the search cannot become unscoped. The Knowledge Runtime resolves the scope again before compiling evidence, which catches grant changes during retrieval. Role codes and names come from the database and are carried into native role-visible document checks.

Manage the bindings with `GET/POST/PUT/DELETE /api/v1/enterprise/skill-resource-scopes` under `system:skill-resource-scopes:manage`. For example, `POST` with `{"tenantId":"tenant-a","skillId":"agent-skill","resourceType":"KNOWLEDGE_BASE","resourceId":"research","enabled":true}` binds the Agent Skill to the `research` category. Grant the user's role `USE` of the Agent Skill with an `AGENT_SKILL` resource grant and `READ` of that category with a `KNOWLEDGE_BASE` resource grant. Apply `V20260922_02__skill_resource_scope.sql` after the resource grant migration on existing databases.

The common pipeline does not fetch documents, execute tools, or load skill content. These operations remain with RocksDB, MCP Runtime, and the skill repository respectively.
