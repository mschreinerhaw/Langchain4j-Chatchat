---
name: python-data-analysis
description: Analyze delivered Python datasets, inspect quality, join sources, compute statistics and business metrics, and deliver reproducible static charts and Runtime OS reports.
version: 1.3.0
---

# Python data analysis in Runtime OS

Use this capability when the objective requires dataset exploration, calculations or charts. Choose the methods warranted by the question and evidence. For reusable program design and tests, also consider `python-code-development`.

## Input and evidence

Read the task's input manifest and schema before interpreting storage columns. Runtime OS supplies immutable authorized files; Skills provide methodology only. Use the delivered paths, mappings, presence markers, exact decimal scales and dataset reference IDs. Do not query another database, download data, install packages or infer authorization from examples. The existing `python_execute` callback runs code in Python Sandbox; this Skill grants no local shell or network access.

Inspect row counts, field types, units, date coverage, missing versus null values, duplicate keys and outliers. Preserve identifiers such as customer numbers as strings, including leading zeroes. Report absent fields and incomplete coverage; never invent observations, business definitions, joins, profit, loss or dates.

For Runtime tabular snapshots, consult the transport mapping example in [references/data-methods.md](references/data-methods.md) before writing the reader. For other format choices, multi-source checks and bounded-memory patterns, read that reference as needed.

## Analysis

Use pandas and NumPy for transformations, profiling and grouped statistics. Use SciPy or scikit-learn when the task calls for statistical tests or models and those packages are already installed in the selected Sandbox. State assumptions, sample size, missing-value treatment and uncertainty. Distinguish descriptive correlation from causation and validate model results with held-out data where applicable.

Define numerator, denominator, units, population and time window before calculating metrics. Use Decimal for exact financial values; retain full precision until presentation. Sort and align periods before time-series, year-over-year or period-over-period comparisons. Distinguish a missing period from zero activity. Guard zero denominators. Check join key cardinality and reconcile row counts and totals after joins; preaggregate where necessary to prevent multiplying amounts.

## Charts and delivery

Use Matplotlib with a noninteractive backend for static charts. Choose line charts for trends, bars for category comparisons, histograms/box plots for distributions, and pie charts only for a small, nonnegative part-to-whole distribution. Label titles, axes, units, legends and dates. Use the Sandbox's configured Chinese fonts; do not download fonts at runtime. Close figures after saving and avoid huge images.

For financial-industry charts and reports, read [references/financial-reporting.md](references/financial-reporting.md). Use declared currency/scale and comparable valuation, trade or settlement periods; separate monetary values, percentages, percentage points and basis points. Use a restrained research-report style, signed zero baselines and an explicit market color convention. Distinguish orders from fills, cash flows from returns, and observed activity from inferred long-term preferences. Derive every reconciliation label from actual calculations and retain unresolved evidence limits.

The financial reference includes tested, stateless Python recipes for labelled component reconciliation, ambiguous flow ordering and grouped numerical hypotheses. Prefer the applicable recipes to rewriting these checks. Inline the chosen recipe functions unchanged in a single-script proposal (or include them in an explicitly authorized code project); they are examples, not an installed Sandbox module. Bind fields and source scopes from this task's metadata. Render their returned component labels and unavailable values consistently in tables, prose and figures; never add an arbitrary last-row balance when ordering is unresolved. These recipes grant no I/O, execution or additional data permissions.

Use its `percentage_composition` for portfolio weights: show the exact total percentage separately from the sum of rounded display rows and disclose any display tail. For hypotheses linking an unconfirmed field to signed transaction amounts/costs, prefer `signed_amount_matrix` with actual dictionary field names. It binds the observed field to its label and evaluates all sign/cost candidates across each actual operation group; fee fields must be declared and distinct from the observed/amount fields. Preserve the returned reconciliation status rather than upgrading an arithmetic match into business certification.

Follow the task's existing output row schema, required filenames and visual-report contract. Save charts through the supplied artifact paths and reference only registered artifacts and exact authorized dataset references. A PNG or stdout alone is not the report. Deliver calculated metrics, their basis, data-quality findings, conclusions and explicit limitations. Inspect the actual execution receipt and reviewed report; repair unsupported findings before confirming completion. If required data is unavailable, return the contract's supported limitation/input-required outcome instead of fabricated metrics.
