# Claim-level analysis acceptance

The analysis graph supplies evidence-bound findings. `GovernedFinalClaimContract`
then executes `ClaimAcceptanceGraph` on the live publication path:

```text
BUILD_CLAIMS -> PROGRAMMATIC_VALIDATE -> SEMANTIC_REVIEW
                                          | issues
                                          v
                                    REPAIR_CLAIMS
                                      | patches
                                      v
                                    VALIDATE_ONCE
                                          |
              no issues / no patches -----+-> ASSEMBLE_VERIFIED_REPORT
                                                   |
                                     ReportPublicationGraph.PUBLISH
```

There is no edge back to semantic review. Technical failures and user cancellation
remain distinct from business uncertainty. VALID findings retain their content
and block IDs; LIMITED findings use narrower admitted evidence; UNRESOLVED findings
are represented as limitations. A rejected finding never causes unrelated valid
findings to be replaced by a full-report fallback.

## Authority and budgets

- The original user question is bound by `AgentOrchestrationEngine`, not inferred
  from the final report. Review receives admitted claim references and their scope.
- Programmatic validation checks lineage, rejected references, numeric provenance,
  and supported scope/confidence constraints. Numeric provenance alone is not a
  proof of a formula, unit, aggregation rule, or business interpretation.
- One semantic model call checks meaning, scope, unsupported causality, and
  cross-claim conflicts. Every review must identify an existing claim and evidence.
  Its proposed text is not a new verified fact.
- Semantic review has a 48,000-byte input cap and waits at most 10 seconds, further
  limited by the remaining run deadline. Provider errors, invalid output, partial
  output, and timeout have explicit dispositions. A bounded executor prevents an
  unresponsive provider from creating unlimited background tasks; interrupting an
  HTTP provider is best effort. Runtime cancellation stops publication.
- Repair has one wave, at most 10 claims and 15 seconds of admission budget. It
  performs no model calls. Current executable patches narrow to admitted evidence
  or remove the unsupported assertion. Patches are revalidated once. Unknown
  formulas and unverified model-proposed calculations are not silently authorized;
  they remain unresolved or are replaced with existing supported observations.

`AnalysisAcceptanceContract` declares question coverage, evidence integrity,
numeric consistency, scope consistency, claim strength, report consistency, and
repair policy. Its serialized snapshot uses plain maps/lists so existing graph
checkpoints can retain it.

## Delivery and audit

`claimAcceptance` stores per-finding issues, original and patched text, evidence
IDs, validation and delivery status, repair count, semantic decisions, and context.
`claimAcceptanceGraphNodes` records executed stages and durations. Structured reports
retain the same audit under `claimAcceptance` and `acceptanceGraphNodes`.

`CLAIM_LEVEL_PARTIAL_DELIVERY` is delivered with its structured blocks and is
classified as `COMPLETED_WITH_LIMITATIONS`. It does not enter full-report model
regeneration. Stale acceptance metadata is cleared before another attempt.

These checks establish a bounded, auditable acceptance process, not a guarantee
that a model can detect every possible natural-language contradiction. Missing
evidence remains a scope limitation rather than an invented business fact.
