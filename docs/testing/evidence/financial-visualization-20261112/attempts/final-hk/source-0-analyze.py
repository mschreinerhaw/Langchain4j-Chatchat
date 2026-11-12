"""Analyze SYNTHETIC-market-visualization.csv: row counts, amount stats, category comparison charts."""

import csv
import json
import os
from decimal import Decimal

# Style constants (financial-visualization SKILL, financial-auto → HKEX → financial-international)
RED = "#C62828"
GREEN = "#16845B"
FLAT = "#64748B"
SERIES = ("#245A81", "#168A91", "#695AA6", "#9B6A27")
TEXT = "#243746"
GRID = "#E2E8F0"
BACKGROUND = "#FFFFFF"

CHANGE_UP = GREEN
CHANGE_DOWN = RED
CHANGE_FLAT = FLAT

# Read the CSV
csv_path = "/workspace/input/data/0/SYNTHETIC-market-visualization.csv"
rows = []
with open(csv_path, newline="") as f:
    reader = csv.DictReader(f)
    for r in reader:
        rows.append(r)

row_count = len(rows)
print(f"Row count (excluding header): {row_count}")

for r in rows:
    r["amount_d"] = Decimal(r["amount"])
    r["change_d"] = Decimal(r["change"])

amounts = [r["amount_d"] for r in rows]
changes = [r["change_d"] for r in rows]
amount_sum = sum(amounts)
amount_mean = amount_sum / len(amounts)
change_sum = sum(changes)

print(f"amount sum: {amount_sum}, mean: {amount_mean}")
print(f"change sum: {change_sum}")
for r in rows:
    print(f"  {r['category']}: amount={r['amount_d']}, change={r['change_d']}, market={r['market']}")


# Generate charts with matplotlib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

categories = [r["category"] for r in rows]
amount_vals = [float(r["amount_d"]) for r in rows]
change_vals = [float(r["change_d"]) for r in rows]

# Chart 1: Amount by Category
fig1, ax1 = plt.subplots(figsize=(6, 4))
colors_amount = [SERIES[0], SERIES[1], SERIES[2]]
bars1 = ax1.bar(categories, amount_vals, color=colors_amount, edgecolor=TEXT, width=0.5)
for bar, val in zip(bars1, amount_vals):
    ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height()+2,
             f"{val:.0f}", ha="center", va="bottom", fontsize=11, color=TEXT, fontweight="bold")
ax1.set_title("Amount by Category", fontsize=13, color=TEXT, pad=12)
ax1.set_xlabel("Category", fontsize=11, color=TEXT)
ax1.set_ylabel("Amount (unit not declared)", fontsize=11, color=TEXT)
ax1.tick_params(colors=TEXT)
ax1.grid(axis="y", color=GRID, linestyle="-", linewidth=0.5)
ax1.set_facecolor(BACKGROUND)
fig1.patch.set_facecolor(BACKGROUND)
legend1 = ax1.legend([mpatches.Patch(color=c) for c in colors_amount], categories,
                      title="Category", loc="upper right", frameon=True, facecolor=BACKGROUND)
legend1.get_title().set_color(TEXT)
for t in legend1.get_texts():
    t.set_color(TEXT)
plt.tight_layout()
chart1_path = "/workspace/analyses/synthetic-market-visualization/amount_by_category.png"
fig1.savefig(chart1_path, dpi=150, facecolor=BACKGROUND)
plt.close(fig1)
print(f"Saved: {chart1_path}")

# Chart 2: Change by Category (directional colors, financial-international)
fig2, ax2 = plt.subplots(figsize=(6, 4))
change_colors = []
for v in change_vals:
    if v > 0:
        change_colors.append(CHANGE_UP)
    elif v < 0:
        change_colors.append(CHANGE_DOWN)
    else:
        change_colors.append(CHANGE_FLAT)
bars2 = ax2.bar(categories, change_vals, color=change_colors, edgecolor=TEXT, width=0.5)
for bar, val in zip(bars2, change_vals):
    y_pos = bar.get_height() + 0.1 if val >= 0 else bar.get_height() - 0.15
    va = "bottom" if val >= 0 else "top"
    label = f"+{val:.0f}" if val > 0 else f"{val:.0f}"
    ax2.text(bar.get_x()+bar.get_width()/2, y_pos, label, ha="center", va=va,
             fontsize=11, color=TEXT, fontweight="bold")
