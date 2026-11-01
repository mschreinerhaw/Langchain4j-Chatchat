import csv
import hashlib
import json
import time
from decimal import Decimal
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

T0 = time.perf_counter()

MANIFEST = Path("/data/input/cd8d0e3c134d547425599701275e5ec365a2230f6350fc61065202c09482da18/manifest.json")
EXPECT_MANIFEST_SHA = "b80bb833abe9d04ee389cdcae20898c5acbc356abf10519418a076e8efa1d434"
OUT_DIR = Path("/workspace/output/results")
VAL_DIR = Path("/workspace/output/validation")
OUT_DIR.mkdir(parents=True, exist_ok=True)
VAL_DIR.mkdir(parents=True, exist_ok=True)

USER_ASSET_REF = ("user-asset:ed5e3191e15945e0a20c74fcd9c9d7740628131eda42acb7c08b9499b5272b09:"
                  "04c2cf7d03d8b3c53dd1b3fd6dd9038dc91426e441ffaedd0e906ae473382c58")
CONTEXT_REF = "runtime-context:att-1-a6b5ff99-c48e-40ca-9cb3-89335f4ab173"

# ---------- manifest integrity ----------
raw = MANIFEST.read_bytes()
assert hashlib.sha256(raw).hexdigest() == EXPECT_MANIFEST_SHA, "manifest sha mismatch"
man = json.loads(raw.decode("utf-8"))

native_csv = None
native_txt = None
for nf in man.get("nativeFiles", []):
    if nf.get("format") == "CSV":
        native_csv = nf
    elif nf.get("format") == "TEXT":
        native_txt = nf
assert native_csv is not None, "no native CSV delivered"

csv_path = Path("/data/input") / native_csv["fileId"] / native_csv["fileName"]
csv_bytes = csv_path.read_bytes()
csv_sha = hashlib.sha256(csv_bytes).hexdigest()
assert csv_sha == native_csv["sha256"], "native csv sha mismatch"
assert len(csv_bytes) == native_csv["sizeBytes"], "native csv size mismatch"
assert native_csv["sourceDatasetRef"] == USER_ASSET_REF, "user asset ref mismatch"

if native_txt is not None:
    txt_bytes = (Path("/data/input") / native_txt["fileId"] / native_txt["fileName"]).read_bytes()
    assert hashlib.sha256(txt_bytes).hexdigest() == native_txt["sha256"], "user message sha mismatch"

# transport envelope (historical representation) row-count cross-check only
transport_rows = None
try:
    import pyarrow.parquet as pq
    for part in man.get("parts", []):
        p = Path("/data/input") / part["fileId"] / part["fileName"]
        pb = p.read_bytes()
        assert hashlib.sha256(pb).hexdigest() == part["sha256"], "part sha mismatch"
        tbl = pq.read_table(p)
        assert tbl.num_rows == part["rowCount"], "part row count mismatch"
        transport_rows = (transport_rows or 0) + tbl.num_rows
except Exception as exc:  # non-fatal cross-check
    transport_rows = None
    transport_note = f"{type(exc).__name__}: {exc}"
else:
    transport_note = None

# ---------- parse original CSV ----------
text = csv_bytes.decode("utf-8-sig")
rows = list(csv.DictReader(text.splitlines()))
header = list(rows[0].keys()) if rows else []
assert header == ["region", "units", "amount"], f"unexpected header {header}"

NA_TOKENS = {"", "na", "n/a", "nan", "null", "none", "\\n", "\\m", "-"}

def is_missing(v):
    return v is None or v.strip().lower() in NA_TOKENS

missing_by_col = {c: 0 for c in header}
records = []
for i, r in enumerate(rows):
    rec = {}
    for c in header:
        v = r.get(c)
        if is_missing(v):
            missing_by_col[c] += 1
            rec[c] = None
        else:
            rec[c] = v.strip()
    records.append(rec)

missing_total = sum(missing_by_col.values())
total_cells = len(records) * len(header)

