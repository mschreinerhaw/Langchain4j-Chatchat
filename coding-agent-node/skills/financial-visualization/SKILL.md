---
name: financial-visualization
description: Render financial analysis and exploratory reports with semantic colors, market-specific rise/fall conventions, stable comparison palettes, clear units and legible Chinese labels. Use for financial-auto, financial-cn and financial-international visualization style profiles, market movements, portfolio comparisons, allocation and financial report charts.
metadata:
  version: 1.0.0
---

# Financial visualization

Use this Skill for financial charts and the `financial-*` presentation profiles. It governs presentation, not business definitions, investment decisions or data access. Choose chart types and project structure autonomously using actual task data. It does not grant tools, extra network access, new queries or permission to reinterpret fields.

Read [references/style-guide.md](references/style-guide.md) before preparing a financial plot. Resolve `reportPresentation.styleProfile` from the frozen task context. If available, use the stateless constants and helpers in [references/financial_style.py](references/financial_style.py) in your own workspace code; they need no extra packages. Adapt presentation only, never rewrite source amounts, signs, adjustment basis or units. Keep the chosen profile and color meaning in the figure legend/caption and report.

The profiles are Runtime OS presentation conventions, not a claim that one palette is a universal regulatory standard. For a known mainland-China market, use red for an observed rise and green for an observed fall. Hong Kong and many international displays use the opposite convention. An explicit user convention takes precedence and must be labelled. Unknown or mixed market conventions require neutral plots or clearly separated labelled panels, not a guessed sign palette.

Absolute positive balances, asset values, revenue and category totals are not rises. Use stable blue/teal/purple/ochre series colors for comparisons and allocation; reserve directional red/green for actual change/return with a known reference. Do not apply financial colors to unrelated nonfinancial analysis merely because the UI offered a financial style.

Export actual PNG/SVG or offline Plotly artifacts supported by the task. Keep axes, units, dates, source coverage and legends readable; preserve the same series colors across charts. Never edit an archived PNG's pixels to simulate a correct semantic plot. Generate the intended styling in the original plotting code and submit it through the normal delivery manifest. Use `plotting` and `runtime-report-delivery` as useful, without a fixed execution sequence.
