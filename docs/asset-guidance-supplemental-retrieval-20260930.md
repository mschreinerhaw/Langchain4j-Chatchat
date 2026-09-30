# Asset guidance: supplementary discovery

## Failure and contract

The reported task treated a template page with no relevant candidate as a failed prerequisite, projected service catalogue metadata into a business dataset, and started a DAG rewrite. Retrieval success, candidate relevance and answer readiness were being conflated.

Discovery-only workflows now use a separate completion path: execute the configured discovery steps, retain semantic selection results, then synthesize the explanation from the existing question analysis, Skill context and available evidence. Supplementary retrieval does not require pagination or plan rewriting. Entering explanatory synthesis closes retrieval with `finalSynthesisRetrievalAllowed=false`; both configured-source and automatic web enhancement honor that boundary. A rejected candidate remains rejected and must not be described as a matching asset.

The policy is derived from registered capability roles, not tool names, Agent identifiers or question keywords. Every available tool and every non-presentation plan step must declare an asset/template discovery role. Explicit data, document and action workflows, unknown roles and any data execution capability stay on the strict path. Required calls still execute; this policy does not grant authorization or turn transport failures into successful calls.

The graph joins at `FINAL_SUPPLEMENTAL` and waits for explanatory model synthesis and ordinary answer review before ending. The task evidence contract becomes optional while retaining mandatory tool calls and the original no-invented-facts setting. This path does not run business-record claim auditing or append dataset-completeness reports. The final claim ledger audits explicit source references only (`EXPLICIT_REFERENCES_IN_EXPLANATORY_ANSWER`), while ordinary semantic answer review owns narrative assessment. Example numbers are not treated as retrieved business records; unknown explicit references still fail. Catalogue metadata remains available to explanatory synthesis but is excluded from business dataset analysis, including batch children. Data acquisition results remain eligible for dataset analysis.

## Verification

Focused tests cover capability-based admission, no selected tools, unknown capabilities, explicit non-asset workflows, semantic rejection versus transport failure, pages with/without accepted candidates, ordinary dependency recovery, discovery dataset exclusion, business dataset preservation and lifecycle completion.

Deployment and live verification results are recorded below. Existing unrelated regression failures documented in the runtime reliability audit are outside this change's passing test claim.

## Deployment

- Final API SHA-256: `2bb55d546b8917525c0aa06871ba2f5d12b840241693e3a47c760fcc986630cf`.
- Server API PID: `1419689`; remote checksum matches, Web HTTP 200, MCP tools registered (32).
- Backup before final update: `/opt/chatchat-deploy-backup/supplemental-20260930-0815/chatchat.jar`.
- Original pre-change API backup: `/opt/chatchat-deploy-backup/supplemental-20260930-0720/chatchat.jar`.
- Focused regression: 87 tests, zero failures/errors/skips; reactor package BUILD SUCCESS.
- No-MCP live test: task `764e8fcf-d342-3ed3-b5f7-f9820d542103`, SUCCESS, zero tool calls, about 21 seconds. Temporary test Agent deleted.
- Professional data-analysis comparison on the first deployment: task `efefbb7d-5c9d-3bc2-b707-dbd54a5c6e43`, PARTIAL_SUCCESS, all three discovery/execution calls succeeded, 115 records across seven business datasets, Runtime COMPLETED before Task completion. Supplementary policy was false. Existing claim coverage failure and Runtime/UI public-status discrepancy remain; this is not a full business-quality pass. The final update changes only supplementary explanation synthesis/contract handling.
- The first post-restart asset request arrived before tool registration and was rejected by admission. Tests were resubmitted only after required tool registration was verified.

## Final live acceptance

Original question: `?????? ?????????????????`.

- Task `7d4af533-22cd-3d91-b4d2-053865b9cf55`: **SUCCESS**, about 302 seconds, 609-character explanatory answer.
- Runtime `att-1-3b72e1eb-cb4f-41a0-82a9-fc9d54e8b448`: **COMPLETED**, finished before the parent Task.
- Exactly two successful calls: service discovery and template discovery. No template execution, pagination round, web search or business dataset analysis.
- Rewrite count and maximum rewrite count both zero; no observation requesting a full plan rewrite. Plan result review satisfied.
- Graph: `PREPARE ? INITIAL_DATA ? INITIAL_ANALYSIS ? FINAL_SUPPLEMENTAL ? END`.
- Evidence requirement OPTIONAL; explanatory synthesis completed; retrieval closed by workflow and respected by final-summary enhancement.
- Claim ledger scope `EXPLICIT_REFERENCES_IN_EXPLANATORY_ANSWER`; status NOT_APPLICABLE for this reference-free explanation; ordinary answer review retained. No failed data-claim audit or dataset appendix.
- End-to-end assertions passed for task/runtime terminal ordering, phase transitions, call count, retrieval closure, audit scope and absence of rewrite/business-analysis events.
- Final health UP, Web HTTP 200, active Agent tasks zero.
