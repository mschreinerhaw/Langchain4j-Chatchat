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

## Closure status

The diagram is a target architecture, not yet a claim that every branch is production-complete. The authenticated
`POST /api/v1/agent/analysis` path now reaches intent/workflow routing, tenant-scoped local Skill authorization,
capability-based local/group/external Agent selection, Runtime-owned evidence, A2A execution, and judging. A
composite workflow executes evidence-producing capabilities before domain Agent compute, admits only verified child
evidence, and stops domain dispatch if a required upstream child fails. Agent `REPLAN_REQUIRED` reselects another
policy-admitted provider; `INPUT_REQUIRED`/`SUPPLEMENT_EVIDENCE` can run bounded local Knowledge Skill acquisition
and resume the same A2A Task.

Remaining production integrations are explicit: structured-data, tool, computation, and external-research analysis
workflows currently have no production `AnalysisCapabilityOperator` bindings; they fail closed instead of fabricating
evidence. The separate legacy orchestrator has PostgreSQL/OpenSearch and RocksDB integrations, but the new
`EvidenceBundle` workflow has not yet consolidated those persistence/index adapters into one durable evidence
protocol. A2A Task links, health history, and circuit state are also process-local. These are required before the
entire pictured OS can be marked complete.

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

## Register a group A2A agent

Use the enterprise administration API. Registry reads and writes currently require the platform administrator;
the registry is global until tenant-scoped agent definitions and grants are implemented.

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
    "supplementMaxAttempts": 2
  }
}
```

The endpoint is an A2A interface base URL. `POST /api/v1/enterprise/agent-registry/discover` previews the
Agent Card, and registration repeats discovery. The gateway also refreshes the Card before invocation/resumption.
The Card is fetched without redirects, canonicalized with RFC 8785 JCS, and its RS256 JWS verified against the
operator-pinned public key and `kid`; non-loopback Cards must be signed. The advertised HTTP+JSON interface URL
must exactly match the registered endpoint. The gateway uses the official A2A Java SDK `1.3.2.Final` client and
REST transport for messages and tasks. For legacy
services, protocol `HTTP_JSON` posts a minimized `AgentExecutionRequest` JSON shape to the configured endpoint and requires an
`AgentExecutionOutcome` response. A credential
reference beginning with `env:` resolves from the named environment variable. Raw credentials must never be placed in
the descriptor, Agent Card, workflow attributes, or evidence.

The A2A gateway stores a bounded, one-hour in-process mapping from Runtime execution ID to active A2A task and context IDs.
`AgentGatewayPort.cancel(agent, executionId)` uses the SDK's `cancelTask` operation after rechecking the Agent Card.
This mapping is not durable across process restarts; durable task resumption/cancellation requires persistence integration.

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
type against caller-authorized document IDs and tags. Runtime rechecks
`SkillExecutionScopePort` authorization for the local `AnalysisContext.skillId`; without an authorized
local Skill and document scope, supplementation is denied. A nonempty result is minimized through
`remoteProjection` and sent as a new A2A message carrying the original `taskId` and `contextId`. The loop is bounded
by request `maxAttempts` (the federated workflow defaults to two), provider `supplementMaxAttempts` and the original
deadline. Missing authorization, no evidence, repeated requirement, or exhausted budget leaves the interrupted
status visible to the caller. Supplementation is currently wired to Knowledge Skills, not arbitrary MCP, Python,
or SQL execution. The caller can provide `agentMaxAttempts`, `documentTags`, and `knowledgeDomains` in the analysis
context; document IDs and roles are inherited from the authorized context.

Routing filters tenant/data/evidence policy first. A local health tracker then excludes remote providers for 30
seconds after three consecutive failures and deprioritizes providers whose observed latency exceeds
`slaLatencyMs`. `GET /api/v1/enterprise/agent-registry/{agentId}/health` exposes this process-local signal.

## Result contract

Successful providers should return `agent_execution_outcome.v1`. Every material claim cites evidence IDs from the input
bundle. Runtime rejects changed execution/provider identities, unknown evidence references, successful empty responses,
and unsupported claims when evidence citation is required.

Unstructured A2A text is preserved as an artifact and downgraded to `PARTIAL`; it is never silently promoted to a
verified conclusion.
