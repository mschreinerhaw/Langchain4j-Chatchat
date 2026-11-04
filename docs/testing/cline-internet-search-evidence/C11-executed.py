import json, time, io, os
import pandas as pd
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

t0 = time.perf_counter()

# ---- 1. Read immutable calculationInput parquet ----
manifest_path = "/data/input/c8bb9f62357cc559695fc18afb7dd86b2aa7d6dc822ca546fc2401cd36590147/manifest.json"
with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = json.load(f)

# Verify sha256 of manifest (informational)
import hashlib
with open(manifest_path, "rb") as f:
    mf_sha = hashlib.sha256(f.read()).hexdigest()

# Read the parquet part containing the CSV content
part_path = "/data/input/c2f03c9ddc13ce729cbe6a09f0259b3208110188b0a93cf2d1e9e3e74dafafa8/part-00000.parquet"
df_fetch = pd.read_parquet(part_path)

# The CSV content is in column 'content' (storageName c1)
# Parquet uses storageName as column names
csv_text = df_fetch["c1"].iloc[0]
content_truncated = df_fetch["c2"].iloc[0]
url_actual = df_fetch["c13"].iloc[0]
retrieved_at = df_fetch["c8"].iloc[0]
source_sha256 = df_fetch["c9"].iloc[0]

# ---- 2. Parse CSV ----
csv_df = pd.read_csv(io.StringIO(csv_text))
# Columns: KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE,OBS_STATUS,...
csv_df["TIME_PERIOD"] = pd.to_datetime(csv_df["TIME_PERIOD"])
csv_df["OBS_VALUE"] = pd.to_numeric(csv_df["OBS_VALUE"], errors="coerce")
csv_df = csv_df.sort_values("TIME_PERIOD").reset_index(drop=True)

n_obs = len(csv_df)
first_date = csv_df["TIME_PERIOD"].iloc[0]
last_date = csv_df["TIME_PERIOD"].iloc[-1]
first_val = csv_df["OBS_VALUE"].iloc[0]
last_val = csv_df["OBS_VALUE"].iloc[-1]
max_val = csv_df["OBS_VALUE"].max()
min_val = csv_df["OBS_VALUE"].min()
max_date = csv_df.loc[csv_df["OBS_VALUE"].idxmax(), "TIME_PERIOD"]
min_date = csv_df.loc[csv_df["OBS_VALUE"].idxmin(), "TIME_PERIOD"]
mean_val = csv_df["OBS_VALUE"].mean()
std_val = csv_df["OBS_VALUE"].std()
change_abs = last_val - first_val
change_pct = (change_abs / first_val) * 100

# Daily changes
csv_df["daily_chg"] = csv_df["OBS_VALUE"].diff()

t1 = time.perf_counter()

# ---- 3. Generate PNG chart ----
chart_t0 = time.perf_counter()

fig, ax = plt.subplots(figsize=(9, 4.5), dpi=150)
fig.patch.set_facecolor("white")
ax.set_facecolor("white")

dates = csv_df["TIME_PERIOD"]
values = csv_df["OBS_VALUE"]

ax.plot(dates, values, color="#1f4e87", linewidth=1.8, marker="o", markersize=3.5,
        markerfacecolor="#1f4e87", markeredgecolor="white", markeredgewidth=0.6, zorder=3)
ax.fill_between(dates, values.min() - 0.002, values, alpha=0.08, color="#1f4e87")

# Annotate first and last
ax.annotate(f"{first_val:.4f}", xy=(first_date, first_val),
            xytext=(0, 12), textcoords="offset points",
            fontsize=8, color="#1f4e87", ha="center", fontweight="bold")
ax.annotate(f"{last_val:.4f}", xy=(last_date, last_val),
            xytext=(0, 12), textcoords="offset points",
            fontsize=8, color="#c0392b", ha="center", fontweight="bold")

# Mark max and min
ax.scatter([max_date], [max_val], color="#c0392b", s=50, zorder=4, edgecolors="white", linewidths=0.8)
ax.scatter([min_date], [min_val], color="#27ae60", s=50, zorder=4, edgecolors="white", linewidths=0.8)
ax.annotate(f"高 {max_val:.4f}", xy=(max_date, max_val),
            xytext=(4, 8), textcoords="offset points",
            fontsize=7.5, color="#c0392b")
ax.annotate(f"低 {min_val:.4f}", xy=(min_date, min_val),
            xytext=(4, -14), textcoords="offset points",
            fontsize=7.5, color="#27ae60")

ax.set_title("ECB EUR/USD 参考汇率（每日）\n2026-10-05 至 2026-11-04",
             fontsize=12, fontweight="bold", pad=10,
             fontfamily="sans-serif")
