# Evidence OS Kernel v1

Status: frozen core contract

This document defines the immutable Evidence OS kernel used by the Agent Runtime. It is a system contract, not an implementation note. New web or evidence-capable tools must adapt to this kernel instead of adding a separate reasoning pipeline.

## Kernel Scope

The Evidence OS kernel applies to every tool that returns public web, crawled, extracted, or structured internet evidence.

Current kernel participants:

- `web_search`
- `search_and_extract`
- MCP tools whose semantic tool key resolves to `web_search`
- MCP tools whose semantic tool key resolves to `search_and_extract`

Non-web tools such as `document_search`, database tools, file tools, and workflow-only tools may produce observations, but they do not enter the WebEvidence observation pipeline unless they return web evidence under the contract below.

## Immutable Pipeline

All web/evidence tools must enter the same semantic pipeline:

```text
ToolOutput
  -> Unified WebEvidence Observation
  -> EvidenceTrustEvaluator
  -> Citation Binding
  -> Agent Reasoning
  -> Answer Review
```

The pipeline must not split by concrete tool name after a tool has been classified as web/evidence-capable. Tool-specific adapters may normalize raw output, but they must emit the same evidence contract.

## WebEvidence Tool Classification

The Agent Runtime classifies a tool as web/evidence-capable when its normalized tool name or semantic key matches one of:

- `web_search`
- `search_and_extract`

Implementation rule:

```java
isWebEvidenceToolName(toolName)
```

All code paths that decide whether to build web evidence observations must use this unified predicate. Do not add direct `web_search`-only branches for trust, citation, or reasoning.

## Evidence Contract

Evidence-producing tools should return evidence chunks under:

```json
{
  "evidence_chunks": [
    {
      "chunk_id": "stable chunk id",
      "title": "optional source title",
      "source_url": "https://source.example/page",
      "domain": "source.example",
      "score": 0.91,
      "content": "cleaned evidence text",
      "snippet": "optional short excerpt"
    }
  ]
}
```

Required for trust evaluation:

- `score`
- `content` or `snippet`

Required for citation binding:

- `source_url`, `url`, `link`, or `href`

Recommended:

- `chunk_id`
- `domain`
- `title`
- `score_breakdown`
- `rank_delta`
- `cache_hit`

## Trust Rules

`EvidenceTrustEvaluator` is the only Agent Runtime trust gate for web evidence.

Kernel v1 trust behavior:

- Evidence below the minimum score threshold is ignored.
- Untrusted or unknown domains are downgraded, not automatically rejected.
- Evidence whose adjusted score is below the usable threshold is ignored.
- Contradictory evidence sets mark `requestMoreEvidence=true`.
- Empty evidence sets mark `requestMoreEvidence=true`.

The evaluator emits trust metadata into the observation:

```json
{
  "version": "agent_evidence_trust_policy_v1",
  "usableCount": 1,
  "ignoredLowScoreCount": 1,
  "downgradedDomainCount": 0,
  "contradictionDetected": false,
  "requestMoreEvidence": false
}
```

Agent reasoning and answer review must treat `requestMoreEvidence=true` as a hard warning against strong claims.

## Citation Rules

Citation binding must happen after trust evaluation.

Rules:

- Only trusted usable evidence may enter the citation map.
- Low-score rejected chunks must not be cited.
- Contradictory evidence may still be listed only when the answer explicitly states uncertainty or conflict.
- Final answers must use citation labels only when those labels appeared in observations.
- The Agent must not invent URLs or citation labels.

Observation text should expose a citation map in this shape:

```text
Web citation map. Use these labels in the final answer when relying on web search evidence:
[网页1] Source title - https://source.example/page - supporting snippet
Citation rule: append the matching [网页N] label immediately after any sentence that uses facts from that page.
```

## Agent Reasoning Rules

The Agent Runtime consumes evidence only after it has become an observation.

Reasoning requirements:

- If web evidence is used, cite the matching observation label.
- If trusted evidence is insufficient, say so instead of making a strong claim.
- If internal document evidence and web evidence conflict, state the conflict explicitly.
- If a tool failed, do not claim that the failed tool provided evidence.
- If the Evidence trust policy requests more evidence, the answer reviewer must reject unsupported strong claims.

## Extension Rules

New evidence tools must follow this checklist:

- Add or reuse semantic tool key normalization.
- Ensure the tool is classified by `isWebEvidenceToolName` or a future equivalent contract predicate.
- Normalize output into `evidence_chunks`.
- Provide URL fields compatible with citation binding.
- Provide a numeric `score`.
- Add tests proving the tool enters Trust and Citation.
- Do not add a separate trust evaluator.
- Do not bind citations before trust evaluation.

If a tool cannot provide scored evidence chunks, it may still produce generic observations, but it must not be treated as trusted web evidence.

## Regression Checklist

Every change to the Evidence OS kernel should be tested against these drift checks:

- Trust drift: low-score chunks remain excluded from citations.
- Citation drift: trusted chunks with `source_url` still produce citation labels.
- Tool consistency: `web_search` and `search_and_extract` enter the same observation pipeline.
- Conflict behavior: contradictory chunks set `requestMoreEvidence=true`.
- Reviewer behavior: final answers with unsupported strong claims are rejected when more evidence is requested.
- Schema compatibility: `url`, `link`, `href`, and `source_url` are accepted citation URL fields.

## Frozen Kernel Boundaries

Frozen in v1:

- WebEvidence observation pipeline
- Evidence trust gate
- Post-trust citation binding
- Agent reasoning and review consumption rules

Allowed to evolve behind the contract:

- scoring thresholds
- trusted domain policy
- reranker implementation
- vector store implementation
- evidence normalization adapters
- observability and regression reporting

Not allowed without a v2 contract:

- parallel trust pipelines
- citation binding before trust
- tool-specific evidence semantics that bypass `WebEvidenceObservation`
- Agent final answers that consume raw web tool output directly