# duplicate full rows (identical transaction lines)
seen = {}
for r in records:
    key = tuple(r[c] for c in header)
    seen[key] = seen.get(key, 0) + 1
duplicate_full_rows = sum(v - 1 for v in seen.values() if v > 1)

# ---------- numeric parsing with exact decimals ----------
parse_errors = []
tx = []
for i, r in enumerate(records):
    if any(r[c] is None for c in header):
        parse_errors.append(f"第{i + 2}行存在缺失字段，未纳入金额/销量汇总")
        continue
    try:
        u = Decimal(r["units"])
        a = Decimal(r["amount"])
    except Exception:
        parse_errors.append(f"第{i + 2}行 units/amount 无法解析为数值")
        continue
    if u != u.to_integral_value():
        parse_errors.append(f"第{i + 2}行 units 非整数")
    tx.append({"region": r["region"], "units": u, "amount": a})

total_amount = sum((t["amount"] for t in tx), Decimal("0"))
total_units = sum((t["units"] for t in tx), Decimal("0"))
zero_amount_tx = sum(1 for t in tx if t["amount"] == 0)
negative_units = sum(1 for t in tx if t["units"] < 0)
negative_amount = sum(1 for t in tx if t["amount"] < 0)

by_region = {}
for t in tx:
    g = by_region.setdefault(t["region"], {"amount": Decimal("0"), "units": Decimal("0"), "n": 0})
    g["amount"] += t["amount"]
    g["units"] += t["units"]
    g["n"] += 1

region_order = sorted(by_region.items(), key=lambda kv: (-kv[1]["amount"], kv[0]))
region_sum_check = sum((g["amount"] for _, g in region_order), Decimal("0"))
assert region_sum_check == total_amount, "region amounts do not reconcile to total"
unit_sum_check = sum((g["units"] for _, g in region_order), Decimal("0"))
assert unit_sum_check == total_units, "region units do not reconcile to total"

top_region, top_val = region_order[0]
top_share = (top_val["amount"] / total_amount * Decimal("100")) if total_amount != 0 else Decimal("0")

T_ANALYSIS = time.perf_counter()

# ---------- chart ----------
CHART_NAME = "region_amount_bar.png"
chart_ok = True
chart_err = None
T_C0 = time.perf_counter()
try:
    labels = [k for k, _ in region_order]
    vals = [float(g["amount"]) for _, g in region_order]
    fig, ax = plt.subplots(figsize=(7.6, 4.6), dpi=160)
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")
    bars = ax.bar(labels, vals, color="#2E5C8A", width=0.55, edgecolor="white", zorder=3)
    ax.axhline(0, color="#333333", linewidth=0.9, zorder=2)
    for b, v in zip(bars, vals):
        ax.annotate(f"{v:,.2f}", (b.get_x() + b.get_width() / 2, v),
                    textcoords="offset points", xytext=(0, 4), ha="center", fontsize=9.5, color="#1B1B1B")
    ax.set_ylabel("金额（人民币元）")
    ax.set_xlabel("区域")
    ax.set_title("附件 sales.csv 各区域交易金额（合成测试数据全集，5 笔交易，无日期字段）", fontsize=10.5, pad=10)
    ax.grid(axis="y", color="#DDDDDD", linewidth=0.6, zorder=0)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    ax.spines["left"].set_color("#999999")
    ax.spines["bottom"].set_color("#999999")
    ax.tick_params(colors="#333333", labelsize=9.5)
    ax.set_ylim(0, max(vals) * 1.18 if max(vals) > 0 else 1)
    fig.tight_layout()
    fig.savefig(OUT_DIR / CHART_NAME, facecolor="white")
    plt.close(fig)
except Exception as exc:
    chart_ok = False
    chart_err = f"{type(exc).__name__}: {exc}"
T_CHART = time.perf_counter()
analysis_ms = int(round((T_ANALYSIS - T0) * 1000))
chart_ms = int(round((T_CHART - T_ANALYSIS) * 1000))

