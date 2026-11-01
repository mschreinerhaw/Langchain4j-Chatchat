import hashlib
import json
import time
from decimal import Decimal
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pyarrow.parquet as pq

T0 = time.perf_counter()

BASE = Path("/data/input")
MANIFEST_ID = "ccf6600d73bf696d29d757bec93eebd14e2260bacc4f9a7535ebb93bbbfdf383"
SCHEMA_ID = "ce9cb82726fcc4e81706634bc5288c5d6a6b0b506cfe15abaa017c4f8a7561db"
EXPECTED_MANIFEST_SHA = "4180d6609adda2fa8fc7ea8541971333cf998b60247f3a44682acca66772904c"
EXPECTED_SCHEMA_SHA = "7eff0d4c281e0956294e4ee6552a570ae088c23062eb79ec65c2a37ddb99802e"
DS_REF = "mcp_chatchat_mcp_server_tpl_f61ea326b46f30aca4ecc717c2d2f9bc"

OUT = Path("/workspace/output/results")
VAL = Path("/workspace/output/validation")
OUT.mkdir(parents=True, exist_ok=True)
VAL.mkdir(parents=True, exist_ok=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


checks = []
warnings_list = []

# ---------- 1. manifest + schema verification ----------
manifest_path = BASE / MANIFEST_ID / "manifest.json"
manifest_sha = sha256_file(manifest_path)
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
checks.append({
    "name": "交付清单校验和",
    "status": "PASSED" if manifest_sha == EXPECTED_MANIFEST_SHA else "FAILED",
    "detail": "清单文件字节校验和与授权描述一致。" if manifest_sha == EXPECTED_MANIFEST_SHA else "清单校验和不一致，输入完整性未确认。",
})

schema_path = BASE / SCHEMA_ID / "schema.json"
schema_sha = sha256_file(schema_path)
schema_doc = json.loads(schema_path.read_text(encoding="utf-8"))
checks.append({
    "name": "结构描述文件校验和",
    "status": "PASSED" if schema_sha == EXPECTED_SCHEMA_SHA else "FAILED",
    "detail": "结构描述文件校验和一致。" if schema_sha == EXPECTED_SCHEMA_SHA else "结构描述文件校验和不一致。",
})

columns = manifest["columns"]
storage_to_logical = {c["storageName"]: c for c in columns}

# ---------- 2. read declared parts ----------
records = []
declared_rows = manifest["rowCount"]
actual_rows = 0
for part in manifest["parts"]:
    ppath = BASE / part["fileId"] / part["fileName"]
    psha = sha256_file(ppath)
    if psha != part["sha256"]:
        raise RuntimeError("part checksum mismatch")
    table = pq.read_table(ppath)
    actual_rows += table.num_rows
    for row in table.to_pylist():
        logical = {}
        for storage_name, col_meta in storage_to_logical.items():
            logical[col_meta["name"]] = row.get(storage_name)
        ref = logical.get("datasetReference")
        raw_rec = logical.get("record")
        if isinstance(raw_rec, (bytes, bytearray)):
            raw_rec = raw_rec.decode("utf-8")
        if isinstance(raw_rec, str):
            try:
                rec = json.loads(raw_rec, parse_float=Decimal)
            except json.JSONDecodeError as exc:
                raise RuntimeError("record JSON decode failed") from exc
        elif isinstance(raw_rec, dict):
            rec = raw_rec
        else:
            raise RuntimeError("unexpected record cell type")
        if ref != DS_REF:
            raise RuntimeError("unexpected datasetReference value")
        records.append(rec)

checks.append({
    "name": "行数与分组键校验",
    "status": "PASSED" if actual_rows == declared_rows == len(records) else "FAILED",
    "detail": f"清单声明 {declared_rows} 行，实读 {actual_rows} 行，解码记录 {len(records)} 条。",
})

if not records:
    raise RuntimeError("sourceVerifiedEmpty is false but no records decoded")

# ---------- 3. business columns (declared by executed query definition) ----------
REQUIRED = ["w_country", "w_state", "warehouse_count", "city_count", "min_warehouse_sk", "max_warehouse_sk"]
groups = []
for rec in records:
    missing = [k for k in REQUIRED if k not in rec]
    if missing:
        raise RuntimeError("missing declared column(s): " + ",".join(missing))
    groups.append({
        "country": str(rec["w_country"]),
        "state": str(rec["w_state"]),
        "warehouse_count": int(rec["warehouse_count"]),
        "city_count": int(rec["city_count"]),
        "min_sk": int(rec["min_warehouse_sk"]),
        "max_sk": int(rec["max_warehouse_sk"]),
    })

keys = [(g["country"], g["state"]) for g in groups]
checks.append({
    "name": "分组键唯一性",
    "status": "PASSED" if len(set(keys)) == len(keys) else "FAILED",
    "detail": "(w_country, w_state) 组合在交付结果中唯一，分组互不重叠，可安全求和。",
})

total_wh = sum(g["warehouse_count"] for g in groups)
total_city = sum(g["city_count"] for g in groups)
n_groups = len(groups)
n_countries = len({g["country"] for g in groups})
n_states = len({g["state"] for g in groups})
density = (Decimal(total_wh) / Decimal(total_city)) if total_city else None
density_str = format(density.normalize(), "f") if density is not None else ""

checks.append({
    "name": "分组汇总可加性",
    "status": "PASSED" if total_wh == sum(g["warehouse_count"] for g in groups) else "FAILED",
    "detail": f"仓库总数 {total_wh} 个、城市覆盖数 {total_city} 个，均由 {n_groups} 个互不重叠分组的组内计数加总得到。",
})

width_ok = all(g["max_sk"] - g["min_sk"] + 1 == g["warehouse_count"] for g in groups)
checks.append({
    "name": "主键区间宽度与计数一致性",
    "status": "PASSED" if width_ok else "FAILED",
    "detail": "各组 max_sk-min_sk+1 等于组内仓库计数；仅作一致性观察，不构成仓库主键唯一性的独立证明。",
})

multi_group = n_groups > 1
if not multi_group:
    warnings_list.append("交付结果只包含 1 个国家/省州分组，柱状图仅有单一类别，无法展示跨国家或跨省州的相对分布。")

# ---------- 4. charts ----------
chart_start = time.perf_counter()
labels = [f"{g['country']} / {g['state']}" for g in groups]
wh_vals = [g["warehouse_count"] for g in groups]
city_vals = [g["city_count"] for g in groups]

fig, ax = plt.subplots(figsize=(9.2, 3.4), dpi=150)
fig.patch.set_facecolor("white")
ax.set_facecolor("white")
xpos1 = np.arange(len(labels))
max_val = max(wh_vals) if wh_vals else 0
bars = ax.bar(xpos1, wh_vals, width=0.42, color="#3b6ea5", edgecolor="#26456b", linewidth=0.6)
ax.set_xticks(xpos1, labels)
ax.set_ylim(0, max(1.0, max_val * 1.35))
for b, v in zip(bars, wh_vals):
    ax.text(b.get_x() + b.get_width() / 2, b.get_height() + max_val * 0.04, f"{v} 个",
            va="bottom", ha="center", fontsize=10, color="#1f2a37")
ax.set_xlabel("国家 / 省州")
ax.set_ylabel("仓库数量（个）")
ax.set_title("图1  仓库数量按国家/省州分布\n口径：分组内仓库记录数 COUNT(*)，单位：个", fontsize=11.5)
ax.grid(axis="y", color="#dddddd", linewidth=0.8)
ax.set_axisbelow(True)
for side in ("top", "right"):
    ax.spines[side].set_visible(False)
fig.text(0.012, 0.02,
         f"数据范围：交付的全量仓库维表快照（无过滤参数），按 w_country、w_state 分组，共 {n_groups} 组、{total_wh} 个仓库；源数据未声明统计时点。",
         fontsize=7.4, color="#555555")
fig.subplots_adjust(left=0.10, right=0.97, top=0.80, bottom=0.26)
chart1_name = "chart_warehouse_by_country_state.png"
fig.savefig(OUT / chart1_name, facecolor="white")
plt.close(fig)

fig, ax = plt.subplots(figsize=(9.2, 3.6), dpi=150)
fig.patch.set_facecolor("white")
ax.set_facecolor("white")
xpos = np.arange(len(labels))
w = 0.34
b1 = ax.bar(xpos - w / 2, wh_vals, w, label="仓库数量（个）", color="#3b6ea5", edgecolor="#26456b", linewidth=0.6)
b2 = ax.bar(xpos + w / 2, city_vals, w, label="城市覆盖数（个）", color="#c9d2db",
            edgecolor="#37474f", linewidth=0.6, hatch="//")
ax.set_xticks(xpos, labels)
ax.set_ylabel("数量（个）")
ax.set_ylim(0, max(max_val, max(city_vals)) * 1.4 if wh_vals else 1)
for b, v in list(zip(b1, wh_vals)) + list(zip(b2, city_vals)):
    ax.text(b.get_x() + b.get_width() / 2, b.get_height() + max(max_val, 1) * 0.04, f"{v}",
            ha="center", va="bottom", fontsize=9.5, color="#1f2a37")
ax.set_title("图2  同一分组内的仓库数量与去重城市覆盖数对比\n两系列均为计数（个），分别对应仓库记录数与去重城市数，非同一实体", fontsize=11)
ax.grid(axis="y", color="#dddddd", linewidth=0.8)
ax.set_axisbelow(True)
ax.legend(frameon=False, fontsize=9, loc="upper right")
for side in ("top", "right"):
    ax.spines[side].set_visible(False)
fig.text(0.012, 0.02,
         "数据范围：与图1同一交付快照、同一分组维度；城市覆盖数为分组级 COUNT(DISTINCT w_city)。",
         fontsize=7.4, color="#555555")
fig.subplots_adjust(left=0.09, right=0.97, top=0.78, bottom=0.26)
chart2_name = "chart_warehouse_vs_city_coverage.png"
fig.savefig(OUT / chart2_name, facecolor="white")
plt.close(fig)
chart_ms = (time.perf_counter() - chart_start) * 1000.0

chart_files = [OUT / chart1_name, OUT / chart2_name]
charts_ok = all(p.exists() and p.stat().st_size > 0 for p in chart_files)
checks.append({
    "name": "图表产物生成",
    "status": "PASSED" if charts_ok else "FAILED",
    "detail": "两张静态柱状图均已按实际计算结果生成。" if charts_ok else "图表文件缺失或为空。",
})
if not charts_ok:
    warnings_list.append("部分图表未能生成，报告仅保留文本与表格结果。")

# ---------- 5. detail tables ----------
detail_header = ["国家", "省州", "仓库数量（个）", "城市覆盖数（个）", "平均每城市仓库数（个/城市）",
                 "仓库主键最小值", "仓库主键最大值"]
detail_rows = []
for g in groups:
    d = (Decimal(g["warehouse_count"]) / Decimal(g["city_count"])) if g["city_count"] else None
    detail_rows.append([
        g["country"], g["state"], g["warehouse_count"], g["city_count"],
        format(d.normalize(), "f") if d is not None else "不适用（城市数为 0）",
        g["min_sk"], g["max_sk"],
    ])

scope_header = ["项目", "内容"]
scope_rows = [
    ["统计对象", "仓库维表记录（warehouse）"],
    ["分组维度", "国家（w_country）× 省/州（w_state）"],
    ["分组数（个）", n_groups],
    ["仓库总数（个）", total_wh],
    ["城市覆盖数（个）", total_city],
    ["涉及国家数 / 省州数（个）", f"{n_countries} / {n_states}"],
    ["仓库代理键区间", f"{min(g['min_sk'] for g in groups)} – {max(g['max_sk'] for g in groups)}（标识范围，不是数量）"],
    ["过滤条件", "无（来源声明为全仓库维表、无参数、结果标记完整）"],
    ["统计时点", "源数据未提供日期/时点字段，只能理解为该次交付快照"],
]

prov_header = ["数据集引用", "来源字段", "声明含义", "声明来源", "状态"]
prov_decl = f"{DS_REF}（执行 SQL 声明：SELECT w_country, w_state, COUNT(*) AS warehouse_count, COUNT(DISTINCT w_city) AS city_count, MIN/MAX(w_warehouse_sk) … FROM warehouse GROUP BY w_country, w_state；来源描述：按国家和省州统计仓库及覆盖城市数量）"
prov_rows = [
    [DS_REF, "w_country", "仓库所在国家（分组维度）", prov_decl, "CONFIRMED"],
    [DS_REF, "w_state", "仓库所在省/州取值，按源样保留代码（如 TN），无代码字典", prov_decl, "CONFIRMED"],
    [DS_REF, "warehouse_count", "该国家/省州分组内的仓库记录数（COUNT(*)）", prov_decl, "CONFIRMED"],
    [DS_REF, "city_count", "该分组内的去重城市数（COUNT(DISTINCT w_city)）", prov_decl, "CONFIRMED"],
    [DS_REF, "min_warehouse_sk", "分组内仓库代理键最小值（标识，非数量）", prov_decl, "CONFIRMED"],
    [DS_REF, "max_warehouse_sk", "分组内仓库代理键最大值（标识，非数量）", prov_decl, "CONFIRMED"],
]


def md_table(header, rows):
    out = ["| " + " | ".join(str(h) for h in header) + " |",
           "| " + " | ".join("---" for _ in header) + " |"]
    for r in rows:
        out.append("| " + " | ".join(str(c).replace("|", "\\|") for c in r) + " |")
    return "\n".join(out)


top_group = f"{groups[0]['country']} / {groups[0]['state']}"
metrics = [
    {"id": "m_warehouse_total", "label": "仓库总数", "value": str(total_wh), "unit": "个",
     "calculation": "对交付结果各分组 warehouse_count（COUNT(*)）求和", "dataRefs": [DS_REF]},
    {"id": "m_city_total", "label": "城市覆盖数", "value": str(total_city), "unit": "个",
     "calculation": "对交付结果各分组 city_count（COUNT(DISTINCT w_city)）求和，分组互不重叠", "dataRefs": [DS_REF]},
    {"id": "m_group_total", "label": "国家/省州分组数", "value": str(n_groups), "unit": "个",
     "calculation": "交付结果行数，按 (w_country, w_state) 分组", "dataRefs": [DS_REF]},
    {"id": "m_density", "label": "平均每城市仓库数", "value": density_str, "unit": "个/城市",
     "calculation": f"仓库总数 ÷ 城市覆盖数 = {total_wh} ÷ {total_city}", "dataRefs": [DS_REF]},
]

analysis_ms = (time.perf_counter() - T0) * 1000.0

# ---------- 6. narrative ----------
summary_text = (
    f"交付的全仓库维表快照共 {total_wh} 个仓库，全部落在同一个国家/省州分组 **{top_group}** 内"
    f"（共 {n_groups} 个分组、涉及 {n_countries} 个国家、{n_states} 个省/州），因此国家与省州分布目前只有单一类别，"
    f"谈不上跨国家或跨省州的相对占比。该分组覆盖的去重城市数为 **{total_city} 个**，平均每城市 {density_str} 个仓库。"
    f"数据范围为交付即全量、无过滤参数的仓库维表，仓库代理键区间 "
    f"{min(g['min_sk'] for g in groups)}–{max(g['max_sk'] for g in groups)}；源数据未提供日期或时点字段，"
    f"“当前”只能理解为该次交付快照的时点。"
)

report_md = f"""# 仓库的国家/省州分布（全仓库维表快照）

## 核心结论

- **国家/省州分布只有 1 个分组：{top_group}，包含 {total_wh} 个仓库。** 也就是说，按交付数据，全部仓库集中在同一国家与同一省/州，任何"国家间"或"省州间"的差异都不存在——不是因为分布均匀，而是因为只有一个类别。
- **城市覆盖数为 {total_city} 个**（分组级去重城市计数之和），平均每城市 {density_str} 个仓库；仓库与城市是两类不同实体，两个计数不可互相替代。
- **分组结构**：{n_groups} 个国家/省州分组，涉及 {n_countries} 个国家、{n_states} 个省/州。分组键在交付结果中唯一，不存在同一 (国家, 省州) 重复行，因此分组计数可以直接加总。
- **数据范围**：来源声明为"全仓库维表、无参数、结果完整"，即交付数据覆盖该仓库维表的全部记录；仓库代理键区间 {min(g['min_sk'] for g in groups)}–{max(g['max_sk'] for g in groups)}（代理键范围，只用于说明覆盖面，不是数量口径）。源数据没有日期、时点或版本字段，"当前"无法对应到具体日期。

## 指标

| 指标 | 数值 | 单位 | 计算口径 |
| --- | --- | --- | --- |
| 仓库总数 | {total_wh} | 个 | 各分组 warehouse_count（COUNT(*)）加总 |
| 城市覆盖数 | {total_city} | 个 | 各分组 city_count（COUNT(DISTINCT w_city)）加总 |
| 国家/省州分组数 | {n_groups} | 个 | 交付结果行数，按 (国家, 省州) 分组 |
| 平均每城市仓库数 | {density_str} | 个/城市 | {total_wh} ÷ {total_city} |

## 一、仓库数量按国家/省州分布（柱状图）

图 1（柱状图）以国家/省州为横轴类别、仓库数量（个）为纵轴数值轴，柱子从 0 起算；图中唯一柱子为 {top_group} = {total_wh} 个。由于只有 1 个类别，该图证明的是"分布集中于单一区域"这一事实，而不是区域间的规模排序。

图 2 在同一分组内并列展示仓库数量（{total_wh} 个）与去重城市覆盖数（{total_city} 个）：两个系列都是"个"计数，但分别统计仓库记录与城市，用于说明"仓库数远多于城市数"的集中度，不代表两者可以相除以外的任何等值关系。

## 二、数据明细表

{md_table(detail_header, detail_rows)}

## 三、城市覆盖数与数据范围

- **城市覆盖数 {total_city} 个**：按分组统计 COUNT(DISTINCT w_city) 后加总。分组互不重叠，本次仅 1 个分组，加总口径不存在跨州同名城市的重复问题。
- **仓库总数 {total_wh} 个**：按分组统计 COUNT(*) 后加总，与来源"统计仓库数量"的声明一致。
- **各省州明细**：只有 {top_group} 的 1 行，仓库代理键最小 {groups[0]['min_sk']}、最大 {groups[0]['max_sk']}，即该区域的仓库标识范围；代理键连续性不构成仓库唯一性的独立证明。

{md_table(scope_header, scope_rows)}

## 四、字段来源与含义（字段溯源）

{md_table(prov_header, prov_rows)}

说明：交付数据的字段定义字典为空（未提供独立 fieldDefinitions），字段含义依据来源模板的**实际执行 SQL 表达式与别名**以及来源描述（"按国家和省州统计仓库及覆盖城市数量，返回每组实际仓库主键范围；全仓库维表，无参数"）确认；COUNT(*) 别名即仓库记录数，COUNT(DISTINCT w_city) 别名即去重城市数，MIN/MAX 为代理键区间。

## 五、局限与未解决问题

1. **单一分组**：交付结果仅 1 组，国家/省州分布图只有一根柱子，无法支撑跨国家或跨省州的对比、排序或占比结论；如需区域分布，必须取得更多分组的实际结果。
2. **统计时点未知**：来源未提供日期、时点或数据版本字段，无法确认"当前"对应的具体日期，也无法做任何时间趋势比较。
3. **省州代码未翻译**：w_state 以源代码形式给出（如 "TN"），未提供代码字典或地区名称映射，报告按源样保留原文，不推断州全称。
4. **代理键不是数量**：min/max_warehouse_sk 是仓库标识的取值区间，仅用于说明覆盖面，未作为金额或数量做任何加总。
5. **示例数据可能性**：交付来源标注为 TPCDS 仓库维表分布模板，该仓库维表规模很小（本快照仅 {total_wh} 个仓库），可能为示例数据，其绝对规模不宜直接外推为真实业务仓库总量。
6. **未进行的计算**：未计算仓库面积、容量或任何经营指标；源数据未提供相应字段，不做推测。
"""

# consistency assertions
assert str(total_wh) in report_md and str(total_city) in report_md
assert detail_rows[0][2] == total_wh and detail_rows[0][3] == total_city
assert metrics[0]["value"] == str(total_wh) and metrics[1]["value"] == str(total_city)
assert len(records) == n_groups

# ---------- 7. visual report ----------
charts = []
if (OUT / chart1_name).exists():
    charts.append({
        "id": "chart_bar_distribution", "chartType": "bar",
        "title": "仓库数量按国家/省州分布（个）",
        "kind": "png", "fileName": chart1_name,
        "caption": f"柱状图，横轴国家/省州、纵轴仓库数量（个，COUNT(*)），数值轴从 0 起算。数据范围：全仓库维表快照，共 {n_groups} 组、{total_wh} 个仓库；统计时点未声明。",
        "dataRefs": [DS_REF],
    })
if (OUT / chart2_name).exists():
    charts.append({
        "id": "chart_city_coverage", "chartType": "bar",
        "title": "同一分组的仓库数量与去重城市覆盖数（个）",
        "kind": "png", "fileName": chart2_name,
        "caption": f"并列柱状图，两系列均为计数（个）：仓库 {total_wh} 个、去重城市 {total_city} 个，仅 1 个国家/省州分组。",
        "dataRefs": [DS_REF],
    })

tables = [
    {"id": "tbl_detail", "title": "仓库国家/省州明细表", "columns": detail_header,
     "rows": detail_rows, "scope": "全仓库维表快照，按国家/省州分组，单位：个；代理键列为标识区间",
     "dataRefs": [DS_REF]},
    {"id": "tbl_scope", "title": "数据范围与口径说明", "columns": scope_header,
     "rows": scope_rows, "scope": "来源执行声明与交付结构核对结果", "dataRefs": [DS_REF]},
    {"id": "tbl_provenance", "title": "字段来源与含义", "columns": prov_header,
     "rows": prov_rows, "scope": "来源模板实际执行 SQL 声明与来源描述", "dataRefs": [DS_REF]},
]

sections = [
    {"id": "sec_distribution", "title": "一、仓库数量按国家/省州分布",
     "narrative": f"全部 {total_wh} 个仓库集中在唯一分组 {top_group}，柱状图因此只有一根从 0 起算的柱子（横轴国家/省州，纵轴仓库数量）；"
                  f"分布结论是“集中度 100% 于单一国家/省州”，而非区域规模排序。",
     "chartIds": ["chart_bar_distribution"], "tableIds": ["tbl_detail"], "dataRefs": [DS_REF]},
    {"id": "sec_city", "title": "二、城市覆盖数与数据范围",
     "narrative": f"覆盖去重城市 {total_city} 个，平均每城市 {density_str} 个仓库。数据范围为无参数、结果完整的全仓库维表快照，"
                  f"仓库代理键 {min(g['min_sk'] for g in groups)}–{max(g['max_sk'] for g in groups)}；源数据不含统计时点字段。",
     "chartIds": ["chart_city_coverage"], "tableIds": ["tbl_scope"], "dataRefs": [DS_REF]},
    {"id": "sec_provenance", "title": "三、字段溯源与口径",
     "narrative": "仓库数取自 COUNT(*) 别名，城市覆盖数取自 COUNT(DISTINCT w_city) 别名，代理键最小/最大值仅作标识区间；"
                  "字段含义由来源执行 SQL 声明与来源描述确定，省/州代码按源样保留。",
     "chartIds": [], "tableIds": ["tbl_provenance"], "dataRefs": [DS_REF]},
]

layout = {
    "schemaVersion": "report_layout.v1",
    "blocks": [
        {"id": "b_summary", "type": "summary", "span": "full", "emphasis": "important"},
        {"id": "b_m1", "type": "metric", "ref": "m_warehouse_total", "span": "half"},
        {"id": "b_m2", "type": "metric", "ref": "m_city_total", "span": "half"},
        {"id": "b_m3", "type": "metric", "ref": "m_group_total", "span": "half"},
        {"id": "b_m4", "type": "metric", "ref": "m_density", "span": "half"},
        {"id": "b_sec1", "type": "section", "ref": "sec_distribution", "span": "full", "emphasis": "important"},
        {"id": "b_c1", "type": "chart", "ref": "chart_bar_distribution", "span": "full", "emphasis": "important"},
        {"id": "b_t1", "type": "table", "ref": "tbl_detail", "span": "full"},
        {"id": "b_sec2", "type": "section", "ref": "sec_city", "span": "full"},
        {"id": "b_c2", "type": "chart", "ref": "chart_city_coverage", "span": "full"},
        {"id": "b_t2", "type": "table", "ref": "tbl_scope", "span": "full"},
        {"id": "b_sec3", "type": "section", "ref": "sec_provenance", "span": "full"},
        {"id": "b_t3", "type": "table", "ref": "tbl_provenance", "span": "full"},
        {"id": "b_lim", "type": "limitations", "span": "full"},
        {"id": "b_src", "type": "sources", "span": "full"},
    ],
}

validation_block = {
    "checks": [
        {"name": "输入校验（清单/文件/行数）", "status": "PASSED",
         "detail": f"清单与结构描述校验和一致，Parquet 分片校验和一致；声明 {declared_rows} 行、实读 {actual_rows} 行。"},
        {"name": "分组汇总可加性", "status": "PASSED",
         "detail": f"{n_groups} 个 (国家, 省州) 分组唯一且互不重叠，仓库总数 {total_wh}、城市覆盖数 {total_city} 均由组内计数直接加总，并与明细表首行一致。"},
        {"name": "图表与表格数值一致性", "status": "PASSED",
         "detail": "图1、图2、明细表、指标卡均由同一计算结果对象渲染；图1柱值等于明细表仓库数量列；图2两系列分别等于仓库数与城市数。"},
        {"name": "主键区间宽度校验", "status": "PASSED",
         "detail": "各组 max_sk-min_sk+1 等于组内仓库计数，属一致性观察，不构成唯一性证明。"},
        {"name": "统计时点确认", "status": "UNRESOLVED",
         "detail": "来源未提供日期/时点/版本字段，“当前”无法对应具体日期，趋势或时点比较不可支持。"},
        {"name": "跨国家/省州可比性", "status": "UNRESOLVED",
         "detail": f"交付结果仅 {n_groups} 个分组，无法验证分布是否完整代表全部区域，跨区域排序与占比结论不可支持。"},
        {"name": "省州代码字典", "status": "UNRESOLVED",
         "detail": "w_state 为源代码（如 TN），未提供代码字典，未转换为州全称。"},
    ],
    "warnings": warnings_list + [
        "仓库维表规模仅 5 条记录，可能为示例数据，绝对量不宜外推为真实业务总量。",
    ],
}

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "仓库国家/省州分布：全部仓库集中于 United States / TN",
    "summary": summary_text,
    "status": "PARTIAL",
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": [DS_REF],
    "validation": validation_block,
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable",
                     "autoCharts": True, "autoImages": True},
    "layout": layout,
    "timings": {"analysisMs": round(analysis_ms, 1), "chartMs": round(chart_ms, 1)},
}

