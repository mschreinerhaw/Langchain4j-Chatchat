---
name: python-data-analysis
description: Analyze delivered Python datasets, inspect quality, join sources, compute statistics and business metrics, and deliver reproducible static charts and Runtime OS reports.
metadata:
  version: 1.3.4
---

# Python data analysis in Runtime OS

Use this capability when the objective requires dataset exploration, calculations or charts. Choose the methods warranted by the question and evidence. For reusable program design and tests, also consider `python-code-development`.

## Input and evidence

Read the task's input manifest and schema before interpreting storage columns. Runtime OS supplies immutable authorized files; Skills provide methodology only. Use the delivered paths, mappings, presence markers, exact decimal scales and dataset reference IDs. Do not query another database, install packages or infer authorization from examples. Use the execution capability actually advertised by the task: Native Cline tasks run Python through native Shell in their local workspace; managed tasks may advertise `python_execute` for the existing Sandbox. Public supplementary files may be obtained through an authorized `web_download` tool and its verified local path. This Skill itself grants no tool or network authority.

Inspect row counts, field types, units, date coverage, missing versus null values, duplicate keys and outliers. Preserve identifiers such as customer numbers as strings, including leading zeroes. Report absent fields and incomplete coverage; never invent observations, business definitions, joins, profit, loss or dates.

For Runtime tabular snapshots, the delivered reader descriptor names the installed checksum-verifying transport functions. They return logical dictionaries and leave source selection and analysis to you. Consult [references/data-methods.md](references/data-methods.md) as needed for original formats, multi-source checks and bounded-memory patterns.

## Analysis

Use pandas and NumPy for transformations, profiling and grouped statistics. Use SciPy or scikit-learn when the task calls for statistical tests or models and those packages are already installed in the selected environment. State assumptions, sample size, missing-value treatment and uncertainty. Distinguish descriptive correlation from causation and validate model results with held-out data where applicable.

Define numerator, denominator, units, population and time window before calculating metrics. Use Decimal for exact financial values; retain full precision until presentation. Sort and align periods before time-series, year-over-year or period-over-period comparisons. Distinguish a missing period from zero activity. Guard zero denominators. Check join key cardinality and reconcile row counts and totals after joins; preaggregate where necessary to prevent multiplying amounts.

## Charts and delivery

Use Matplotlib with a noninteractive backend for static charts. Choose line charts for trends, bars for category comparisons, histograms/box plots for distributions, and pie charts only for a small, nonnegative part-to-whole distribution. Label titles, axes, units, legends and dates. Use the environment's configured Chinese fonts; do not download fonts at runtime. Close figures after saving and avoid huge images.

Use declared currency/scale and comparable valuation, trade or settlement periods; separate monetary values, percentages, percentage points and basis points. Use a restrained research-report style, signed zero baselines and an explicit market color convention. Distinguish orders from fills, cash flows from returns, and observed activity from inferred long-term preferences. Derive every reconciliation label from actual calculations and retain unresolved evidence limits. Consult [references/financial-reporting.md](references/financial-reporting.md) when the objective needs accounting reconciliation, ambiguous flow ordering or portfolio hypotheses. A price trend or other financial topic alone does not require loading that reference.

Follow the task's existing output row schema, required filenames and visual-report contract. Save charts through the supplied artifact paths and reference only registered artifacts and exact authorized dataset references. A PNG or stdout alone is not the report. Deliver calculated metrics, their basis, data-quality findings, conclusions and explicit limitations. Inspect the actual execution receipt and reviewed report; repair unsupported findings before confirming completion. If required data is unavailable, return the contract's supported limitation/input-required outcome instead of fabricated metrics.
