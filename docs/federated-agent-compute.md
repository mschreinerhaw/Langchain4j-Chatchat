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
  "metadata": {"owner": "group-investment-platform", "allowedTenantIds": ["tenant-1"]}
}
```

The endpoint is an A2A interface base URL. The gateway uses the official A2A Java SDK `1.3.2.Final` client and
REST transport, resolves the Agent Card, and requires its advertised HTTP+JSON interface URL to match the registered
endpoint before invoking it. The SDK owns message serialization and task querying. For legacy
services, protocol `HTTP_JSON` posts a minimized `AgentExecutionRequest` JSON shape to the configured endpoint and requires an
`AgentExecutionOutcome` response. A credential
reference beginning with `env:` resolves from the named environment variable. Raw credentials must never be placed in
the descriptor, Agent Card, workflow attributes, or evidence.

The A2A gateway stores a bounded, one-hour in-process mapping from Runtime execution ID to an active A2A task ID.
`AgentGatewayPort.cancel(agent, executionId)` uses the SDK's `cancelTask` operation after rechecking the Agent Card.
This mapping is not durable across process restarts; durable task cancellation requires persistence integration.

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
`SUPPLEMENT_EVIDENCE`, Runtime decides whether an authorized Skill may satisfy that requirement. The current first
slice preserves this status for the caller; automatic bounded supplementary execution is the next orchestration stage.

## Result contract

Successful providers should return `agent_execution_outcome.v1`. Every material claim cites evidence IDs from the input
bundle. Runtime rejects changed execution/provider identities, unknown evidence references, successful empty responses,
and unsupported claims when evidence citation is required.

Unstructured A2A text is preserved as an artifact and downgraded to `PARTIAL`; it is never silently promoted to a
verified conclusion.