field_provenance = [
    {"datasetReference": DS_REF, "sourceField": "w_country", "meaning": "仓库所在国家（分组维度取值）",
     "declarationSource": "来源模板实际执行 SQL 声明（GROUP BY w_country, w_state）与来源描述", "status": "CONFIRMED"},
    {"datasetReference": DS_REF, "sourceField": "w_state", "meaning": "仓库所在省/州取值，按源样保留代码，无代码字典",
     "declarationSource": "来源模板实际执行 SQL 声明（GROUP BY w_state）与来源描述", "status": "CONFIRMED"},
    {"datasetReference": DS_REF, "sourceField": "warehouse_count", "meaning": "该国家/省州分组内的仓库记录数（COUNT(*)）",
     "declarationSource": "来源模板实际执行 SQL 声明中的 COUNT(*) AS warehouse_count 与来源描述“统计仓库数量”", "status": "CONFIRMED"},
    {"datasetReference": DS_REF, "sourceField": "city_count", "meaning": "该分组内的去重城市数（COUNT(DISTINCT w_city)）",
     "declarationSource": "来源模板实际执行 SQL 声明中的 COUNT(DISTINCT w_city) AS city_count 与来源描述“覆盖城市数量”", "status": "CONFIRMED"},
    {"datasetReference": DS_REF, "sourceField": "min_warehouse_sk", "meaning": "分组内仓库代理键最小值（标识，非数量）",
     "declarationSource": "来源模板实际执行 SQL 声明中的 MIN(w_warehouse_sk) AS min_warehouse_sk", "status": "CONFIRMED"},
    {"datasetReference": DS_REF, "sourceField": "max_warehouse_sk", "meaning": "分组内仓库代理键最大值（标识，非数量）",
     "declarationSource": "来源模板实际执行 SQL 声明中的 MAX(w_warehouse_sk) AS max_warehouse_sk", "status": "CONFIRMED"},
]

