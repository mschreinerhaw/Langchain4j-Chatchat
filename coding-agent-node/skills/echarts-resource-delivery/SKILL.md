---
name: echarts-resource-delivery
description: Build interactive ECharts reports with the Runtime OS Frontend Resource Hub, render them in the system HTML viewer, and package runnable HTML, CSS, JavaScript and authorized chart data for offline download.
metadata:
  version: 1.0.0
---

# ECharts reports with Frontend Resource Hub

Use when the task authorizes HTML reports or downloadable interactive charts and the presentation calls for a chart. Follow the task's report mode, chart preferences and artifact contract. For a financial report, load `financial-visualization` for chart choice, units and market conventions. Load `chart-designer` and `report-designer` when advertised for layout and professional report helpers.

## Select resources and build the report

1. Load the advertised `frontend-design` Skill and use its `deliver.py` from the task workspace. Discover `echarts` and any matching theme, template or component through the task-bound Frontend Resource Hub. Use the exact catalog paths, versions and dependency order. Record the versions and preserve asset licenses. The broker capability stays in `input/frontend-transport.json`.

   ```sh
   python "<frontend-design-skill-folder>/deliver.py" --query /api/v1/resources
   python "<frontend-design-skill-folder>/deliver.py" --query '/api/v1/templates?q=data-analysis'
   ```

2. Use a matching Hub template as a starting point. If professional reporting is available, prefer `professional-report` shared styles and the `report-designer` helpers `prepare_report_assets.py` and `finalize_report.py`. For a custom financial series or layout, use the catalog-pinned classic ECharts script and one task-authored renderer. When adapting a template, choose either its `window.BUSINESS_DATA` binder or the custom renderer. Loading both can overwrite data or initialize charts twice.
3. Generate chart series, legends, tables and summaries from the same authorized computed observations. Use safely serialized JSON, and escape any markup-significant characters if embedding it in HTML. Keep only the figures necessary for the report in the artifact. Render financial labels, axes, tooltip, legend and data table together: record security, time window, currency, scale, unit and benchmark or adjustment basis where applicable. Use a table for exact values and make tooltips supplementary.
4. For the Hub preview, reference the registered resource paths. Run the advertised preview helper and review its desktop, mobile and print evidence. Keep charts within the card width; initialize or resize charts after their tab becomes visible. Inspect all tabs and fix overlaps, clipped axes, and long names in the layout. The default preview receipt alone does not guarantee every tab or label was checked.

## System display and offline download

- **System HTML viewer.** Deliver exactly one canonical HTML entry as `REPORT` where the native contract requires it. Use the finalized HTML with relative local assets, classic scripts, and report data bundled with the entry. Register each referenced file through `delivery_manifest.json`, so the viewer can resolve it inside the authenticated report sandbox. Hub preview URLs are for preview preparation; they are not viewer asset URLs.
- **Download.** When an offline download is requested, package the runnable report code, not a screenshot or an HTML file detached from its dependencies. Reuse the Hub's offline bundle and its `standalone.html` entry, or package the finalized report entry and its local CSS, scripts, images, fonts and licenses to a ZIP inside the authorized output. Include a short opening instruction naming the entry file. Preserve relative paths. Register any additional ZIP using a type allowed by the existing artifact contract. Only include report-authorized data; omit task input dumps, broker descriptors, credentials and private evidence outside the selected report scope. Do not rerun data retrieval for the download.
- **File-based rendering.** Use locally bundled classic scripts and embed small, safely serialized chart series in HTML or a local classic data script when necessary. The authenticated viewer can resolve delivered JSON, but browsers often block `fetch()` from a downloaded `file://` page. Avoid that dependency in the offline version. The downloaded report must open without a server, login, CDN or backend request. For an explicitly requested single HTML file, inline the needed authorized assets if the delivery budget and licensing allow it; otherwise keep the package intact with its entry and local assets.
- **Verify what is delivered.** Open the actual viewer artifact when available. Extract the download to an empty folder and open its entry in a browser with HTTP blocked. Check the chart instance, visible series and exact values, local tab/filter controls, console errors and horizontal overflow. Verify that online and offline reports use the same computed values, labels, units and sign colors. If a browser or the Hub is unavailable, retain readable local tables or static charts and state which interactive/offline checks remain unverified.
