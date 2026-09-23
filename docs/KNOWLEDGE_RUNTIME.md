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

The contracts are grouped by responsibility:

| Package | Responsibility |
| --- | --- |
| `knowledge.model` | Normalized IR, rules, scope, semantic types and source lineage |
| `knowledge.search` | Search documents, ranked hits, statuses and result protocol |
| `knowledge.index` | Scoped index query and document write messages |
| `knowledge.extraction` | Ingestion-time extraction request |
| `knowledge.skill` | Whitelisted Skill definitions, plans and results |
| `knowledge.runtime` | Runtime request, compiled context and Skill execution input |
| `knowledge.spi` | Runtime, extraction, indexing, search, compiler and Skill extension ports |
| `knowledge.template` | Root namespace for governed business-template contracts |

Only `package-info.java` belongs directly in the `knowledge` root package. New
contracts must be placed according to their role so infrastructure ports do not
accumulate beside domain values.

Template contracts are divided again by their lifecycle role:

| Package | Responsibility |
| --- | --- |
| `knowledge.template.model` | Canonical template knowledge, business intent and relationships |
| `knowledge.template.matching` | Candidate evaluation, semantic coverage and worker analysis context |
| `knowledge.template.resolution` | Resolution events, failures and recovery actions |
| `knowledge.template.service` | Transport-neutral discovery and execution service protocol |
| `knowledge.template.protocol` | Provider payload normalization into canonical template knowledge |

`KnowledgeRequest.maxTokens` is a first-class safety limit and is capped by a platform hard
maximum. The current compiler uses UTF-8 byte length as a conservative token upper bound and
never returns a compiled context above the request budget.

## Current adapters

`ModelDrivenKnowledgeSkillSynthesizer` creates dynamic goals and query hints with the selected
Agent model. Its JSON result is validated against the static enum and plan budget; malformed,
unknown or disallowed capabilities fall back to the deterministic planner.

Documents are normalized offline by `KnowledgeDocumentIngestionService` whenever they are created,
updated or reindexed. The default `DeterministicKnowledgeExtractor` chunks content, classifies
knowledge types, compacts each unit and preserves source lineage. A domain-specific or model-backed
extractor can replace it through `KnowledgeExtractionPort` without changing Agent Runtime.

`JpaKnowledgeIRIndex` persists the extracted units in `knowledge_ir_unit`. Runtime first searches
this native, tenant-scoped IR index. `DocumentKnowledgeSkillExecutor` remains a compatibility
fallback over the governed document index while old documents are being reindexed. Even on that
path, only ranked chunks selected inside the Agent's scope enter the budgeted compiler; the complete
raw document is never appended to the prompt.

Role Chat uses a 1200-token knowledge budget. Tool Agent uses 1500 tokens. Runtime metadata reports
the selected Skill count, estimated knowledge tokens, budget and whether compilation truncated the
result.

Existing installations must apply `V20260910_01__knowledge_ir_runtime.sql` and reindex existing
documents once to populate native IR. New and modified documents are synchronized automatically;
document deletion removes the corresponding IR units.

## Evidence boundary

Knowledge supplies definitions, rules, methods and constraints. Current facts and calculations in
Tool Agent remain grounded in `<tool_evidence>`. Examples found in knowledge are never current-user
facts.
