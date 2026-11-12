# Financial chart style v1

## Scene and market convention

`financial-auto` means a financial analysis/report scene with the direction convention selected from authorized source metadata and explicit user instructions. Known mainland exchanges (SSE/SZSE/BSE) normally use red up and green down. HKEX and many international displays use green up and red down. Do not infer the market from the interface language, a positive amount or a guessed security name. Without a resolved convention, use neutral series colors and disclose that direction colors are unspecified.

`financial-cn` explicitly selects red up/green down. `financial-international` explicitly selects green up/red down. These are display choices, not a market assertion. If the chosen convention differs from the source market's usual display, make the selected legend explicit. `auto` is general analysis and does not require this financial profile. Unsupported profiles must not silently become a China-market convention.

There is no universal financial hexadecimal palette. This project's chosen accessible, restrained palette is:

| Role | Color |
| --- | --- |
| Direction red | `#C62828` |
| Direction green | `#16845B` |
| Zero/flat/benchmark | `#64748B` |
| Comparison series | `#245A81`, `#168A91`, `#695AA6`, `#9B6A27` |
| Body/axis text | `#243746` |
| Grid | `#E2E8F0` |
| Background | `#FFFFFF` |

The up/down assignment follows the selected convention, not the sign of arbitrary raw values. Distinguish negative change from high risk: a negative expense change can be favorable, and a positive exposure is not automatically favorable. Do not label favorable/unfavorable without explicit business definitions. Missing/NaN values are unavailable, never zero/flat. Flat means a real observed change of zero. Use signs, labels, line patterns and marker shapes as well as color so red/green distinction is not the only encoding.

## Chart semantics and layout

* Actual price/return change: use the declared reference (for example previous close), currency/unit and date. Show the reference line when appropriate. Do not compute a change from unrelated observations. For candlesticks, use actual open/high/low/close and disclose whether candle direction uses close-minus-open; daily return color can instead use previous close. Do not silently conflate the two.
* Category balances, revenues and allocation: stable neutral category palette, not red/green by numeric sign. A single comparison series may be blue. Share plots require a real nonnegative whole and denominator. Negative or incomplete components cannot be forced into a pie chart.
* Continuous amounts and dates: sequential blue intensity or stable series lines. Diverging heatmaps require a meaningful declared center; a zero-centered return heatmap must label the center and convention. Do not use a rainbow palette or an unrelated scale for comparable panels.
* Different units or magnitudes: separate panels/axes with explicit labels; do not place counts, money and percentages on one unlabeled axis. Avoid dual axes that create unsupported associations. Preserve source units and exact computed results; display rounding must be disclosed when material. An absent unit stays absent.
* White background, light grid, restrained lines, clear legend and suitable Chinese font if the report is Chinese. Check actual available fonts instead of assuming a font exists. Size exports for the real report viewport; make titles, ticks, minus signs and data labels readable without zooming. Avoid 3D effects, decorative illustrations and excessive colors.
* Retain source IDs, actual dates/cutoffs, adjustment basis and limitations in the accompanying report. A polished plot cannot imply live data, complete history, causal proof or confirmed business meaning.

## Sources for convention differences

* Shanghai Stock Exchange, 港股通交易风险揭示书必备条款, 2026-11-06, clause 30: Hong Kong up-green/down-red differs from mainland; software preferences may change. https://www.sse.com.cn/lawandrules/sselawsrules2025/global/hkexsc/c/c_20261106_10836951.shtml
* HKEX, Overview of the listed market, FAQ: rising quotation screens are green and falling are red. https://www.hkex.com.hk/Global/Exchange/FAQ/Getting-Started/Overview-of-the-listed-market?sc_lang=en

These sources support market convention differences. They do not mandate this project's exact hex values or authorize financial data access. The already delivered guide is sufficient for styling; no extra browsing is required solely to apply a palette.
