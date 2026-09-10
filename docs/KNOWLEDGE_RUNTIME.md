# Knowledge Runtime

Knowledge is a Runtime capability, not raw prompt material. Agent Runtime consumes only a
budgeted `KnowledgeContext`; it does not consume PDF, Word, Excel, document chunks, vector
search responses, or provider-specific retrieval types.

```text
Document source
  -> KnowledgeExtractionPort
  -> KnowledgeIR
  -> governed index

User task
  -> KnowledgeSkillSynthesizerPort
  -> validated KnowledgeSkillPlan
  -> KnowledgeSkillExecutorPort
  -> relevant KnowledgeIR
  -> KnowledgeContextCompilerPort
  -> budgeted KnowledgeContext
  -> Role Chat / Tool Agent
```

## Contract boundary

Stable, framework-neutral contracts live in `chatchat-common` under
`com.chatchat.common.knowledge`:

- `KnowledgeExtractionPort` converts governed content references into normalized IR.
- `KnowledgeSkillSynthesizerPort` may use a model to generate task-specific Skill instances.
- `KnowledgeSkillType` is the platform whitelist; a model cannot create executable types.
- `KnowledgeSkillExecutorPort` executes validated instances without exposing storage internals.
- `KnowledgeContextCompilerPort` ranks, deduplicates and compiles IR under `maxTokens`.
- `KnowledgeRuntimePort` is the only knowledge dependency used by Agent Runtime.

`KnowledgeRequest.maxTokens` is a first-class safety limit and is capped by a platform hard
maximum. The current compiler uses UTF-8 byte length as a conservative token upper bound and
never returns a compiled context above the request budget.

## Current adapters

`ModelDrivenKnowledgeSkillSynthesizer` creates dynamic goals and query hints with the selected
Agent model. Its JSON result is validated against the static enum and plan budget; malformed,
unknown or disallowed capabilities fall back to the deterministic planner.

`DocumentKnowledgeSkillExecutor` is a compatibility adapter over the existing governed document
index. It enforces Agent document scope and converts search results into `KnowledgeIR`. It is not
part of the common contract and can be replaced by a native Knowledge IR/vector/graph index.

Role Chat uses a 1200-token knowledge budget. Tool Agent uses 1500 tokens. Runtime metadata reports
the selected Skill count, estimated knowledge tokens, budget and whether compilation truncated the
result.

## Evidence boundary

Knowledge supplies definitions, rules, methods and constraints. Current facts and calculations in
Tool Agent remain grounded in `<tool_evidence>`. Examples found in knowledge are never current-user
facts.
