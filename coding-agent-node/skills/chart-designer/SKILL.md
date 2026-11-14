---
name: chart-designer
description: Choose useful ECharts charts with consistent styling, comparable financial series, visible source metadata and offline print fallbacks.
---

Use the shared report-designer assets/report.js ECharts renderer instead of regenerating chart infrastructure. Time evolution: line; category comparison: bar; quantity and rate: combo with declared separate units; association: scatter; two-dimensional intensity: heatmap. Keep charts only when they answer a question. Use shared palette, font, legend, axis, tooltip and readable labels. Every chart declares id, purpose, type, period, unit, sourceIds, basis, labels and series [{name,values}]. Scatter values are [x,y]; heatmap values are [column,row,value]; combo series may declare type and axis (0 or 1) with axisUnits. Null means missing, never zero; do not connect gaps. Include chart data tables and JSON downloads. Non-comparable data must not be ranked or overlaid.

Financial figures: normalize aligned adjusted prices to 100 at a common valid base date; relative performance requires matching benchmark dates and return conventions; drawdown uses running peaks; risk/return scatter requires identical observation windows, frequency and annualization. Label price vs total returns and currencies. Never synthesize absent benchmarks or history. ECharts canvas snapshots serve print after rendering; visible tables remain available without JS. Use a local version-pinned platform ECharts bundle and check actual rendered chart count, canvas dimensions, errors, narrow viewport and print. Capture rendered figures/PDF when requested; do not claim screenshot validation from a successful script exit alone.

Do not repeat the same category bars as both amount and percentage charts when they express the same ordering and proportions. Prefer one chart with a useful table or tooltip. Each additional chart must answer a different analytical question, rather than add page length.
