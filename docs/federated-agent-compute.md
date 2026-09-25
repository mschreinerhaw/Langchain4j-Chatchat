# Federated Agent Compute

## Five compute nodes

`ComputeNodeType` defines MODEL, AGENT, SKILL, DATA and WORKFLOW. `ExecutionUnit<I,O>` is the typed
adapter contract; `ComputeNodeRouter` rejects duplicate registrations, missing nodes, and request/result type
mismatches. Current bindings are MODEL → configured capacity-governed `AgentChatModelResolver`, AGENT → local/remote
`AgentComputeRuntimePort`, SKILL → whitelisted `KnowledgeSkillExecutorPort`, DATA → structured-data analysis
workflow, and WORKFLOW → analytical workflow runtime. The federated workflow dispatches through AGENT. These are
initial bindings, not a claim that every model operation, Java/Python/MCP skill, Spark/Trino query or Temporal flow
has been migrated. The router deliberately fails closed for an unbound node or mismatched input contract.

Runtime OS treats local, group, and external agents as replaceable domain-compute providers. Runtime retains planning,
authorization, data projection, evidence ownership, verification, and result publication.

Agent source (`LOCAL`, `GROUP`, `EXTERNAL`) and agent execution mode are independent. The request selects
`DOMAIN_INFERENCE` (default) or `AGENTIC_EXECUTION`; a provider must declare the requested mode in
`metadata.supportedExecutionModes` as well as pass capability, tenant, evidence, health and schema admission.
Legacy descriptors without that declaration are inference-only. The Runtime's `INLINE`/`DURABLE` placement is a
separate dimension.
Skill-catalog-backed local agents now participate in both modes through the controlled local adapter. In a
federated run it supplies no MCP tools or document bindings to the legacy AgentRuntime and sets the tool-call
budget to zero. The model returns structured evidence-cited claims or a bounded `SUPPLEMENT_EVIDENCE` request;
only Runtime may authorize and perform the requested local Skill/data operation before re-invoking the agent.
This does not change ordinary, non-federated Skill execution.
Creating, updating, rolling back, publishing, or deleting a Skill-catalog Agent reconciles its local descriptor
after the catalog transaction commits, so new local capabilities do not require a process restart.

## Closure status

The diagram is a target architecture, not yet a claim that every branch is production-complete. The authenticated
`POST /api/v1/agent/analysis` path now reaches intent/workflow routing, tenant-scoped local Skill authorization,
capability-based local/group/external Agent selection, Runtime-owned evidence, A2A execution, and judging. A
composite workflow executes evidence-producing capabilities before domain Agent compute, admits only verified child
evidence, and stops domain dispatch if a required upstream child fails. Agent `REPLAN_REQUIRED` reselects another
policy-admitted provider; `INPUT_REQUIRED`/`SUPPLEMENT_EVIDENCE` can run bounded local Knowledge Skill acquisition
and resume the same A2A Task.
An opted-in provider may also request `STRUCTURED_DATA` supplementation. This requires
`metadata.supplementCapabilities` to contain `STRUCTURED_DATA`, the request to carry a published template and
logical asset, and the caller's local Skill plus enterprise MCP policy to authorize
`sql_template_analysis_execute`. The Runtime never accepts provider-supplied SQL or datasource IDs; only the
preauthorized template result's `remoteProjection` crosses the A2A boundary. This branch shares the same bounded
attempt/deadline budget as Knowledge Skill supplementation.

The read-only tool analysis branch now has a production `AnalysisCapabilityOperator` binding. It accepts only a
tool explicitly bound to the selected local Skill, checks the published tool's read-only metadata, and executes
through the existing governed Tool Runtime (including enterprise MCP asset authorization). Failed calls yield no
evidence. This is a single-tool invocation, not an autonomous multi-tool planner.

The structured-data branch now uses the separately published `sql_template_analysis_execute` MCP capability.
It accepts only an enabled SQL template explicitly allowlisted on one logical datasource, rejects raw SQL/scripts,
DSL and high-risk or multi-statement templates, and runs the existing SQL safety and tenant/asset permission checks.
The selected local Skill must explicitly bind this MCP tool. Results become bounded structured evidence; remote
Agents receive only the asset/template identity and row count unless a richer projection is separately authorized.
The generic `sql_query_execute` remains `confirm_required` and is never used for automatic evidence acquisition.

