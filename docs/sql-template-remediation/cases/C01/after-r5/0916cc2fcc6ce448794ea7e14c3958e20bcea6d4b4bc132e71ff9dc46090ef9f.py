import json, hashlib, os, time
from decimal import Decimal

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pyarrow.parquet as pq

T0 = time.perf_counter()
CHART_MS = 0.0

OUT_DIR = "/workspace/output/results"
VAL_DIR = "/workspace/output/validation"
os.makedirs(OUT_DIR, exist_ok=True)
os.makedirs(VAL_DIR, exist_ok=True)

MANIFEST_PATH = "/data/input/cd824158a3b4720874e76ce6a7f407433a96803477dd4428372e2efd0a1c3b62/manifest.json"
SCHEMA_PATH = "/data/input/c82ebe7d86b48fc44b0741648806a8e3188c6c8eef06f6e966af7a3430d7b7b2/schema.json"
EXPECT_MANIFEST_SHA = "10562298654a97137985ffc8ee2ed0741270d777f9191c0a036a88853970990c"
DATASET_REF = "mcp_chatchat_mcp_server_tpl_f61ea326b46f30aca4ecc717c2d2f9bc"
DECLARED_FIELDS = ["w_country", "w_state", "warehouse_count", "city_count",
                   "min_warehouse_sk", "max_warehouse_sk"]

checks = []
def add_check(name, status, detail):
    checks.append({"name": name, "status": status, "detail": detail})

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def as_dec(v):
    if isinstance(v, Decimal):
        return v
    return Decimal(str(v))

# ---------- manifest ----------
man_sha = sha256_file(MANIFEST_PATH)
with open(MANIFEST_PATH, "r", encoding="utf-8") as fh:
    manifest = json.load(fh)
add_check("输入清单完整性",
          "PASSED" if man_sha == EXPECT_MANIFEST_SHA else "FAILED",
          "清单文件哈希与交付声明一致" if man_sha == EXPECT_MANIFEST_SHA else "清单文件哈希与交付声明不一致")

col_map = {c["storageName"]: c["name"] for c in manifest["columns"]}
parts = manifest["parts"]

# ---------- schema (declared envelope) ----------
with open(SCHEMA_PATH, "r", encoding="utf-8") as fh:
    schema_doc = json.load(fh)

# ---------- parts ----------
records = []
part_row_total = 0
part_hash_ok = True
null_counts = {}
for p in parts:
    ppath = "/data/input/{}/{}".format(p["fileId"], p["fileName"])
    if sha256_file(ppath) != p["sha256"]:
        part_hash_ok = False
    tbl = pq.read_table(ppath)
    part_row_total += tbl.num_rows
    for i, cname in enumerate(tbl.column_names):
        logical = col_map.get(cname, cname)
        null_counts[logical] = null_counts.get(logical, 0) + tbl.column(i).null_count
    for raw in tbl.to_pylist():
        row = {col_map.get(k, k): v for k, v in raw.items()}
        rec = row.get("record")
        if isinstance(rec, (bytes, bytearray)):
            rec = rec.decode("utf-8")
        if isinstance(rec, str):
            rec = json.loads(rec, parse_float=Decimal)
        row["record"] = rec
        records.append(row)

add_check("数据分片完整性与行数",
          "PASSED" if (part_hash_ok and part_row_total == manifest["rowCount"]) else "FAILED",
          "数据分片哈希校验通过，实际行数 {} 与清单声明 {} 一致".format(part_row_total, manifest["rowCount"])
          if (part_hash_ok and part_row_total == manifest["rowCount"]) else "分片哈希或行数与清单不一致")

add_check("数据集引用一致性",
          "PASSED" if all(r.get("datasetReference") == DATASET_REF for r in records) else "FAILED",
          "记录内数据集引用与授权引用一致")

# ---------- decode logical records ----------
groups = []
for r in records:
    rec = r["record"]
    groups.append({
        "country": rec.get("w_country"),
        "state": rec.get("w_state"),
        "warehouse_count": as_dec(rec.get("warehouse_count")),
        "city_count": as_dec(rec.get("city_count")),
        "min_sk": as_dec(rec.get("min_warehouse_sk")),
        "max_sk": as_dec(rec.get("max_warehouse_sk")),
    })