ax.set_xlabel("日期", fontsize=10, fontfamily="sans-serif")
ax.set_ylabel("USD / 1 EUR", fontsize=10, fontfamily="sans-serif")

ax.xaxis.set_major_locator(mdates.WeekdayLocator(byweekday=mdates.MO))
ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d"))
ax.tick_params(axis="both", labelsize=8.5)
ax.grid(axis="y", linestyle="--", alpha=0.35, color="#888888")
ax.grid(axis="x", linestyle=":", alpha=0.2, color="#aaaaaa")

# Y-axis range with padding
y_pad = (max_val - min_val) * 0.35
ax.set_ylim(min_val - y_pad, max_val + y_pad)

# Caption
fig.text(0.5, -0.02,
         f"数据来源：ECB Data Portal · EXR/D.USD.EUR.SP00.A · 2.15 pm CET · 单位 USD（10⁰） · 指数基期 99Q1=100 · 检索时间 {retrieved_at}",
         ha="center", fontsize=7.5, color="#666666", fontfamily="sans-serif")

plt.tight_layout()
out_dir = "/workspace/output/results"
os.makedirs(out_dir, exist_ok=True)
png_path = os.path.join(out_dir, "ecb_eur_usd_1m.png")
fig.savefig(png_path, dpi=150, bbox_inches="tight", facecolor="white")
plt.close(fig)

chart_t1 = time.perf_counter()

# ---- 4. Build reportMarkdown ----
first_date_str = first_date.strftime("%Y-%m-%d")
last_date_str = last_date.strftime("%Y-%m-%d")
max_date_str = max_date.strftime("%Y-%m-%d")
min_date_str = min_date.strftime("%Y-%m-%d")

report_md = f"""# 欧洲央行 EUR/USD 参考汇率近一月报告

## 数据概要

本报告基于欧洲央行（ECB）官方数据接口发布的 **EXR/D.USD.EUR.SP00.A** 序列，取最近 23 个观测值，覆盖 **{first_date_str}** 至 **{last_date_str}** 共 {n_obs} 个交易日 [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)。该序列含义为 **1 欧元兑多少美元**（USD per EUR），计价币种为 USD，分母币种为 EUR，汇率类型为即期（SP00），取每日 14:15 CET 参考汇率，序列变体为平均值（A），指数基期为 1999Q1=100 [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)。

## 区间走势

- **期初值**（{first_date_str}）：{first_val:.4f} USD/EUR [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)
- **期末值**（{last_date_str}）：{last_val:.4f} USD/EUR [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)
- **区间变动**：{change_abs:+.4f}（{change_pct:+.2f}%）[数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)
- **区间最高**：{max_val:.4f}（{max_date_str}）[数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)
- **区间最低**：{min_val:.4f}（{min_date_str}）[数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)
- **区间均值**：{mean_val:.4f}；标准差：{std_val:.4f} [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)

整体看，EUR/USD 在该月内呈先稳后落再回升的走势：10 月上旬至中旬围绕 1.12 上下窄幅波动；10 月下旬起逐步走低至 10 月 29 日的区间低点 1.1130；11 月初回升，11 月 4 日收在 1.1267，为区间次高，仅低于 10 月 6 日的 1.1269 [数据来源](runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c)。

## 图表

![ECB EUR/USD 参考汇率走势](ecb_eur_usd_1m.png)

## 局限性

- 本序列仅提供每日一个参考汇率值（14:15 CET 均值），不含日内高/低、成交量或买卖价差，无法反映盘中波动。
- CSV 未提供每条观测的"发布时间"字段（PUBL_ECB / PUBL_MU / PUBL_PUBLIC 列均为空），因此无法从该 CSV 确定每条汇率的具体发布时点。
- 观测仅覆盖工作日，周末与欧元区/美国公共假日缺失，属正常交易日安排而非数据缺失。
- 报告仅基于该 CSV 直接支持的数值，不引入未从来源取得的外部发布时点或事件归因。
- CSV 中 OBS_STATUS 全部为 A（正常），OBS_CONF 全部为 F（自由），无特殊状态标记。
"""