The computation branch supports bounded, deterministic COUNT/SUM/AVG/MIN/MAX over one complete, same-tenant
structured result. It records the source evidence ID and projects only the derived metric to remote Agents;
arbitrary expressions, Python code and incomplete/truncated rows are refused.

The external-research branch now invokes only an explicitly Skill-bound, read-only unified `web_search` tool
through Tool Runtime. It converts at most five public, attributed URLs into source evidence, excludes invalid/private
URLs and duplicates, and reports the source and distinct-host counts. A single source, an old publication date, or
an undated source is preserved rather than vetoed: sufficiency, freshness and credibility are user judgments.
This validates evidence shape and boundedness, not the truth of source claims;
the Agent must cite source evidence IDs and the Judge still evaluates its output.
The final `EvidenceBundle` is archived with a SHA-256 checksum in PostgreSQL. A rebuildable OpenSearch index stores
only archive metadata (owner, run, digest, size, timestamp), never full evidence content. Index failures leave the
PostgreSQL result authoritative and pending for a scheduled retry. Existing RocksDB analysis spill/checkpoint storage
remains the bounded working-data tier, not a second authoritative archive. Owner-scoped archive read/list APIs enable
evidence retrieval by run; they do not re-execute an analysis. Retention is opt-in with
`chatchat.analysis.evidence.retention-days`; disabled by default, and expired
PostgreSQL records are removed only after their OpenSearch metadata is removed.
A2A Task links are stored in `agent_a2a_task_link`, so a resumed workflow carrying the same execution
identity can recover its remote task after gateway restart;
the link contains only execution/tenant/agent/task/context IDs and expiry, never credentials or evidence.
Health history and circuit state are stored in `agent_provider_health` with transactional row locking;
the in-process state is a fallback if health storage is temporarily unavailable. This is a global provider
health signal, not a replacement for tenant-specific admission policy. The remaining evidence and capability
integrations are still required before the entire pictured OS can be marked complete.

The new template-only capability is a separate governance contract, not a change to the high-risk SQL gateway.

## Runtime flow

```text
AnalysisContext
  -> Query Analyzer
  -> Capability Planner
  -> policy-admitted Agent Registry candidates
  -> Local Agent Provider or Agent Gateway
  -> structured AgentExecutionOutcome
  -> identity/evidence verification
  -> Accept, fallback, supplement evidence, or block
```

`CapabilityId` describes the business function, such as `finance.portfolio-analysis.v1`. It is deliberately separate
from the execution kind. A workflow, skill, local agent, or remote agent may provide the same capability.

## Domain intelligence as evidence-first compute

The workspace's **知识与数据联合分析** page and `POST /api/v1/agent/analysis/domain-intelligence`
use either a published general chat model or a tenant-admitted group/external provider in
`DOMAIN_INFERENCE` mode. The caller chooses up to four published Knowledge Skills, with explicitly selected
document IDs resolved separately for each Skill through
`POST /api/v1/agent/analysis/domain-resources`, up to four Skill-bound read-only MCP tools with explicit
JSON arguments and their owning Skill IDs, and optionally a published preauthorized read-only SQL template
bound to a selected Skill. The caller must explicitly
confirm remote evidence transfer. Runtime checks the Skill/document scope, runs the evidence workflows before
the provider, projects only bounded document excerpts and sanitized tool/structured-data results, then verifies
the provider outcome. The remote provider never receives local tool credentials or permission to execute Skills.
Its A2A message includes `analysisPackage` (`analysis_package.v1`) alongside the existing execution contract.
The separate federated workflow remains available for agent-to-agent collaboration and controlled tool-request
modes; this domain workflow does not grant autonomous execution.

`GET /api/v1/agent/analysis/intelligence-providers` is the common read-only Registry for published enabled
general LLMs and tenant-admitted domain Agents. It returns public capability and evidence descriptors, never
endpoints or credential references. Model publication and Agent registration remain administrator actions.
Each Skill retains its own authorization and document/tool binding throughout the composite workflow;
the general LLM adapter receives the same bounded, sanitized evidence projection as a domain Agent.
Its free-text result is labeled as model-stated grounding, not a claim-level verified conclusion.

## Register a group A2A agent

