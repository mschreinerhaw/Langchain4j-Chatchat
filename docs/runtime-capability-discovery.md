# Runtime capability discovery

Model decides. Runtime executes. User judges.

## Observed failure

On 2026-10-09 the deployed `web_search` already described governed financial retrieval.
The MCP execution logs nevertheless showed company-code reads against ETF scale and
index datasets, with zero observations. The internal relevance matcher was selecting
datasets without a model-visible directory. In addition, the authoritative workflow
planner used a compact tool description without the full invocation schema.
Repeated live requests also exposed a query-scope loss: the default enrichment read
only the first resolved entity filter. It now executes every resolved binding within
the existing resolver limit, preserving each filter and isolating read failures.
The dataset budget counts populated datasets, rather than consuming a slot for each
entity of the same dataset. This is completion of requested retrieval scope, not a
model-independent business analysis plan.

## Contract path

`McpToolProvider.capabilityManifest(toolName)` publishes factual affordances through
the existing MCP registry and transport metadata. The Agent's
`CapabilityManifestInjector` projects only the already authorized tool list into both
planner paths. A configured tool label cannot erase its publisher's parameter schema
or manifest. A manifest does not create tool aliases, permissions or executable code.

`web_search` retains the same governed workflow and adds explicit operations:

| Operation | Meaning |
| --- | --- |
| `search` | Existing local-first financial/news/web retrieval |
| `discover_datasets` | Registered dataset directory, fields and filter contracts; not observation evidence |
| `read_dataset` | Read the model-selected registered dataset using validated filters and dates |

The financial manifest is derived from the installed service and storage catalog.
It does not promise live quotes, new acquisition APIs, unregistered indicators or
invented `market_data.fetch` / `market_data.query` aliases. Its dataset directory is
refreshed on provider publication; `discover_datasets` reads the current catalog.
The original automatic search remains compatible, but an automatic match does not
certify business relevance or that the user's evidence needs have been met.

The first online retest also exposed a continuation boundary: the analysis model
could read existing evidence but could not ask MCP for new evidence. `HarnessToolAccess`
now exposes optional `CALL_TOOL` requests for the Agent's authorized read-only tools.
Execution uses `AgentToolCallCoordinator`, including prior trace continuity and the
existing Tool Runtime gates. The Agent's remaining step budget caps extra calls.
New results are checkpointed, projected through the existing evidence protocol and
registered as scoped workspace handles; the model can read or compute them next turn.
This is a tool capability, not a second planner or a mandatory retrieval stage.
Continuation traces use JSON-compatible metadata and are merged into the final
execution trace and tool count.

## Data boundary

An explicit read returns actual rows, registered field metadata, filter/date scope,
the reusable dataset code and source metadata. `count` is the returned row count;
`boundedRead=true` and `totalRecordCountKnown=false` avoid implying full coverage.
The Runtime's existing evidence workspace retains these observations and gives the
analysis model scoped result handles for reading, computing and report binding.
Dataset discovery itself does not count as collected price evidence.

Model selection must use the installed tool and declared parameters. The existing
MCP authorization and financial-store identifier/field validation remain in force.
No stock-name routing, forced financial checklist or report quality grader is added.

## Verification

Targeted tests cover manifest projection, the compact planner's contract preservation,
authorization boundaries, absence of unsupported financial operations, explicit
directory/read execution without automatic dataset routing, MCP publication and
transport, and the existing Single Brain analysis harness. Live artifacts are kept
under the ignored `target/codex-live/capabilities-20261009/` directory.

Live verification on 2026-10-09 exposed seven actual registered datasets. A single
governed search for the three-company market question returned three distinct
entity-filter scopes from `market_quote_daily`: 9, 18 and 17 observation rows,
44 in total. An earlier Agent retest also demonstrated two model-selected continuation
calls and a subsequent model-authored report referencing their workspace results.
These checks establish discovery, execution, scope preservation and data access;
they do not certify the model's financial interpretations.

The final Agent retest (`7c83a93a-972e-42f1-94d4-c687eccc6ee9`) completed
successfully with one native analysis-model turn. Its evidence retained all three
resolved company scopes, and the model-authored report referenced the quote dates
and prices for each company. No additional summary-model call was introduced.
Its chart proposal failed field validation (`UNKNOWN_FIELD`), so the renderer
correctly displayed zero automatic charts. Browser replay of this actual report
confirmed the collapsed gray 12px query appendix and no JavaScript errors.

One retest proposed three charts against mixed news/observation datasets. All three
were rejected with `MISSING_SOURCE_VALUE`; the report remained readable and the
production renderer showed no unverified chart. The query appendix remained
collapsed, gray and 12px, with no browser errors. This is the intended deterministic
fallback, not a successful chart-generation claim.

The related test suite contains 85 passing tests. Original API and MCP programs are
backed up under `/opt/chatchat-deploy-backup/capabilities-20261009/`.
