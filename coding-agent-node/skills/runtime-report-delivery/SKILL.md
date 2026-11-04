---
name: runtime-report-delivery
description: Deliver autonomous evidence-based text and visual reports through Runtime OS; synthesize relevant sources rather than dumping retrieved records, preserve uncertainty, use actual Python chart artifacts and the existing report contract.
version: 1.0.0
---

Choose analysis methods, depth, report structure and charts from the user's actual objective and available evidence. This skill describes delivery and source discipline, never a fixed analysis sequence or new authorization.

Treat retrieved records as candidate evidence. Disambiguate the entity using explicit source identifiers; similar company names are not interchangeable. Inspect relevance, actual publication/observation time, source quality and duplicate content. Read original articles when necessary. Exclude irrelevant hits from conclusions. Explain which evidence supports each material claim, what is inference, and what remains unknown. Search counts do not establish company fundamentals, valuation or causation.

Author a report that answers the business question, with compact comparisons and qualified conclusions when warranted. Do not reproduce the full retrieval inventory, sequential record cards, every source title/URL/body, technical dataset fields, or a raw JSON dump in reportMarkdown. Preserve exact source IDs in evidenceReferences and adjacent citations; a small relevant quotation or source table can support your analysis. Processing all delivered inputs does not require publishing all raw rows. Insufficient evidence supports a clear limitation, never an invented explanation.

Use the task's reportMode and user preferences. Text mode does not forbid useful Python analysis. A visual preference allows useful charts based on real authorized numerical observations; it does not require a chart for qualitative text. Do not chart keyword-hit counts as financial performance, invent missing history, units or derived financial definitions. When no reliable numerical data is available, deliver supported text and explain why a chart is not justified.

When python_execute is authorized, use the existing Sandbox and immutable input descriptors. Resource tools may return a calculationInput descriptor: read that exact snapshot through its declared manifest/schema and retain its datasetReference and evidence_id. Do not transcribe or hardcode source measurements from the prompt into a program. The Sandbox has no external network or database access. Skills never expand its tools. Select the already available python-data-analysis skill and relevant original analyzer/plotting guidance as needed.

Follow the supplied outputContract and visual-report schema, including the actual rows.jsonl location and chart basenames. The existing visualReport is the artifact manifest; do not invent a parallel manifest. Generate report values and charts from the same computed objects in one Sandbox execution. Static PNG/SVG is appropriate for report delivery; upstream examples of HTML, local hosting, shell commands and external databases do not apply to this task. Runtime stores files and execution evidence through its existing archive; never upload to an external service or request new credentials.

For text-only output without Python, use report_submit when advertised, including the same-session source review. After Python, use its result-file and artifact review lifecycle; never submit a text receipt that hides a failed execution. Review the actual report and artifact/reference validation, repair defects within budget, then confirm completion. Tool success, exit code zero, a chart filename, or an assistant message alone cannot certify a delivered report. Preserve PARTIAL/INPUT_REQUIRED and actual failures; do not rewrite them as success.