The Agent management page provides a four-step wizard: connect and verify the Agent, select discovered
capabilities, grant Knowledge Skills/documents/data tools, then review and enable. Protocol identifiers,
trust anchors, credential references, routing and SLA remain in administrator-only advanced settings.
The selected `analysisGrants` are persisted with the provider and rechecked on every domain analysis request;
they are an upper bound in addition to the caller's Skill and data permissions. A default analysis instruction
is applied to the Agent task, while document retrieval keeps the user's original query. If document supplement
is enabled, Runtime may expand retrieval only within the selected Skill and the caller's authorized documents.
The MCP option generates an allowlist from selected data capabilities; it does not bypass the read-only tool
workflow or autonomously invent tool arguments.

The enterprise administration API remains available for platform administrators. Registry reads and writes
currently require the platform administrator; the registry is global until tenant-scoped agent definitions
and grants are implemented.

```http
POST /api/v1/enterprise/agent-registry
Content-Type: application/json

{
  "agentId": "group.investment.analysis",
  "version": "v1",
  "origin": "GROUP",
  "protocol": "A2A_HTTP_JSON",
  "endpoint": "https://agents.example.com/a2a/investment",
  "capabilities": [
    {"namespace": "finance", "name": "portfolio-analysis", "version": "v1"}
  ],
  "trustLevel": "GROUP_TRUSTED",
  "dataAccessMode": "RUNTIME_MANAGED",
  "allowedDataDomains": ["portfolio", "transactions", "market"],
  "allowedEvidenceTypes": ["StructuredDataEvidence", "ComputationEvidence"],
  "outputSchema": "agent_execution_outcome.v1",
  "credentialRef": "env:GROUP_INVESTMENT_AGENT_TOKEN",
  "priority": 100,
  "enabled": true,
  "metadata": {
    "owner": "group-investment-platform",
    "allowedTenantIds": ["tenant-1"],
    "cardKeyId": "group-card-key-1",
    "cardPublicKeyPem": "-----BEGIN PUBLIC KEY-----...-----END PUBLIC KEY-----",
    "requireSignedCard": true,
    "slaLatencyMs": 10000,
    "supplementSkillTypes": ["RULE_LOOKUP"],
    "supplementMaxAttempts": 2,
    "requestQueryParameters": {"region": "north"},
    "requestBodyParameters": {"businessUnit": "research"}
  }
}
```

The endpoint is an A2A interface base URL. `POST /api/v1/enterprise/agent-registry/discover` previews the
Agent Card, and registration repeats discovery. The gateway also refreshes the Card before invocation/resumption.
The Card is fetched without redirects, canonicalized with RFC 8785 JCS, and its RS256 JWS verified against the
operator-pinned public key and `kid`; non-loopback Cards must be signed. The advertised HTTP+JSON interface URL
must match the registered endpoint's scheme, host, port, and path. Optional `requestQueryParameters` are fixed,
non-secret values appended to Agent Card discovery and, after the SDK constructs each A2A operation URL,
to the final HTTP request. The signed Card and its advertised interface URL remain unchanged;
`requestBodyParameters` are sent as `providerRequestParameters` within the A2A task data, not as top-level
Runtime fields. These maps allow up to 16 scalar values each. They are connection defaults, not arbitrary
per-user input, and credentials belong only in `credentialRef`. The gateway uses the official A2A Java SDK `1.3.2.Final` client and
REST transport for messages and tasks. For legacy
services, protocol `HTTP_JSON` posts a minimized `AgentExecutionRequest` JSON shape to the configured endpoint and requires an
`AgentExecutionOutcome` response. A credential
reference beginning with `env:` resolves from the named environment variable. Raw credentials must never be placed in
the descriptor, Agent Card, workflow attributes, or evidence.

The A2A gateway stores a bounded, one-hour PostgreSQL mapping from Runtime execution ID to active A2A task and context IDs.
`AgentGatewayPort.cancel(agent, executionId)` uses the SDK's `cancelTask` operation after rechecking the Agent Card.
The mapping can be recovered after a gateway restart while the execution and link are still valid.