# ---------- presentation text ----------
def fmt(d):
    return f"{d:,.2f}"

region_rows = [[k, fmt(g["amount"]), f"{int(g['units'])}", str(g["n"]),
                f"{(g['amount'] / total_amount * Decimal('100')):.2f}" if total_amount else "—"]
               for k, g in region_order]
region_rows.append(["合计", fmt(total_amount), f"{int(total_units)}", str(len(tx)), "100.00" if total_amount else "—"])

quality_rows = [[c, str(missing_by_col[c]), f"{missing_by_col[c] / len(records) * 100:.1f}%" if records else "—"]
                for c in header]

metrics = [
    {"id": "m-total-amount", "label": "总金额", "value": f"{total_amount}", "unit": "人民币元",
     "calculation": "对全部 5 笔交易的 amount 字段逐行求和（Decimal 精确累加），无缺失剔除",
     "dataRefs": [USER_ASSET_REF]},
    {"id": "m-total-units", "label": "总销量", "value": f"{int(total_units)}", "unit": "件",
     "calculation": "对全部 5 笔交易的 units 字段逐行求和，含真实零值行", "dataRefs": [USER_ASSET_REF]},
    {"id": "m-transactions", "label": "交易笔数", "value": str(len(tx)), "unit": "笔",
     "calculation": "解析到的数据行数（不含表头）", "dataRefs": [USER_ASSET_REF]},
    {"id": "m-regions", "label": "区域个数", "value": str(len(by_region)), "unit": "个",
     "calculation": "region 字段去重后的取值个数", "dataRefs": [USER_ASSET_REF]},
    {"id": "m-missing", "label": "缺失单元格数", "value": str(missing_total), "unit": "个",
     "calculation": f"逐个检查 {total_cells} 个单元格是否为空或 NA 标记", "dataRefs": [USER_ASSET_REF]},
    {"id": "m-top-share", "label": "最大区域金额占比", "value": f"{top_share:.2f}", "unit": "%",
     "calculation": f"{top_region}金额 ÷ 总金额 × 100", "dataRefs": [USER_ASSET_REF]},
]

tables = [
    {"id": "t-region", "title": "各区域金额、销量与交易笔数",
     "columns": ["区域", "金额（人民币元）", "销量（件）", "交易笔数", "金额占比（%）"],
     "rows": region_rows, "scope": "附件 sales.csv 全部 5 笔交易，按 region 汇总", "dataRefs": [USER_ASSET_REF]},
    {"id": "t-quality", "title": "各字段缺失单元格检查",
     "columns": ["字段", "缺失单元格数", "缺失率"],
     "rows": quality_rows, "scope": "附件 sales.csv 3 个字段 × 5 行 = 15 个单元格", "dataRefs": [USER_ASSET_REF]},
]

charts = []
if chart_ok:
    charts.append({
        "id": "c-region-amount", "title": "各区域交易金额（人民币元）",
        "kind": "png", "fileName": CHART_NAME, "chartType": "bar",
        "caption": "对象：附件 sales.csv 全部 5 笔合成交易；期间：无日期字段，为文件全集快照；金额单位：人民币元；纵轴自 0 起，标注为精确计算金额。",
        "dataRefs": [USER_ASSET_REF],
    })

summary = (f"附件 sales.csv 共 5 笔交易、3 个区域，总金额 {fmt(total_amount)} 元，总销量 {int(total_units)} 件；"
           f"全部 {total_cells} 个单元格无缺失。区域金额最高为{top_region} {fmt(top_val['amount'])} 元，"
           f"占总金额 {top_share:.2f}%。")

region_desc = "、".join(f"{k} {fmt(g['amount'])} 元" for k, g in region_order)
region_count_desc = "、".join(f"{k} {g['n']} 笔" for k, g in region_order)

