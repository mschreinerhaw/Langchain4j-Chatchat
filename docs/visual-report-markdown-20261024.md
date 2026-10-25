# Visual report narrative rendering

The latest successful production task stored Markdown in the visual report's section
narratives. `PythonVisualReport.vue` displayed those values through text interpolation
inside a paragraph, exposing heading and emphasis delimiters to the reader. The
persisted report and chart artifacts were valid; the defect was in presentation.

The report now renders narrative, summary and limitation text through a Markdown parser
with raw HTML disabled. Headings, emphasis, lists, tables, links and code blocks become
HTML elements. Whole-document Markdown fences are unwrapped; ordinary code examples
remain literal. Numeric strings, charts, structured tables and archived report objects
are preserved. Links use `noopener noreferrer` when opening a new tab.

## Verification

- 25 targeted tests passed, covering report presentation, archived artifact handling,
  calculated-value preservation, Markdown structures, source fences and unsafe markup.
- A real authenticated history conversation was opened in Chromium. The actual archived
  report rendered headings, emphasis and lists, retained all metric values, and loaded
  all four original PNG charts. Desktop and mobile checks passed without page errors or
  failed requests.
- PDF export succeeded with the rendered report, producing a 1,327,185-byte PDF.
- The report resource hash in the persisted manifest remained unchanged. The browser
  issued zero analysis submissions during verification.
- The task was successful, while its report's original PARTIAL state was preserved:
  its program-declared warning concerns an empty active-transaction dataset. This
  presentation fix does not certify business calculations or change that limitation.

## Deployment

API image `2.4.21-report-markdown-20261024` contains the renderer from commit
`15b6baedc`. The release changes four static assets: a new renderer and stylesheet,
plus two existing chunks that reference them. Runtime import/export slots were checked
before reusing the deployed frontend runtime and Markdown library. Every unrelated JAR
entry, including Java classes, is verified byte for byte against the running baseline.

Deployment included configuration/database backups, task-idleness checks, health checks,
artifact hashes and unchanged permission/template-binding fingerprints. The original
conditional request for a changed JavaScript chunk returned HTTP 200 with an updated
Last-Modified value. Reloading the page loads the new report renderer.

Both preview and final live browser acceptance passed; the final browser run used no
HTTP fixtures or asset overrides. Private release and acceptance evidence is retained
under `/opt/chatchat-releases/report-markdown-20261024`.