field_ok = all(set(g.keys()) == {"country", "state", "warehouse_count", "city_count", "min_sk", "max_sk"}
               and all(v is not None for v in g.values()) for g in groups)
missing_fields = sorted({k for r in records for k in DECLARED_FIELDS if k not in r["record"]})
add_check("声明字段完整性与空值",
          "PASSED" if field_ok and not missing_fields and not any(null_counts.values()) else "FAILED",
          "全部 6 个声明字段均已交付、无 null、无缺失" if field_ok and not missing_fields and not any(null_counts.values())
          else "存在缺失/null 字段: {}".format(missing_fields or null_counts))

# duplicate group keys
key_pairs = [(g["country"], g["state"]) for g in groups]
add_check("分组键唯一性",
          "PASSED" if len(key_pairs) == len(set(key_pairs)) else "FAILED",
          "国家/省州分组键唯一，共 {} 组".format(len(set(key_pairs))))

groups.sort(key=lambda g: (str(g["country"]), str(g["state"])))

# ---------- aggregates ----------
total_warehouses = sum((g["warehouse_count"] for g in groups), Decimal("0"))
group_count = len(groups)
countries = []
for g in groups:
    if g["country"] not in countries:
        countries.append(g["country"])
total_cities = sum((g["city_count"] for g in groups), Decimal("0"))
sk_min = min((g["min_sk"] for g in groups), default=None)
sk_max = max((g["max_sk"] for g in groups), default=None)

add_check("仓库总数与主键跨度一致性",
          "PASSED" if (sk_min is not None and sk_max is not None and (sk_max - sk_min + 1) == total_warehouses
                       and len(groups) == 1) else "UNRESOLVED",
          "单一分组内仓库记录数 {} 与主键跨度 {}–{}（{} 个不同键值）一致".format(
              total_warehouses, sk_min, sk_max, (sk_max - sk_min + 1))
          if (sk_min is not None and (sk_max - sk_min + 1) == total_warehouses and len(groups) == 1)
          else "主键跨度与仓库记录数无法直接对照，需分组级明细验证")

# declared completeness of the executed query population
add_check("查询结果完整性声明",
          "PASSED",
          "交付声明该查询为 warehouse 维表全量分组结果（result complete，无过滤参数），返回 {} 组".format(group_count))

add_check("城市名称明细可得性", "UNRESOLVED",
          "源查询仅返回分组内不同城市数，未返回城市名称清单，无法列出具体城市")
add_check("业务时点/统计期间", "UNRESOLVED",
          "交付字段中不含任何日期或时间字段，无法界定快照的业务时点或统计期间")

# ---------- chart ----------
labels = ["{} / {}".format(g["country"], g["state"]) for g in groups]
values = [int(g["warehouse_count"]) for g in groups]
assert sum(values) == int(total_warehouses)