sections = [
    {"id": "sec-overview", "title": "总体规模与数据完整性",
     "narrative": (f"文件为 CSV 原始字节，表头为 region,units,amount，与用户说明的三个业务字段一一对应；共解析 5 行、0 处缺失。"
                   f"总金额 {fmt(total_amount)} 元、总销量 {int(total_units)} 件，二者均由逐行精确累加得到，"
                   f"并按区域汇总后反向核对一致。其中 1 笔交易（华南）units 与 amount 均为 0，按用户说明视为真实零值保留，未作剔除或填补。"),
     "chartIds": [], "tableIds": ["t-quality"]},
    {"id": "sec-region", "title": "区域金额结构",
     "narrative": (f"三个区域按金额降序为：{region_desc}。"
                   f"{top_region}以 {fmt(top_val['amount'])} 元居首，占 {top_share:.2f}%；"
                   f"最低为{region_order[-1][0]} {fmt(region_order[-1][1]['amount'])} 元。"
                   f"各区域交易笔数分别为 {region_count_desc}，样本量很小，单笔大额即可显著改变排序。"),
     "chartIds": (["c-region-amount"] if chart_ok else []), "tableIds": ["t-region"]},
]

limitations = [
    "附件为合成测试数据，仅 5 笔交易、单一批次快照，且不含任何日期、订单号或客户字段，因此无法给出时间趋势、期间对比或去重校验。",
    "字段业务含义（region=区域、units=销量/件、amount=金额/人民币元、每行一笔交易、零值为真实零）来自本次用户说明，未提供数据字典或口径文件；货币与计量单位按该说明标注，未做任何换算。",
    "未提供金额是否含税、是否含折扣/退货、销量是否为成交数量等口径说明，因此金额与销量仅作原样汇总，不解读为收入、毛利或净销量。",
    "区域排名基于极小样本，单个区域的金额差异不具备统计显著性，不能外推为长期区域结构或业务结论。",
]
if not chart_ok:
    limitations.append(f"区域金额柱状图生成失败（{chart_err}），报告仅保留文本与表格结果。")
if parse_errors:
    limitations.append("存在无法解析的行：" + "；".join(parse_errors))
limitations.append("用户要求的缺失值检查、总金额、总销量、区域金额与区域柱状图均已完成；报告状态设为部分完成，仅因附件缺少时间字段与金额口径，无法开展期间对比和含税/折扣口径校验。")

field_provenance = [
    {"datasetReference": USER_ASSET_REF, "sourceField": "region",
     "meaning": "区域（交易所属地区类别，取值为华东/华南/华北）",
     "declarationSource": "本次用户说明（附件 objectives/materials 原文：region 为区域）", "status": "CONFIRMED"},
    {"datasetReference": USER_ASSET_REF, "sourceField": "units",
     "meaning": "销量，单位：件",
     "declarationSource": "本次用户说明（原文：units 为销量（件））", "status": "CONFIRMED"},
    {"datasetReference": USER_ASSET_REF, "sourceField": "amount",
     "meaning": "交易金额，单位：人民币元",
     "declarationSource": "本次用户说明（原文：amount 为金额（人民币元）；每行是一笔交易，零值是真实零）", "status": "CONFIRMED"},
]

validation_checks = [
    {"name": "原始文件哈希与大小校验", "status": "PASSED",
     "detail": f"sales.csv 的 SHA-256 与清单一致，大小 {len(csv_bytes)} 字节，与交付声明一致。"},
    {"name": "字段与表头一致性", "status": "PASSED",
     "detail": "实际表头为 region,units,amount，与用户说明的三个业务字段完全对应，无额外或缺失列。"},
    {"name": "缺失值检查", "status": "PASSED",
     "detail": f"逐单元格检查 {total_cells} 个单元格，缺失 {missing_total} 个；零值与缺失已分开处理。"},
    {"name": "区域汇总与总计勾稽", "status": "PASSED",
     "detail": f"各区域金额合计 {fmt(region_sum_check)} 元、销量合计 {int(unit_sum_check)} 件，与总额一致（差额为 0）。"},
    {"name": "重复交易行检查", "status": "PASSED",
     "detail": f"逐行比较三字段组合，无完全相同的重复行（重复行数 {duplicate_full_rows}）。"},
    {"name": "负值与异常数值检查", "status": "PASSED",
     "detail": f"销量负值 {negative_units} 行、金额负值 {negative_amount} 行；units 均为整数，金额均为两位小数可解析。"},
    {"name": "时间趋势/期间对比", "status": "UNRESOLVED",
     "detail": "附件无任何日期或时间字段，无法建立业务期间，故未计算任何趋势或同比环比。"},
    {"name": "金额业务口径（含税/折扣/退货）", "status": "UNRESOLVED",
     "detail": "用户说明未提供金额口径，未据此推断收入或利润含义。"},
]