Remote agents are denied by default when an evidence type or requested data domain is not explicitly listed. Local
agents remain inside the Runtime trust boundary.
Remote agents are also denied unless their descriptor explicitly grants the request's tenant in
`metadata.allowedTenantIds`. A platform administrator controls this grant while the registry is global.
The gateway omits Runtime-internal user identity, scope attributes and metadata. Remote evidence is **deny by
default**: an upstream evidence producer must set `attributes.remoteProjection` to an explicitly approved JSON
object or text. Only that projection, its evidence ID and capability cross the boundary. Unprojected evidence is
omitted and cannot support a remote claim during verification. The Runtime does not infer safe fields from raw
evidence; deployment teams must define domain-specific projection and masking rules before sensitive analysis.

## Publish an existing local agent capability

Existing `SkillDefinition` agents are automatically registered as `local.skill.{skillId}`. Add business capabilities
to the skill's `workflowConfig`:

```json
{
  "agentCapabilities": [
    "finance.portfolio-analysis.v1",
    "finance.risk-analysis.v1"
  ]
}
```

Every local skill also receives the direct capability `local.{skillId}.v1` for backward-compatible explicit routing.

## Invoke from Analysis Runtime

Authenticated users can invoke the scoped vertical path with:

```http
POST /api/v1/agent/analysis
Content-Type: application/json

{"query":"分析投资组合风险","skillId":"authorized-local-skill",
 "capability":"finance.portfolio-analysis.v1","documentIds":["doc-1"],
 "maxAttempts":2,"timeoutMs":60000}
```

Tenant, user, roles, effective document scope, and request identity come from the authenticated Runtime context,
not from request-provided claims. A denied local Skill or document scope prevents execution.
For a remote Agent that may request structured-data evidence later, this endpoint also accepts optional
`dataTemplateId`, `dataAssetName`, `dataEnvironment` and `dataParameters`. They remain Runtime-local and are
used only if an admitted provider requests `STRUCTURED_DATA` within the bounded A2A supplement loop.
Set `agentExecutionMode` to `AGENTIC_EXECUTION` to admit only providers that explicitly declare that mode.
In this mode an Agent may return `metadata.toolRequests`, for example:

```json
{"toolRequests":[{"requestId":"need-rules","type":"SUPPLEMENT_EVIDENCE",
  "evidenceType":"RULE_LOOKUP","minimumCount":1,"reason":"Check the approved policy"}]}
```

No raw tool name, SQL, URL or datasource can be supplied in this protocol. The Runtime converts the request into
an evidence requirement, checks the provider's supplement allowlist and the caller's Skill/data scope, executes
the local authorized supplement and resumes the same Agent task within its attempt/deadline budget.

For a bounded multi-Agent plan, use the same federated workflow through:

```http
POST /api/v1/agent/analysis/collaborate
Content-Type: application/json

{"query":"Review portfolio risk","skillId":"authorized-local-skill",
 "tasks":[
  {"taskId":"domain","agentId":"local.risk","capability":"finance.risk.v1",
   "instruction":"Analyze the scoped evidence","mode":"DOMAIN_INFERENCE"},
  {"taskId":"review","agentId":"group.risk","capability":"finance.risk.v1",
   "instruction":"Review the prior analysis","mode":"AGENTIC_EXECUTION","dependsOn":["domain"]}
 ]}
```

Plans contain at most five topologically ordered tasks. Each task independently passes the existing Provider
admission policy; an explicit `agentId` narrows that set but never bypasses it. A dependent task receives only
the original Runtime evidence and its declared predecessors' evidence. Final metadata records each task status;
verified partial findings remain visible instead of imposing a cross-source sufficiency verdict on the user.

For an explicitly bound read-only registered tool, use the separate governed path:

```http
POST /api/v1/agent/analysis/tool
Content-Type: application/json

{"query":"Summarize 2025 revenue","skillId":"authorized-local-skill",
 "toolName":"published_revenue_query","arguments":{"year":2025}}
```

The selected Skill and the enterprise tool asset policy must both authorize the call. Tool arguments and evidence
size are bounded; tools published as write/send/delete or confirmation-required are refused for analysis.

To combine local evidence with federated Agent reasoning in one verified run:

```http
POST /api/v1/agent/analysis/composite
Content-Type: application/json

{"query":"Analyze portfolio risk","skillId":"authorized-local-skill",
 "capability":"finance.portfolio-analysis.v1","documentIds":["doc-1"],
 "toolName":"published_risk_metrics","arguments":{"portfolioId":"p-1"}}
```