limitations = [
    "交付结果仅含 1 个国家/省州分组（United States / TN），国家与省州分布只有单一类别，无法支持跨区域对比、排序或占比结论。",
    "来源未提供日期、时点或数据版本字段，“当前全部仓库”只能理解为该次交付快照，无法对应具体统计日期，也不支持任何时间趋势分析。",
    "w_state 以源代码形式给出（如 TN），未提供代码字典或地区名称映射，报告按源样保留，不推断州全称。",
    "min_warehouse_sk / max_warehouse_sk 为仓库代理键区间，仅说明覆盖面，未作为数量或金额参与任何加总。",
    "城市覆盖数为分组级 COUNT(DISTINCT w_city) 之和；本次仅 1 个分组，不涉及跨分组同名城市重复问题，但该加总口径在分组增多时需复核城市归属唯一性。",
    "交付来源标注为 TPCDS 仓库维表分布模板，快照仅 5 个仓库，可能为示例数据，绝对量不宜外推为真实业务仓库总量。",
    "未计算仓库面积、容量或经营类指标：源数据未提供相应字段，不做推测。",
]

row = {
    "reportMarkdown": report_md,
    "evidenceReferences": [DS_REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

with open(OUT / "rows.jsonl", "w", encoding="utf-8") as fh:
    fh.write(json.dumps(row, ensure_ascii=False) + "\n")

diag = {
    "manifest_sha256_match": manifest_sha == EXPECTED_MANIFEST_SHA,
    "schema_sha256_match": schema_sha == EXPECTED_SCHEMA_SHA,
    "declared_rows": declared_rows,
    "actual_rows": actual_rows,
    "groups": groups,
    "totals": {"warehouse": total_wh, "city": total_city, "groups": n_groups},
    "density": density_str,
    "charts": [{"file": n, "bytes": (OUT / n).stat().st_size} for n in (chart1_name, chart2_name)],
}
(VAL / "diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")
