# Problem analysis workflows

`document_search` is an operator inside the document-class problem workflow. It
is not the Agent Runtime's top-level execution model.

The stable Runtime OS execution line is:

```text
Agent Runtime OS
  -> Query Analyzer
  -> AnalysisIntent
  -> Workflow Router
  -> AbstractAnalysisWorkflow
  -> Concrete Workflow
  -> WorkflowPlan
  -> Operators / Capabilities
  -> EvidenceBundle
  -> Verification / Synthesis
  -> AnalysisExecutionOutcome
```

```mermaid
flowchart TD
    A[Agent Runtime] --> B[AnalysisQueryAnalyzer]
    B --> C[AnalysisIntent]
    C --> D[AnalysisWorkflowRouter]
    D --> E[DocumentProblemAnalysisWorkflow]
    D --> F[StructuredDataAnalysisWorkflow]
    D --> G[ToolAnalysisWorkflow]
    D --> H[ComputationAnalysisWorkflow]
    D --> I[ExternalResearchWorkflow]
    D --> J[CompositeAnalysisWorkflow]
    E --> K[EvidenceBundle]
    F --> K
    G --> K
    H --> K
    I --> K
    J --> K
```

## Parent lifecycle

Every child extends `AbstractAnalysisWorkflow`, which fixes the lifecycle order:

```text
UNDERSTAND -> SCOPE -> PLAN -> EXECUTE -> VERIFY -> SYNTHESIZE -> RETURN
```

The parent class contains no OpenSearch, SQL, MCP, calculation, or web-search
logic. Infrastructure is reached through a child workflow or an
`AnalysisCapabilityOperator`.

The Runtime kernel owns context, routing, plans, execution, loop control,
governance, evidence, state, and observability. Workflows own the analysis
method, Skills own domain rules, and Operators own atomic infrastructure
capabilities. `AnalysisExecutionOutcome` is the stable result contract returned
to the Runtime.

`AnalysisWorkflowRouter` uses the capabilities declared by `AnalysisIntent`.
One capability selects its child workflow; multiple capabilities select
`CompositeAnalysisWorkflow`. Models may produce `AnalysisIntent`, while the
router remains deterministic. `StandardAnalysisQueryAnalyzer` supplies a
bounded rule-based baseline when no intent has been supplied.

## Workflow implementations

| Workflow | Required capability | Plan shape |
| --- | --- | --- |
| Document | `DOCUMENT_SEARCH` | PostgreSQL route, OpenSearch hybrid recall, RRF, BGE, parent section, RocksDB verification |
| Structured data | `STRUCTURED_DATA` | entity/metric resolution, dataset route, SQL plan/execute, quality check, aggregation |
| Tool | `TOOL_CALL` | capability route, permission, selection, binding, execution, response verification |
| Computation | `COMPUTATION` | input resolution, data check, algorithm selection, calculation, boundary/result verification |
| External research | `EXTERNAL_RESEARCH` | freshness, trusted sources, search/retrieval, deduplication, authority ranking, cross validation |
| Composite | multiple | child workflows, evidence merge, cross validation |

The non-document workflows use `AnalysisCapabilityOperator`. A database, MCP,
Python, Spark, or web-search adapter implements that port and declares the
capability it provides. If an operator is unavailable, the workflow returns an
explicit verification finding instead of fabricating evidence.

## Unified evidence

Every workflow returns `EvidenceBundle` containing typed `AnalysisEvidence`:

- `DocumentAnalysisEvidence`
- `StructuredDataEvidence`
- `ToolAnalysisEvidence`
- `ComputationEvidence`
- `ExternalResearchEvidence`

Composite workflows merge only child bundles and check that every required
capability contributed verified evidence. Skills remain domain rules and
knowledge; workflows define how evidence is obtained and analyzed.

## Runtime integration

`DefaultAnalysisWorkflowRuntime` implements the stable `AnalysisRuntimePort`.
It analyzes, routes, and executes with the existing `KernelDataScope`.
`DocumentKnowledgeSkillExecutor` now enters through this port when it is
available, so bound-document Skill execution follows the same parent lifecycle.
Its local document-workflow fallback exists for isolated module tests and
deployments that do not include the Agent Runtime module.

Add a new top-level workflow only for a new analysis mechanism. Domain-specific
behavior belongs in Skills, plans, or capability operators.