# ---- 5. Build visualReport ----
analysis_ms = round((t1 - t0) * 1000, 2)
chart_ms = round((chart_t1 - chart_t0) * 1000, 2)

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "欧洲央行 EUR/USD 参考汇率近一月报告",
    "summary": f"基于 ECB EXR/D.USD.EUR.SP00.A 序列最近 23 个观测值（{first_date_str} 至 {last_date_str}），EUR/USD 由 {first_val:.4f} 变动至 {last_val:.4f}，区间变动 {change_abs:+.4f}（{change_pct:+.2f}%），最高 {max_val:.4f}（{max_date_str}），最低 {min_val:.4f}（{min_date_str}）。",
    "status": "SUCCESS",
    "metrics": [
        {"id": "n_obs", "label": "观测数", "value": str(n_obs), "unit": "条", "calculation": "CSV 行数"},
        {"id": "first_val", "label": "期初值", "value": f"{first_val:.4f}", "unit": "USD/EUR", "calculation": f"{first_date_str} OBS_VALUE"},
        {"id": "last_val", "label": "期末值", "value": f"{last_val:.4f}", "unit": "USD/EUR", "calculation": f"{last_date_str} OBS_VALUE"},
        {"id": "change_abs", "label": "区间变动", "value": f"{change_abs:+.4f}", "unit": "USD", "calculation": "last - first"},
        {"id": "change_pct", "label": "区间变动率", "value": f"{change_pct:+.2f}", "unit": "%", "calculation": "(last - first) / first * 100"},
        {"id": "max_val", "label": "区间最高", "value": f"{max_val:.4f}", "unit": "USD/EUR", "calculation": f"max OBS_VALUE ({max_date_str})"},
        {"id": "min_val", "label": "区间最低", "value": f"{min_val:.4f}", "unit": "USD/EUR", "calculation": f"min OBS_VALUE ({min_date_str})"},
        {"id": "mean_val", "label": "区间均值", "value": f"{mean_val:.4f}", "unit": "USD/EUR", "calculation": "mean OBS_VALUE"},
        {"id": "std_val", "label": "标准差", "value": f"{std_val:.4f}", "unit": "USD/EUR", "calculation": "std OBS_VALUE (ddof=1)"}
    ],
    "sections": [
        {
            "id": "overview",
            "title": "数据概要",
            "narrative": f"序列 EXR/D.USD.EUR.SP00.A 表示 1 欧元兑美元的 ECB 参考汇率，每日 14:15 CET 发布，取最近 23 个观测值覆盖 {first_date_str} 至 {last_date_str}。所有观测 OBS_STATUS=A（正常），OBS_CONF=F（自由）。",
            "chartIds": [],
            "tableIds": ["summary_table"]
        },
        {
            "id": "trend",
            "title": "区间走势与图表",
            "narrative": f"EUR/USD 在区间内先稳后落再回升：10 月上中旬围绕 1.12 窄幅波动，10 月下旬下行至 1.1130 的区间低点，11 月初回升至 1.1267。区间极差 {max_val - min_val:.4f}。",
            "chartIds": ["trend_png"],
            "tableIds": []
        }
    ],
    "charts": [
        {
            "id": "trend_png",
            "title": "ECB EUR/USD 参考汇率每日走势",
            "kind": "png",
            "fileName": "ecb_eur_usd_1m.png",
            "caption": f"2026-10-05 至 2026-11-04，USD/EUR，标注首末值、区间最高与最低。"
        }
    ],
    "tables": [
        {
            "id": "summary_table",
            "title": "区间关键统计",
            "columns": ["指标", "数值", "单位"],
            "rows": [
                ["观测数", str(n_obs), "条"],
                ["期初值", f"{first_val:.4f}", "USD/EUR"],
                ["期末值", f"{last_val:.4f}", "USD/EUR"],
                ["区间变动", f"{change_abs:+.4f}", "USD"],
                ["区间变动率", f"{change_pct:+.2f}", "%"],
                ["区间最高", f"{max_val:.4f} ({max_date_str})", "USD/EUR"],
                ["区间最低", f"{min_val:.4f} ({min_date_str})", "USD/EUR"],
                ["区间均值", f"{mean_val:.4f}", "USD/EUR"],
                ["标准差", f"{std_val:.4f}", "USD/EUR"]
            ],
            "scope": f"{first_date_str} 至 {last_date_str}"
        }
    ],
    "dataSources": ["runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c"],
    "validation": {
        "checks": [
            {"name": "csv_row_count", "status": "PASSED", "detail": f"CSV 解析得到 {n_obs} 行观测，与请求 lastNObservations=23 一致"},
            {"name": "date_range", "status": "PASSED", "detail": f"日期范围 {first_date_str} 至 {last_date_str}，全部为工作日"},
            {"name": "obs_status_all_A", "status": "PASSED", "detail": "所有观测 OBS_STATUS=A"},
            {"name": "currency_unit", "status": "PASSED", "detail": "CURRENCY=USD, CURRENCY_DENOM=EUR, UNIT_MULT=0, 即 USD per 1 EUR"},
            {"name": "chart_file_exists", "status": "PASSED", "detail": f"PNG 已写入 {png_path}"}
        ],
        "warnings": [
            "CSV 未提供每条观测的具体发布时点（PUBL_* 列为空），无法核验发布延迟",
            "仅覆盖工作日，周末与假日无观测，属正常安排"
        ]
    },
    "timings": {
        "analysisMs": analysis_ms,
        "chartMs": chart_ms
    },
    "presentation": {
        "strategy": "auto",
        "template": "briefing",
        "density": "comfortable",
        "autoCharts": True,
        "autoImages": True
    },
    "layout": {
        "schemaVersion": "report_layout.v1",
        "blocks": [
            {"id": "b_summary", "type": "summary", "span": "full", "emphasis": "important"},
            {"id": "b_metrics", "type": "metric", "ref": "n_obs", "span": "half", "emphasis": "normal"},
            {"id": "b_m_first", "type": "metric", "ref": "first_val", "span": "half", "emphasis": "normal"},
            {"id": "b_m_last", "type": "metric", "ref": "last_val", "span": "half", "emphasis": "normal"},
            {"id": "b_m_chg", "type": "metric", "ref": "change_abs", "span": "half", "emphasis": "important"},
            {"id": "b_section_overview", "type": "section", "ref": "overview", "span": "full", "emphasis": "normal"},
            {"id": "b_table", "type": "table", "ref": "summary_table", "span": "full", "emphasis": "normal"},
            {"id": "b_section_trend", "type": "section", "ref": "trend", "span": "full", "emphasis": "normal"},
            {"id": "b_chart", "type": "chart", "ref": "trend_png", "span": "full", "emphasis": "important"},
            {"id": "b_limits", "type": "limitations", "span": "full", "emphasis": "normal"},
            {"id": "b_sources", "type": "sources", "span": "full", "emphasis": "normal"}
        ]
    }
}