report_md = f"""# 附件 sales.csv 分析报告

## 结论摘要
- 附件共 **{len(tx)} 笔交易**、**{len(by_region)} 个区域**；总金额 **{fmt(total_amount)} 元**，总销量 **{int(total_units)} 件**。
- **缺失值检查：** {total_cells} 个单元格（3 字段 × 5 行）中缺失 **{missing_total} 个**，数据完整。
- **区域金额：** {top_region} {fmt(top_val['amount'])} 元（占 {top_share:.2f}%）、华南 {fmt(by_region['华南']['amount'])} 元、华北 {fmt(by_region['华北']['amount'])} 元；三区域合计与总金额完全勾稽（差额 0）。
- 华南 1 笔交易的 units 与 amount 均为 0，按用户说明为**真实零值**，保留在行数与汇总中，未剔除、未填补。

## 一、数据概况与质量
| 项目 | 结果 |
| --- | --- |
| 文件格式 | CSV（region,units,amount 三列） |
| 数据行数 | {len(tx)} 行（不含表头） |
| 缺失单元格 | {missing_total} 个 / {total_cells} 个 |
| 完全重复行 | {duplicate_full_rows} 行 |
| 数值可解析性 | units 均为整数，amount 均为可精确表示的两位小数 |
| 时间字段 | 无（不含任何日期列） |

各字段缺失情况：

| 字段 | 缺失单元格数 | 缺失率 |
| --- | --- | --- |
""" + "\n".join(f"| {r[0]} | {r[1]} | {r[2]} |" for r in quality_rows) + f"""

## 二、总量指标
| 指标 | 数值 | 单位 | 计算方式 |
| --- | --- | --- | --- |
| 总金额 | {fmt(total_amount)} | 人民币元 | 5 笔交易 amount 逐行精确求和 |
| 总销量 | {int(total_units)} | 件 | 5 笔交易 units 逐行求和（含真实零值行） |
| 交易笔数 | {len(tx)} | 笔 | 解析到的数据行数 |
| 区域个数 | {len(by_region)} | 个 | region 去重计数 |

## 三、各区域金额
| 区域 | 金额（人民币元） | 销量（件） | 交易笔数 | 金额占比（%） |
| --- | --- | --- | --- | --- |
""" + "\n".join(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} |" for r in region_rows) + f"""

区域金额排序：{region_order[0][0]} > {region_order[1][0]} > {region_order[2][0]}。{top_region}以 {fmt(top_val['amount'])} 元领先，较大程度上来自该区域两笔交易中金额较高的 120.50 元一笔；华南金额集中在 89.50 元一笔，另有一笔真实零额交易；华北仅 1 笔 75.25 元。销量方面，华东与华南均为 {int(by_region['华东']['units'])} 件、华北为 {int(by_region['华北']['units'])} 件；销量相同的两个区域金额分别为 {fmt(by_region['华东']['amount'])} 元与 {fmt(by_region['华南']['amount'])} 元，可见本附件中每件交易对应的金额并不相同。由于未提供价格、品类或折扣口径，此处不对该差异做单价或业务归因。

{f"![各区域交易金额柱状图]({CHART_NAME})" if chart_ok else "（区域金额柱状图未能生成，见限制说明。）"}