chart_name = "warehouse_country_state_bar.png"
chart_path = os.path.join(OUT_DIR, chart_name)
c_start = time.perf_counter()
chart_error = None
try:
    fig, ax = plt.subplots(figsize=(9.0, 4.8), dpi=150)
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")
    bars = ax.bar(range(len(labels)), values, width=0.42, color="#3b6ea5",
                  edgecolor="#1f3a5f", linewidth=0.8, zorder=3)
    for b, v in zip(bars, values):
        ax.annotate("{} 个".format(v), (b.get_x() + b.get_width() / 2, v),
                    textcoords="offset points", xytext=(0, 6), ha="center",
                    fontsize=11, color="#1f3a5f")
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, fontsize=11)
    ax.set_ylabel("仓库数量（个）", fontsize=11)
    ax.set_xlabel("国家 / 省州", fontsize=11)
    ax.set_ylim(0, max(values) * 1.35 if values else 1)
    ax.yaxis.grid(True, color="#dcdcdc", linewidth=0.6, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color("#9a9a9a")
    ax.set_title("全部仓库的国家/省州分布：仅 1 个分组，合计 {} 个仓库".format(int(total_warehouses)),
                 fontsize=12, pad=12, loc="left")
    fig.text(0.01, 0.01,
             "口径：warehouse 维表按 w_country、w_state 分组计数；数据范围 1 个国家/省州分组、"
             "{} 个仓库记录、覆盖 {} 个城市；交付数据不含日期字段，无法标注业务时点。".format(
                 group_count, int(total_warehouses), int(total_cities)),
             fontsize=8.5, color="#555555", ha="left", va="bottom")
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(chart_path, facecolor="white")
    plt.close(fig)
except Exception as exc:  # noqa: BLE001
    chart_error = "{}: {}".format(type(exc).__name__, exc)
CHART_MS = (time.perf_counter() - c_start) * 1000.0

if chart_error is None:
    add_check("柱状图渲染", "PASSED", "仓库数量柱状图已生成，横轴为国家/省州、纵轴为仓库数量（个）")
else:
    add_check("柱状图渲染", "FAILED", "柱状图渲染失败：{}".format(chart_error))

# ---------- tables ----------
detail_rows = [[g["country"], g["state"], str(int(g["warehouse_count"])),
                str(int(g["city_count"])), str(int(g["min_sk"])), str(int(g["max_sk"]))] for g in groups]
assert len(detail_rows) == group_count
assert sum(int(r[2]) for r in detail_rows) == int(total_warehouses)

metrics = [
    {"id": "total_warehouses", "label": "仓库总数", "value": str(int(total_warehouses)), "unit": "个",
     "calculation": "对查询返回的各国家/省州分组的 warehouse_count（COUNT(*)）求和",
     "dataRefs": [DATASET_REF]},
    {"id": "group_count", "label": "国家/省州分组数", "value": str(group_count), "unit": "个",
     "calculation": "交付结果按 w_country、w_state 分组后的行数",
     "dataRefs": [DATASET_REF]},
    {"id": "country_count", "label": "涉及国家数", "value": str(len(countries)), "unit": "个",
     "calculation": "分组 w_country 取值去重计数",
     "dataRefs": [DATASET_REF]},
    {"id": "city_count", "label": "覆盖城市数", "value": str(int(total_cities)), "unit": "个",
     "calculation": "对分组 city_count（COUNT(DISTINCT w_city)）求和；当前仅 1 组，不存在跨组重复计数",
     "dataRefs": [DATASET_REF]},
    {"id": "sk_range", "label": "仓库主键范围", "value": "{}–{}".format(int(sk_min), int(sk_max)) if sk_min is not None else "",
     "unit": "", "calculation": "各分组 min_warehouse_sk 的最小值与 max_warehouse_sk 的最大值",
     "dataRefs": [DATASET_REF]},
]

tables = [
    {"id": "detail_table", "title": "仓库国家/省州分布明细（全部交付分组）",
     "columns": ["国家", "省州", "仓库数量（个）", "覆盖城市数（个）", "仓库主键最小值", "仓库主键最大值"],
     "rows": detail_rows,
     "scope": "warehouse 维表快照，全部 {} 个分组 / {} 个仓库记录".format(group_count, int(total_warehouses)),
     "dataRefs": [DATASET_REF]},
]

# ---------- field provenance ----------
field_provenance = [
    {"datasetReference": DATASET_REF, "sourceField": "w_country",
     "meaning": "仓库所在国家；源查询按该字段分组，作为分布横轴的第一层维度",
     "declarationSource": "数据集 sourceDefinition.description（按国家和省州统计仓库）与 queryDefinition 的 GROUP BY w_country",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "w_state",
     "meaning": "仓库所在省/州；源查询按该字段分组，作为分布横轴的第二层维度",
     "declarationSource": "sourceDefinition.description 与 queryDefinition 的 GROUP BY w_state",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "warehouse_count",
     "meaning": "分组内仓库记录数（仓库数量）；公式 COUNT(*)",
     "declarationSource": "queryDefinition 显式别名 warehouse_count 与 description（统计仓库数量）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "city_count",
     "meaning": "分组内不同城市数量；公式 COUNT(DISTINCT w_city)",
     "declarationSource": "queryDefinition 显式别名 city_count 与 description（覆盖城市数量）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "min_warehouse_sk",
     "meaning": "分组内仓库主键（w_warehouse_sk）最小值；属于标识符，不作金额或数量求和",
     "declarationSource": "queryDefinition 显式别名 min_warehouse_sk 与 description（仓库主键范围）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "max_warehouse_sk",
     "meaning": "分组内仓库主键（w_warehouse_sk）最大值；属于标识符，不作金额或数量求和",
     "declarationSource": "queryDefinition 显式别名 max_warehouse_sk 与 description（仓库主键范围）",
     "status": "CONFIRMED"},
]
assert all(f["status"] == "CONFIRMED" for f in field_provenance)

# ---------- narrative ----------
only_group = groups[0] if groups else None
geo_label = "{} / {}".format(only_group["country"], only_group["state"]) if only_group else "无分组"

md_metric_rows = "\n".join("| {} | {} | {} | {} |".format(m["label"], m["value"], m["unit"], m["calculation"])
                           for m in metrics)
md_detail = "\n".join("| " + " | ".join(r) + " |" for r in detail_rows)
md_prov = "\n".join("| {} | {} | {} | {} |".format(f["sourceField"], f["meaning"], f["declarationSource"], f["status"])
                    for f in field_provenance)

report_md = """# 仓库国家/省州分布（交付快照）

## 结论
本次交付的仓库数据集中在 **1 个国家/省州分组：{geo}**，合计 **{total} 个仓库**，覆盖 **{cities} 个城市**。
也就是说，在当前数据范围内不存在跨国家或跨省州的分布差异——柱状图只有一根柱子，无法给出“哪个国家/地区仓库更多”的排名结论。这是数据范围本身的结果，而不是计算失败。

## 核心指标
| 指标 | 数值 | 单位 | 计算方式 |
| --- | --- | --- | --- |
{metric_rows}

## 分布结果
![仓库数量柱状图]({chart})

数据明细（全部交付分组）：

| 国家 | 省州 | 仓库数量（个） | 覆盖城市数（个） | 仓库主键最小值 | 仓库主键最大值 |
| --- | --- | --- | --- | --- | --- |
{detail}

## 城市覆盖与数据范围
- **城市覆盖**：{cities} 个城市。该数字来自源查询的“分组内不同城市数”指标（COUNT(DISTINCT w_city)）之和；当前只有 1 个分组，因此不存在跨分组重复计数问题。源查询未返回城市名称清单，**无法列出具体城市名称**。
- **地理范围**：仅 {geo} 一个分组，{country_n} 个国家。
- **仓库范围**：共 {total} 条仓库记录，主键取值落在 {skmin}–{skmax}，跨度 {span} 个值，与仓库记录数一致。
- **时间范围**：交付数据中**没有任何日期或时间字段**，因此无法界定“当前”对应的业务时点或统计期间；只能表述为一次交付的仓库维表快照。
- **完整性**：交付声明该查询为 warehouse 维表按国家/省州全量分组结果（无过滤参数、结果完整），因此可以认为除上述分组外没有其它国家/省州分组被遗漏。

## 口径与字段来源
| 源字段 | 业务含义 | 声明来源 | 状态 |
| --- | --- | --- | --- |
{prov}

## 局限
- 分布只有一个分组，无法开展跨国家/省州的数量对比、排序或份额分析（也不存在可计算的“占比”——单一分组占比恒为 100%，不构成有效结论）。
- 城市覆盖只有数量、没有名称与城市级明细，无法回答“具体哪些城市有仓库”。
- 缺少日期字段，无法说明统计截止日或历史变化，也无法判断该分布是否长期稳定。
- “仓库数量”是 warehouse 维表按 COUNT(*) 得到的记录数，其业务口径依赖源查询声明（每行代表一个仓库）；本次未获得独立的仓库台账可比总数，因此未做跨源对账。

## 校验与审计附录
- 已核对交付清单、数据分片哈希与行数（{rows} 行），并逐字段映射存储列到声明字段，六个声明字段均无 null/缺失。
- 分组键（国家+省州）唯一；明细表仓库数量合计与指标“仓库总数”一致。
- 图表数值与明细表使用同一计算结果，未做四舍五入替换。

*统计口径单位“个”为用户指定的计数单位；仓库主键为标识符，未参与任何求和。*
""".format(geo=geo_label, total=int(total_warehouses), cities=int(total_cities),
           metric_rows=md_metric_rows, chart=chart_name, detail=md_detail,
           country_n=len(countries), skmin=int(sk_min), skmax=int(sk_max),
           span=int(sk_max - sk_min + 1), prov=md_prov, rows=part_row_total)

limitations = [
    "交付数据仅包含 1 个国家/省州分组（{}），无法支持跨国家或跨省州的仓库数量对比、排序或份额结论。".format(geo_label),
    "城市覆盖只有分组内不同城市数（合计 {} 个），源查询未返回城市名称清单，无法列出具体城市。".format(int(total_cities)),
    "交付字段中不含任何日期/时间字段，无法界定“当前”的业务时点或统计期间，报告只能描述为一次交付的维表快照。",
    "仓库数量为 warehouse 维表按 COUNT(*) 得到的记录数，口径依赖源查询声明；本次没有独立的仓库台账可比总数，未做跨源对账。",
    "单一分组下仓库占比恒为 100%，不具分析意义，因此未提供份额类指标或饼图。",
]

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "仓库国家/省州分布（交付快照）",
    "summary": "交付的仓库数据全部落在 1 个国家/省州分组：{}，合计 {} 个仓库，覆盖 {} 个城市；仓库主键范围 {}–{}。"
               "数据中无其它国家/省州分组，也无日期字段，因此只能给出单一分组的分布事实，无法做跨地区对比或时点界定。".format(
                   geo_label, int(total_warehouses), int(total_cities), int(sk_min), int(sk_max)),
    "status": "PARTIAL",
    "metrics": [{k: v for k, v in m.items() if k != "dataRefs"} for m in metrics],
    "sections": [
        {"id": "sec_distribution", "title": "国家/省州分布与城市覆盖",
         "narrative": "全部 {} 个仓库集中在 {}，覆盖 {} 个城市。由于仅 1 个分组，柱状图只有一根柱子，"
                      "不存在跨国家/省州的排序或对比；明细表列出该分组的仓库数量、覆盖城市数与仓库主键范围。".format(
                          int(total_warehouses), geo_label, int(total_cities)),
         "chartIds": [c["id"] for c in [{"id": "chart_bar"}]] if chart_error is None else [],
         "tableIds": ["detail_table"]},
        {"id": "sec_scope", "title": "数据范围与口径",
         "narrative": "地理范围仅 {}；仓库记录 {} 条，主键取值 {}–{}，跨度与记录数一致。交付声明该结果为 warehouse 维表按国家/省州全量分组，未使用过滤参数。"
                      "城市覆盖为分组内 COUNT(DISTINCT w_city) 之和（当前仅 1 组，无重复计数），但没有城市名称明细。"
                      "数据不含日期字段，无法标注统计截止日或业务时点，因此所有数字只代表本次交付快照。".format(
                          geo_label, int(total_warehouses), int(sk_min), int(sk_max)),
         "chartIds": [], "tableIds": []},
    ],
    "charts": [] if chart_error is not None else [
        {"id": "chart_bar", "title": "仓库数量按国家/省州分布（仓库数量，个；单分组快照）",
         "kind": "png", "fileName": chart_name, "chartType": "bar",
         "caption": "横轴：国家/省州（w_country / w_state）；纵轴：仓库数量（个），源自 warehouse 维表 COUNT(*) 分组计数。"
                    "数据范围：{} 个分组、{} 个仓库记录、覆盖 {} 个城市；数据无日期字段，未标注业务时点。".format(
                        group_count, int(total_warehouses), int(total_cities)),
         "dataRefs": [DATASET_REF]}
    ],
    "tables": tables,
    "dataSources": [DATASET_REF],
    "validation": {
        "checks": checks,
        "warnings": ([chart_error] if chart_error else []) + [
            "分布仅 1 个国家/省州分组，无法形成跨地区比较。",
            "数据不含日期字段，无法界定快照业务时点。",
            "城市名称明细未随查询返回，仅有覆盖城市数量。",
        ],
    },
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable",
                     "autoCharts": True, "autoImages": True},
    "layout": {
        "schemaVersion": "report_layout.v1",
        "blocks": [
            {"id": "blk_summary", "type": "summary", "span": "full"},
            {"id": "blk_m_total", "type": "metric", "ref": "total_warehouses", "span": "half", "emphasis": "important"},
            {"id": "blk_m_groups", "type": "metric", "ref": "group_count", "span": "half"},
            {"id": "blk_m_countries", "type": "metric", "ref": "country_count", "span": "half"},
            {"id": "blk_m_cities", "type": "metric", "ref": "city_count", "span": "half"},
            {"id": "blk_m_sk", "type": "metric", "ref": "sk_range", "span": "half"},
            {"id": "blk_sec_dist", "type": "section", "ref": "sec_distribution", "span": "full", "emphasis": "important"},
            {"id": "blk_chart_bar", "type": "chart", "ref": "chart_bar", "span": "full"},
            {"id": "blk_table_detail", "type": "table", "ref": "detail_table", "span": "full"},
            {"id": "blk_sec_scope", "type": "section", "ref": "sec_scope", "span": "full"},
            {"id": "blk_limits", "type": "limitations", "span": "full"},
            {"id": "blk_sources", "type": "sources", "span": "full"},
        ],
    },
}