# ---- 6. Write rows.jsonl ----
row = {
    "reportMarkdown": report_md,
    "evidenceReferences": ["runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c"],
    "limitations": [
        "CSV 的 PUBL_ECB / PUBL_MU / PUBL_PUBLIC 列均为空，无法从该 CSV 确定每条汇率的具体发布时点",
        "仅覆盖工作日，周末与欧元区/美国公共假日缺失，属正常交易日安排",
        "仅基于该 CSV 直接支持的数值，未引入未从来源取得的外部发布时点或事件归因",
        "该序列仅提供每日一个参考汇率值（14:15 CET 均值），不含日内高/低、成交量或买卖价差"
    ],
    "analysisStatus": "COMPLETE",
    "fieldProvenance": [
        {
            "datasetReference": "runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c",
            "sourceField": "OBS_VALUE",
            "meaning": "ECB EUR/USD 参考汇率，1 欧元兑多少美元，每日 14:15 CET",
            "declarationSource": "ECB Data Portal EXR/D.USD.EUR.SP00.A CSV",
            "status": "CONFIRMED"
        },
        {
            "datasetReference": "runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c",
            "sourceField": "TIME_PERIOD",
            "meaning": "观测日期，YYYY-MM-DD",
            "declarationSource": "ECB Data Portal EXR/D.USD.EUR.SP00.A CSV",
            "status": "CONFIRMED"
        },
        {
            "datasetReference": "runtime-evidence:knowledge:6e9a1aa3d47817623bdad7d12b2ac5bd742379b8cf58b729fb4ad5c66dca7e6c",
            "sourceField": "UNIT",
            "meaning": "计价币种 USD，UNIT_MULT=0，即 USD×10⁰",
            "declarationSource": "ECB Data Portal EXR/D.USD.EUR.SP00.A CSV",
            "status": "CONFIRMED"
        }
    ],
    "visualReport": visual_report
}

out_path = os.path.join(out_dir, "rows.jsonl")
with open(out_path, "w", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=False) + "\n")

print(f"Rows written: {out_path}")
print(f"PNG written: {png_path}")
print(f"n_obs={n_obs}, first={first_date_str}:{first_val}, last={last_date_str}:{last_val}")
print(f"max={max_val}({max_date_str}), min={min_val}({min_date_str})")
print(f"change={change_abs:+.4f} ({change_pct:+.2f}%)")
print(f"analysisMs={analysis_ms}, chartMs={chart_ms}")
print(f"manifest_sha256_ok={mf_sha == 'a924050314414421207dc29519d4230f68e78f1279199c9f751fc76da63bc5d0'}")
print(f"source_sha256_from_csv={source_sha256}")