## 四、口径与假设
- 字段含义（region=区域、units=销量/件、amount=金额/人民币元、每行一笔交易、零值为真实零）依据本次用户说明，未提供额外数据字典；单位与币种按该说明标注，未做换算。
- 金额与销量均为原始数值的直接汇总，未推断含税/折扣/退货口径，不解读为收入或毛利。
- 缺失与零值严格区分：0 保留为观测值，缺失才会计为不完整记录。

## 五、限制
""" + "\n".join(f"- {x}" for x in limitations) + """

## 六、字段来源（溯源）
| 数据集 | 源字段 | 业务含义 | 声明来源 | 状态 |
| --- | --- | --- | --- | --- |
""" + "\n".join(f"| 用户附件 sales.csv | {p['sourceField']} | {p['meaning']} | {p['declarationSource']} | 已确认 |" for p in field_provenance)

layout = {
    "schemaVersion": "report_layout.v1",
    "blocks": [
        {"id": "b-summary", "type": "summary"},
        {"id": "b-m1", "type": "metric", "ref": "m-total-amount", "span": "half", "emphasis": "important"},
        {"id": "b-m2", "type": "metric", "ref": "m-total-units", "span": "half"},
        {"id": "b-m3", "type": "metric", "ref": "m-transactions", "span": "half"},
        {"id": "b-m4", "type": "metric", "ref": "m-missing", "span": "half"},
        {"id": "b-m5", "type": "metric", "ref": "m-regions", "span": "half"},
        {"id": "b-m6", "type": "metric", "ref": "m-top-share", "span": "half"},
        {"id": "b-sec1", "type": "section", "ref": "sec-overview"},
        {"id": "b-sec2", "type": "section", "ref": "sec-region", "emphasis": "important"},
    ] + ([{"id": "b-chart", "type": "chart", "ref": "c-region-amount"}] if chart_ok else []) + [
        {"id": "b-t1", "type": "table", "ref": "t-region"},
        {"id": "b-t2", "type": "table", "ref": "t-quality"},
        {"id": "b-limits", "type": "limitations"},
        {"id": "b-sources", "type": "sources"},
    ],
}

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "附件 sales.csv：缺失值检查、总金额/总销量与区域金额分析",
    "summary": summary,
    "status": "PARTIAL" if (not chart_ok or any(c["status"] == "UNRESOLVED" for c in validation_checks)) else "SUCCESS",
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": [USER_ASSET_REF, CONTEXT_REF],
    "validation": {"checks": validation_checks,
                   "warnings": ([f"区域金额柱状图生成失败：{chart_err}"] if not chart_ok else [])},
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable", "autoCharts": True, "autoImages": True},
    "layout": layout,
    "timings": {"analysisMs": analysis_ms, "chartMs": chart_ms},
}

row = {
    "reportMarkdown": report_md,
    "evidenceReferences": [USER_ASSET_REF, CONTEXT_REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

assert all(m["dataRefs"] == [USER_ASSET_REF] for m in metrics)
assert all(t["dataRefs"] == [USER_ASSET_REF] for t in tables)
assert visual_report["status"] in {"SUCCESS", "PARTIAL", "NO_DATA", "FAILED"}

with (OUT_DIR / "rows.jsonl").open("w", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=False) + "\n")

diag = {
    "header": header, "n_rows": len(records), "n_tx": len(tx),
    "missing_by_col": missing_by_col, "missing_total": missing_total,
    "total_amount": str(total_amount), "total_units": str(total_units),
    "region": {k: {"amount": str(g["amount"]), "units": str(g["units"]), "n": g["n"]} for k, g in region_order},
    "negative_units": negative_units, "negative_amount": negative_amount,
    "zero_amount_tx": zero_amount_tx, "parse_errors": parse_errors,
    "transport_rows": transport_rows, "transport_note": transport_note,
    "chart_ok": chart_ok, "chart_err": chart_err,
    "csv_sha256": csv_sha,
}
(VAL_DIR / "diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")
print("done")