ax2.axhline(0, color=TEXT, linestyle="-", linewidth=0.8)
ax2.set_title("Change by Category (HKEX: green=up, red=down, gray=flat)",
              fontsize=12, color=TEXT, pad=12)
ax2.set_xlabel("Category", fontsize=11, color=TEXT)
ax2.set_ylabel("Change (signed, unit not declared)", fontsize=11, color=TEXT)
ax2.tick_params(colors=TEXT)
ax2.grid(axis="y", color=GRID, linestyle="-", linewidth=0.5)
ax2.set_facecolor(BACKGROUND)
fig2.patch.set_facecolor(BACKGROUND)
direction_legend = [
    mpatches.Patch(color=CHANGE_UP, label="Positive (up)"),
    mpatches.Patch(color=CHANGE_DOWN, label="Negative (down)"),
    mpatches.Patch(color=CHANGE_FLAT, label="Zero / Flat")
]
legend2 = ax2.legend(handles=direction_legend, loc="upper right",
                      frameon=True, facecolor=BACKGROUND)
for t in legend2.get_texts():
    t.set_color(TEXT)
plt.tight_layout()
chart2_path = "/workspace/analyses/synthetic-market-visualization/change_by_category.png"
fig2.savefig(chart2_path, dpi=150, facecolor=BACKGROUND)
plt.close(fig2)
print(f"Saved: {chart2_path}")

# Build report markdown
report_lines = [
    "# SYNTHETIC 验收数据分析报告",
    "",
    "## 数据概况",
    "",
    "| 指标 | 值 |",
    "| --- | --- |",
    f"| 数据行数（不含表头） | {row_count} |",
    "| 来源文件 | SYNTHETIC-market-visualization.csv |",
    "| 市场标识 | HKEX（全部行） |",
    "| 数据标记 | SYNTHETIC（验收测试数据） |",
    "",
    "## amount 统计（存量）",
    "",
    "| 指标 | 值 |",
    "| --- | --- |",
    f"| 总和 | {amount_sum} |",
    f"| 均值 | {amount_mean} |",
    f"| 最小值 | {min(amounts)} |",
    f"| 最大值 | {max(amounts)} |",
    "",
    "## change 统计（有符号变动）",
    "",
    "| 指标 | 值 |",
    "| --- | --- |",
    f"| 总和 | {change_sum} |",
    f"| 最小值 | {min(changes)} |",
    f"| 最大值 | {max(changes)} |",
    "",
    "## 各类别对比",
    "",
    "| 类别 | amount（存量） | change（变动） |",
    "| --- | --- | --- |",
    f"| A | {rows[0]['amount_d']} | +{rows[0]['change_d']} |",
    f"| B | {rows[1]['amount_d']} | {rows[1]['change_d']} |",
    f"| C | {rows[2]['amount_d']} | {rows[2]['change_d']} |",
    "",
    "## 量纲说明",
    "",
    "- **amount**：表示各类别现有存量，为正数值。三个类别的存量分别为 120、80、100。",
    "- **change**：表示已提供的有符号变动值，可正、可负、可为零。A 为 +2（正向变动），B 为 -1（负向变动），C 为 0（零变动）。",
    "- 两个量纲分开呈现，**不可混为一谈**。",
    "",
    "## 零变动说明",
    "",
    "类别 **C** 的 change 值为 **0**，表示该类别在观测期间内**无变动**（flat）。在图表中以灰色（#64748B）标识，与正向（绿色）和负向（红色）变动区分。零变动是有意义的观测结果，不是缺失数据。",
    "",
    "## 图表",
    "",
    "### amount 按类别比较",
    "",
    "![Amount by Category](analyses/synthetic-market-visualization/amount_by_category.png)",
    "",
    "上图使用稳定比较色板（蓝色系），展示三类 amount 存量大小。amount 为存量大小的比较，不适用涨跌配色。",
    "",
    "### change 按类别比较",
    "",
    "![Change by Category](analyses/synthetic-market-visualization/change_by_category.png)",
    "",
    "上图使用 financial-international 配色约定（HKEX 市场惯例）：**绿色=正向变动（up）**、**红色=负向变动（down）**、**灰色=零变动（flat）**。C 类别的零变动以灰色柱标识。",
    "",
    "## 局限性",
    "",
    "- 单位未在源数据中声明，未做任何推断。",
    "- 仅 3 行数据，不具备统计显著性。",
    "- change 的参考期间未在数据中提供。",
    "- 数据标记为 SYNTHETIC，仅用于验收测试。",
    "- Internet 不可用，未查询外部数据。",
    "",
]
report = "\n".join(report_lines)