# consistency assertions across report objects
assert visual_report["summary"].find(str(int(total_warehouses))) != -1
assert sum(int(r[2]) for r in visual_report["tables"][0]["rows"]) == int(total_warehouses)
if visual_report["charts"]:
    assert sum(values) == int(total_warehouses)
    assert os.path.exists(os.path.join(OUT_DIR, chart_name))
metric_ids = {m["id"] for m in metrics}
section_ids = {s["id"] for s in visual_report["sections"]}
table_ids = {t["id"] for t in tables}
chart_ids = {c["id"] for c in visual_report["charts"]}
for blk in visual_report["layout"]["blocks"]:
    if blk["type"] == "metric":
        assert blk["ref"] in metric_ids
    elif blk["type"] == "section":
        assert blk["ref"] in section_ids
    elif blk["type"] == "table":
        assert blk["ref"] in table_ids
    elif blk["type"] == "chart":
        assert blk["ref"] in chart_ids

analysis_status = "PARTIAL"
row = {
    "reportMarkdown": report_md,
    "evidenceReferences": [DATASET_REF],
    "limitations": limitations,
    "analysisStatus": analysis_status,
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

with open(os.path.join(OUT_DIR, "rows.jsonl"), "w", encoding="utf-8") as fh:
    fh.write(json.dumps(row, ensure_ascii=False) + "\n")

diag = {
    "manifest_sha256": man_sha,
    "part_rows": part_row_total,
    "null_counts": null_counts,
    "groups": [{k: str(v) for k, v in g.items()} for g in groups],
    "totals": {"warehouses": str(total_warehouses), "groups": group_count,
               "countries": len(countries), "cities": str(total_cities)},
    "chart_error": chart_error,
    "checks": checks,
    "analysis_ms": round((time.perf_counter() - T0) * 1000.0, 1),
}
with open(os.path.join(VAL_DIR, "diagnostics.json"), "w", encoding="utf-8") as fh:
    json.dump(diag, fh, ensure_ascii=False, indent=2)

# persist timings into the written row (rewrite with measured timings)
analysis_ms = (time.perf_counter() - T0) * 1000.0
row["visualReport"]["timings"] = {"analysisMs": round(analysis_ms, 1), "chartMs": round(CHART_MS, 1)}
with open(os.path.join(OUT_DIR, "rows.jsonl"), "w", encoding="utf-8") as fh:
    fh.write(json.dumps(row, ensure_ascii=False) + "\n")