Document and tool evidence are verified before the Agent is dispatched. Either source can be omitted, but at least
one is required. If a required evidence branch fails, the composite Judge rejects the run and skips Agent dispatch.
For preauthorized SQL data evidence, add `dataTemplateId`, `dataAssetName`, `dataEnvironment`, and
`dataParameters` to this request. Add `metricOperation` (COUNT/SUM/AVG/MIN/MAX) and `metricField` when a deterministic
calculation is required. Raw SQL, arbitrary formulas and concrete datasource IDs are not accepted.
To require governed public-source research before Agent dispatch, add `researchToolName` pointing to the selected
Skill's explicitly bound `web_search` or registered MCP `*_web_search` tool, and explicit bounded `researchTerms`
for the external provider. The original user query remains local to the unified search bridge. A missing or inadmissible search result
rejects the composite run before the Agent is called.
Automatic SQL execution is restricted to an enabled, datasource-allowlisted, published single-statement
read-only template bound to the caller's authorized Skill. The generic `sql_query_execute` remains
confirmation-required. SQL results marked truncated or whose reported row count does not match the returned rows
are rejected before they can be used as structured evidence or fed to a remote Agent.

Accepted final evidence is stored in PostgreSQL with a SHA-256 integrity digest, and analysis metadata returns
`evidenceArchiveId`, `evidenceSha256` and `evidenceByteLength`. Retrieve it through
`GET /api/v1/agent/analysis/evidence/{evidenceArchiveId}`; access is limited to the authenticated tenant and user.
`GET /api/v1/agent/analysis/evidence?runId={runId}` lists that owner's archive references for a run.
An archive write failure changes the Judge result to rejected rather than returning a misleading accepted result.
Production deployments should set and review the retention period against their data-governance obligations.

Set `runtime.agent.capability` on `AnalysisContext`. The query analyzer selects `DOMAIN_INTELLIGENCE`; in a composite
analysis, evidence produced by earlier structured-data, document, computation, tool, or research workflows is passed to
the federated-agent workflow as one Runtime-owned `EvidenceBundle`.

```java
AnalysisContext context = baseContext
    .withAttribute(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE,
        "finance.portfolio-analysis.v1")
    .withAttribute("allowedDataDomains", List.of("portfolio", "market"));

AnalysisExecutionOutcome outcome = analysisRuntime.analyze(context);
```

An external provider does not receive MCP credentials or Skill names. If it returns `INPUT_REQUIRED` or
`SUPPLEMENT_EVIDENCE` with `missingEvidence`, Runtime may run only an operator-allowlisted local Knowledge Skill
or an explicitly authorized read-only SQL template. Runtime rechecks
`SkillExecutionScopePort` authorization for the local `AnalysisContext.skillId`; without an authorized
local Skill and applicable document scope, Knowledge supplementation is denied. Structured-data supplementation
has the separate explicit template, asset, Skill binding and MCP permissions described above. A nonempty result is minimized through
`remoteProjection` and sent as a new A2A message carrying the original `taskId` and `contextId`. The loop is bounded
by request `maxAttempts` (the federated workflow defaults to two), provider `supplementMaxAttempts` and the original
deadline. Missing authorization, no evidence, repeated requirement, or exhausted budget leaves the interrupted
status visible to the caller. Supplementation is wired only to Knowledge Skills and the preauthorized read-only
SQL-template path, never arbitrary MCP, Python or raw SQL execution. The caller can provide `agentMaxAttempts`, `documentTags`, and `knowledgeDomains` in the analysis
context; document IDs and roles are inherited from the authorized context.

Routing filters tenant/data/evidence policy first. A health tracker backed by PostgreSQL then excludes remote providers for 30
seconds after three consecutive failures and deprioritizes providers whose observed latency exceeds
`slaLatencyMs`. `GET /api/v1/enterprise/agent-registry/{agentId}/health` exposes this signal.

## Result contract

Successful providers should return `agent_execution_outcome.v1`. Every material claim cites evidence IDs from the input
bundle. Runtime rejects changed execution/provider identities, unknown evidence references, successful empty responses,
and unsupported claims when evidence citation is required.

Unstructured A2A text is preserved as an artifact and downgraded to `PARTIAL`; it is never silently promoted to a
verified conclusion.