report_path = "/workspace/analyses/synthetic-market-visualization/report.md"
with open(report_path, "w", encoding="utf-8") as f:
    f.write(report)
print(f"Saved: {report_path}")

# Build result JSON
result = {
    "analysisStatus": "COMPLETE",
    "evidenceReferences": [
        "runtime-context:att-1-be019df4-8d24-4c9f-bfe7-c0b4e88304da",
        "user-asset:b8130e44e098ed95cd7b6fb66748859da1f12c8723385efa416d05babbe41ffb:57f5fb972c967c723581af4e7baa493fab4d9cd545ac8e180acffe96a045f472"
    ],
    "fieldProvenance": [
        {
            "datasetReference": "user-asset:b8130e44e098ed95cd7b6fb66748859da1f12c8723385efa416d05babbe41ffb:57f5fb972c967c723581af4e7baa493fab4d9cd545ac8e180acffe96a045f472",
            "sourceField": "category",
            "meaning": "Category label (A, B, C)",
            "declarationSource": "SYNTHETIC-market-visualization.csv column header",
            "status": "CONFIRMED"
        },
        {
            "datasetReference": "user-asset:b8130e44e098ed95cd7b6fb66748859da1f12c8723385efa416d05babbe41ffb:57f5fb972c967c723581af4e7baa493fab4d9cd545ac8e180acffe96a045f472",
            "sourceField": "amount",
            "meaning": "Existing stock/quantity per category; unit not declared",
            "declarationSource": "SYNTHETIC-market-visualization.csv column header",
            "status": "CONFIRMED"
        },
        {
            "datasetReference": "user-asset:b8130e44e098ed95cd7b6fb66748859da1f12c8723385efa416d05babbe41ffb:57f5fb972c967c723581af4e7baa493fab4d9cd545ac8e180acffe96a045f472",
            "sourceField": "change",
            "meaning": "Signed change value per category; unit not declared",
            "declarationSource": "SYNTHETIC-market-visualization.csv column header",
            "status": "CONFIRMED"
        },
        {
            "datasetReference": "user-asset:b8130e44e098ed95cd7b6fb66748859da1f12c8723385efa416d05babbe41ffb:57f5fb972c967c723581af4e7baa493fab4d9cd545ac8e180acffe96a045f472",
            "sourceField": "market",
            "meaning": "Market identifier (HKEX for all rows)",
            "declarationSource": "SYNTHETIC-market-visualization.csv column header",
            "status": "CONFIRMED"
        }
    ],
    "limitations": [
        "Units for amount and change are not declared in source; no inference was made.",
        "Only 3 data rows; statistical significance is not applicable.",
        "No time window or reference period is provided for the change values.",
        "Internet access is unavailable; no external data was consulted.",
        "Data is marked SYNTHETIC; results apply only to the supplied test data."
    ],
    "reportMarkdown": report,
    "visualReport": {
        "charts": [
            {"path": "analyses/synthetic-market-visualization/amount_by_category.png", "title": "Amount by Category"},
            {"path": "analyses/synthetic-market-visualization/change_by_category.png", "title": "Change by Category"}
        ],
        "styleProfile": "financial-auto",
        "directionConvention": "financial-international (HKEX: green=up, red=down, gray=flat)"
    }
}

result_path = "/workspace/analyses/synthetic-market-visualization/result.json"
with open(result_path, "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print(f"Saved: {result_path}")

print("\n=== Analysis complete ===")

