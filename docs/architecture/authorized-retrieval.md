# Unified authorized retrieval

`AuthorizedRetrieval` in `chatchat-common` defines the shared boundary for Knowledge, MCP tools, and Skills:

1. The domain resolves a `Scope` from its authoritative database. The scope carries tenant, user, role IDs, and allowed resource IDs.
2. The retrieval callback searches only within that scope. An empty restricted scope stops before the index is called.
3. Every returned ID is intersected with the scope again, then checked against authoritative state before it is returned.

The Agent chat path already resolves Skill document bindings with
`DatabaseSkillExecutionScopeService` and limits MCP candidates with
`DatabaseMcpToolCandidateRetriever` before semantic ranking. Both services now
query only the caller's assigned roles; MCP candidate lookup selects the requested
tool names in SQL instead of loading the full catalog for every run.

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

## Document evidence recall order

Document retrieval is implemented by `DocumentRetrievalWorkflow`, rather than
by conditionals in the orchestrator. Spring discovers `DocumentRetrievalStage`
implementations and executes them by `order()`:

| Order | Stage | Responsibility |
| ---: | --- | --- |
| 100 | `original-query` | Preserve the caller's query and suppress first-pass synonym expansion |
| 150 | `skill-role-context` | Materialize Skill document bindings, tags, and database-resolved roles |
| 200 | `authorized-document-scope` | Resolve PostgreSQL IR matches and the effective ACL document IDs |
| 300 | `bm25-vector-recall` | Run lexical/vector recall only inside that scope |
| 400 | `rrf-fusion` | Fuse independent ranked channels and reapply the authorized ID boundary |
| 500 | `candidate-rerank` | Run registered rankers, including the optional BGE HTTP adapter |
| 550 | `parent-section-expansion` | Load parent/section navigation for reranked documents from PostgreSQL |
| 600 | `source-verification` | Verify current RocksDB ACL, version, and passage text before evidence assembly |

Add a retrieval phase by implementing `DocumentRetrievalStage` with a unique ID
and order. Add a cross encoder or business ranker by implementing
`DocumentCandidateReranker`; all registered rankers run in their declared order.
The workflow context carries scoped IDs, channel results, candidates, completed
stages, and verified sources. A stage may stop execution, which is how an empty
or unavailable authoritative scope fails closed. `DocumentSearchOrchestrator`
is retained as a thin compatibility facade.

The default problem-analysis budgets route at most 10 documents, retain 30 RRF
candidates, and return at most 5 reranked document candidates. Configure them
under `chatchat.search.problem-analysis`. A BGE-compatible endpoint accepts
`model`, `query`, `documents`, and `top_n`, and returns `results` containing
`index` plus `relevance_score` (or `score`). Enable it with
`CHATCHAT_BGE_RERANKER_ENABLED`, `CHATCHAT_BGE_RERANKER_ENDPOINT`, and optionally
`CHATCHAT_BGE_RERANKER_API_KEY`. When it is disabled or unavailable, the stage
keeps RRF order and still applies the final candidate budget.

`QueryPlanningService` supplies intent and normalized tokens. The first workflow
stage turns those signals plus company, industry, and tags into a
`ProblemQueryAnalysis`. The Skill/Role stage carries explicit document bindings,
tags, and trusted roles into PostgreSQL routing. After source verification,
`DocumentSearchEvidenceService` assembles chunks, citations, context, quality,
and reasoning as the Evidence Bundle consumed by `DocumentKnowledgeSkillExecutor`
and Agent Runtime.

The API enables `chatchat.search.document-first-enabled` in its development and
production profiles. For an unscoped `document_search` request, it looks up
accessible document IDs in PostgreSQL `knowledge_ir_unit` first. An empty match
ends the recall; it does not start a corpus-wide chunk or RocksDB scan. For a
nonempty match, the passage index receives those IDs as a filter in OpenSearch
or Lucene. The API then opens only candidate source documents in RocksDB to
check current authorization, version, and verbatim passage content before
returning citations. If the passage index misses or fails, it reads only the
PostgreSQL-selected documents for local evidence. A request with explicit
document IDs continues to search those selected documents directly. This mode
requires populated `knowledge_ir_unit` rows for searchable documents; rebuild
the IR navigation data before enabling it on a corpus with older documents.

The MCP `document_search` tool preserves the caller's query on the first recall.
The Knowledge retrieval kernel suppresses semantic and bilingual expansion during
that recall. If the first response contains document titles but no body chunks,
MCP retries up to two authorized document IDs with the original query and an
exact document scope. If neither a chunk nor a document is found, MCP may make
one synonym retry, adding only one expansion term. A product identifier such
as `livedata` is not broadened into a generic `data` query.

The analysis adapter projects document evidence from `results` (or title-only
`documents`) even when an older MCP gateway labels the envelope `UNDECLARED`.
It does not split the serialized transport JSON into apparent source chunks.

## MCP authorization across the PostgreSQL boundary

MCP does not read the API database directly. Its `ResourceAuthorizationPort` adapter
calls the signed API endpoint `POST /internal/v1/resource-authorization` with the
tenant, user, resource type and at most 500 candidate IDs. The API validates the
enabled user and tenant, resolves current database roles and grants, and returns
only allowed IDs. The adapter fails closed when that call is unavailable. MCP's
document-search result gate checks the returned document IDs again before returning
evidence, and rebuilds context and citations after removing denied chunks. The
`/api/v1/search/document-search/expand` route checks the document ID before loading
its body. Existing document visibility and version checks remain in the knowledge
store.

The browser library routes under `/internal/api/v1/search/**` still use their
native document ACL. This change does not apply the API `resource_grant` overlay
to every library list, preview, download or mutation path. Keep those routes
behind the authenticated API gateway and treat library-wide grant enforcement
as a separate migration of the document authorization boundary.

This gate is enabled by default through
`chatchat.mcp.server.document-search.api-authorization-enabled`. Configure the
shared internal credential and `chatchat.mcp.authorization.api-base-url` on MCP
before starting it. A standalone MCP deployment without the API can explicitly
disable the gate, which also disables the API grant overlay in MCP; its local
document ACL remains in force. Do not use that setting when API grants govern
document or MCP tool access.

PostgreSQL RLS is not enabled by this integration. The MCP document body and its
native visibility metadata are still held by the document store, while API grants
and Skill bindings are in the API database. RLS can protect a table only after all
of its read and write paths set a trusted tenant context within their transaction;
connection initialization SQL is not a user context. Introduce it table by table
after separating application and migration roles, testing pooled connections,
background jobs and cross-tenant denial, and providing an explicit migration path.
