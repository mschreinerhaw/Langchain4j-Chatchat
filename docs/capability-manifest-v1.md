# Capability Registry and capability-manifest.v1

MCP definitions remain the source of truth. A manifest is a generated, versioned
view of an actual tool, not a separately edited JSON file or a new execution path.
The Runtime gateway obtains definitions through its existing MCP transport; MCP
servers do not need to implement another REST protocol.

## Protocol

| Field | Source and meaning |
| --- | --- |
| manifestVersion | `capability-manifest.v1`, the platform view protocol |
| capabilityId | SHA-256 identity of server ID and canonical local tool name, prefixed `mcp-` |
| version | SHA-256 of the canonical content excluding this version field; an opaque contract version |
| status | PUBLISHED, DEPRECATED, DRAFT, DISABLED or RETIRED, projected from current publication |
| provider | Actual server/local/remote tool identities; dynamic child parent relationship when declared |
| discovery | Publisher name, description, capability code and declared usage/keywords/limitations |
| contract | Actual input/output schemas, result kind/schema reference and pagination support |
| execution | Actual tool identity and declared dynamic parent route; runtime authorization is required |
| governance | Selected publication/confirmation/visibility/schema metadata; never an access grant |
| publisherCapabilities | Supported descriptive manifest fields, including actual operation and dataset catalog contracts |

Map-key order does not change the hash. Caller identity, authorization results and
sync timestamps are not hashed. Tools without enhanced descriptions still receive
a manifest from their actual MCP contract; no operations are invented. Missing
output schemas remain empty rather than being guessed.

## Persistence and lifecycle

`capability_registry` stores the current pointer and lifecycle per real tool.
`capability_version` stores immutable content-addressed JSON snapshots and timestamps.
Input/output and binding data are projections, not independently editable definitions.
Existing MCP permissions/resource grants and Agent bindings remain authoritative;
there is no second capability_permission table.

Startup, periodic reconciliation (default 60 seconds) and discovery requests sync
the directory maintained by the existing MCP transport. That transport already
handles tool-change notifications. Identical definitions create no new version.
An absent tool is retired only when the corresponding service directory is
authoritative and the kernel is ready; an outage must not erase history. Removed
tools never appear in discovery. Content rollback can reuse a previous version.
Per-entry optimistic locking and unique version identities reject conflicting
concurrent updates. The reconciler retries failed syncs on its next tick.

## APIs

| API | Behavior |
| --- | --- |
| GET /api/v1/capabilities?query=...&agentId=...&offset=0&limit=20 | Authorized summaries only; bounded pagination, maximum 100 |
| POST /api/v1/capabilities/search | Same discovery; body query, agentId, offset and limit |
| GET /api/v1/capabilities/{capabilityId}?agentId=... | Authorized current full manifest |
| POST /api/v1/capabilities/{capabilityId}/invoke?agentId=... | Body version, arguments and optional conversationId; existing ToolRuntime execution |

V1 query matching is case-insensitive substring matching over publisher discovery
and capability descriptions. It is not an additional LLM ranker or a business intent
classifier; callers can use names, keywords or registered dataset identifiers.

Tenant/user are taken only from authenticated request attributes, not request JSON.
Roles come from existing database policy evaluation. Optional Agent scope must be
accessible to the caller and its current tool/service bindings are intersected
with tool authorization. Disabled tool configs take precedence over service binding.
Explicit tool bindings also narrow a bound service to those named tools; merely
recording their parent service does not grant the Agent every tool on that service.
Directory counts and filtering are computed after authorization; unavailable and
unauthorized details both return 404. No shared cache stores caller-visible results.

Directory read/search/invoke have separate seeded RBAC API permissions. A one-time
marked migration maps existing runtime-directory read rights to read/search, and
runtime-invoke rights to capability invoke. Roles without the equivalent existing
rights receive nothing. Subsequent manual revocations are not automatically restored.
Tool
permissions are checked again on detail/invoke. The required contract version is
checked before execution; stale contracts return 409. The existing runtime registry
revision guard also catches a contract change between lookup and execution.
ToolRuntime continues to validate arguments, enforce confirmations and MCP publisher
ACLs, route dynamic children and retain execution traces.

Dynamic children do not inherit parent authorization. If existing API policy has
no decision for an unmanaged child, an explicit platform resource grant is required
for discovery. Publisher-specific and argument-dependent ACLs are still enforced
on actual execution; visibility is not a guarantee every argument is permitted.
Current identity policies implement user, role and tenant rules. Independent
organization grants are not introduced by v1; any future organization policy must
be implemented once in the shared policy engine rather than in this registry.

## MCP, Skills and model context compatibility

Standard MCP discovery and execution remain unchanged. Existing publisher
`capability_manifest.v1` extensions are accepted as descriptive data inside the
new platform `capability-manifest.v1` envelope; they are not claimed to be an MCP
standard. Existing Agent/Skill tool bindings and the current authorized model prompt
injection remain in use. This phase exposes discovery/detail to callers; it does
not grant all Agents new discovery tools automatically or inject the entire registry
into every prompt. Callable Skills remain governed by their existing publication
and execution mechanisms; plain Skills documents are not manufactured into tools.

## Verification

Database tests exercise version deduplication, immutable history, authoritative
retirement, child-route preservation, rollback, real schemas and deterministic
hashing. Access tests cover filtering, hidden details, grant revocation, missing
child authorization, version mismatch, bound execution identity and bounded discovery.

Live verification on 2026-10-09:

- PostgreSQL contains 32 capability entries and 32 immutable snapshots, including
  five dynamic-child manifests with real parent-delegation routes.
- Authenticated discovery returns 30 visible capabilities; `financial-news-agent`
  narrows that to its single bound tool.
- The web-search detail preserves three real operations and seven actual datasets;
  a `market_quote_daily` directory query discovers that tool without question-specific rules.
- Repeated detail reads preserve the content version. Anonymous, unknown and stale-version
  requests return 401, 404 and 409 respectively.
- Invoking `discover_datasets` through the versioned capability gateway succeeds
  through the existing ToolRuntime/MCP path, with a successful tool trace.
- The relevant suite contains 67 passing tests. The final API artifact SHA-256 is
  `9060343f2b5b0c4a3cb1730f747ab345f7ca5c8dd629c662dfebb90235f80412`.
  The previous program is backed up at
  `/opt/chatchat-deploy-backup/capability-registry-20261009/chatchat.jar`.

Captured HTTP evidence is kept under the ignored
`target/codex-live/capability-registry-20261009/` directory.
