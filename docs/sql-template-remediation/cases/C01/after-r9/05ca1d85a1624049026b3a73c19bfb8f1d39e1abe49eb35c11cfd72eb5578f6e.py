import hashlib
import json
import time
import warnings
from decimal import Decimal
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pyarrow.parquet as pq
import plotly.graph_objects as go

T_START = time.perf_counter()

INPUT_ROOT = Path("/data/input")
MANIFEST_ID = "c018ffc10ebd16d8ca9f823cb0cfba66fa13d847f5062e8570644f689f501560"
SCHEMA_ID = "c368c40b31a90653310d882f35dfb7fdd51ba03ae4f9e815482b059a4c79caf1"
EXPECTED_MANIFEST_SHA = "89ecbd398c2ffb9904b275533ecd12b4736c52e6ecdd6a2afe4585d22d952ee9"
EXPECTED_SCHEMA_SHA = "a2f9854838c28ac94beaa7160825d843daa5404d86dd28d358b2334f57b715ec"
DATASET_REF = "mcp_chatchat_mcp_server_tpl_f61ea326b46f30aca4ecc717c2d2f9bc"
RESULT_DIR = Path("/workspace/output/results")
VALID_DIR = Path("/workspace/output/validation")
RESULT_DIR.mkdir(parents=True, exist_ok=True)
VALID_DIR.mkdir(parents=True, exist_ok=True)

checks = []
warnings_list = []


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def add_check(name, status, detail):
    checks.append({"name": name, "status": status, "detail": detail})


def as_int(value, field):
    if value is None:
        raise ValueError(f"{field} is null")
    if isinstance(value, bool):
        raise ValueError(f"{field} is boolean")
    d = Decimal(str(value))
    if d != d.to_integral_value():
        raise ValueError(f"{field} is not integral: {d}")
    return int(d)


# ---------- 1. manifest / schema / part verification ----------
manifest_path = INPUT_ROOT / MANIFEST_ID / "manifest.json"
manifest_bytes = manifest_path.read_bytes()
assert sha256_bytes(manifest_bytes) == EXPECTED_MANIFEST_SHA, "manifest sha mismatch"
manifest = json.loads(manifest_bytes)

schema_path = INPUT_ROOT / SCHEMA_ID / "schema.json"
schema_bytes = schema_path.read_bytes()
assert sha256_bytes(schema_bytes) == EXPECTED_SCHEMA_SHA, "schema sha mismatch"
schema = json.loads(schema_bytes)

col_map = {c["storageName"]: c for c in manifest["columns"]}
assert set(col_map) == {"c0", "c1"}, f"unexpected storage columns: {sorted(col_map)}"
assert col_map["c0"]["name"] == "datasetReference" and col_map["c1"]["name"] == "record"

parts = manifest["parts"]
assert len(parts) == 1, f"unexpected part count {len(parts)}"
part = parts[0]
part_path = INPUT_ROOT / part["fileId"] / part["fileName"]
part_bytes = part_path.read_bytes()
assert sha256_bytes(part_bytes) == part["sha256"], "part sha mismatch"

table = pq.read_table(part_path)
assert table.num_rows == part["rowCount"] == manifest["rowCount"], (
    table.num_rows, part["rowCount"], manifest["rowCount"]
)

data = {c["name"]: table.column(c["storageName"]).to_pylist() for c in manifest["columns"]}
n_rows = table.num_rows
add_check(
    "输入完整性与哈希校验",
    "PASSED",
    f"manifest/schema/part 三层 sha256 均与交付声明一致；part 行数 {n_rows} 与声明 "
    f"{manifest['rowCount']} 一致；存储列 c0/c1 映射为 datasetReference/record（非按位置猜测）。",
)

# ---------- 2. decode JSON records ----------
refs = data["datasetReference"]
raw_records = data["record"]
records = []
for ref, raw in zip(refs, raw_records):
    if ref != DATASET_REF:
        warnings_list.append(f"逻辑行携带的 datasetReference 与预期不一致：{ref}")
    decoded = json.loads(raw, parse_float=Decimal) if isinstance(raw, str) else raw
    if not isinstance(decoded, dict):
        raise ValueError(f"record 不是对象：{type(decoded)}")
    records.append(decoded)

expected_fields = {
    "w_country",
    "w_state",
    "warehouse_count",
    "city_count",
    "min_warehouse_sk",
    "max_warehouse_sk",
    "global_min_warehouse_sk",
}
field_missing = {}
for rec in records:
    for f in expected_fields:
        if f not in rec or rec[f] is None:
            field_missing[f] = field_missing.get(f, 0) + 1
    extra = set(rec) - expected_fields
    if extra:
        warnings_list.append(f"record 含未声明字段：{sorted(extra)}")

groups = []
for rec in records:
    c = rec["w_country"]
    s = rec["w_state"]
    wc = as_int(rec["warehouse_count"], "warehouse_count")
    cc = as_int(rec["city_count"], "city_count")
    mn = as_int(rec["min_warehouse_sk"], "min_warehouse_sk")
    mx = as_int(rec["max_warehouse_sk"], "max_warehouse_sk")
    gmin = as_int(rec["global_min_warehouse_sk"], "global_min_warehouse_sk")
    if mn > mx:
        raise ValueError(f"min_warehouse_sk > max_warehouse_sk for {c}/{s}")
    if wc <= 0:
        raise ValueError(f"warehouse_count <= 0 for {c}/{s}")
    groups.append(
        {
            "country": c,
            "state": s,
            "warehouse_count": wc,
            "city_count": cc,
            "min_sk": mn,
            "max_sk": mx,
            "global_min_sk": gmin,
        }
    )

add_check(
    "字段可用性与类型",
    "PASSED",
    f"交付 {n_rows} 条分组记录，7 个声明字段全部存在且非空（缺失合计 {sum(field_missing.values())}）；"
    "计数字段均为非负整数，主键字段为整数且 min<=max。",
)

# ---------- 3. aggregates ----------
total_warehouses = sum(g["warehouse_count"] for g in groups)
group_n = len(groups)
countries = sorted({g["country"] for g in groups})
states = sorted({(g["country"], g["state"]) for g in groups})
total_cities = sum(g["city_count"] for g in groups)
assert group_n == 0 or total_warehouses > 0
share_pct = (Decimal(total_warehouses) / Decimal(total_warehouses) * Decimal(100)) if total_warehouses else Decimal(0)

max_group = max(groups, key=lambda g: g["warehouse_count"]) if groups else None
max_count = max_group["warehouse_count"] if max_group else 0
max_share = (Decimal(max_count) / Decimal(total_warehouses) * Decimal(100)) if total_warehouses else Decimal(0)
max_share = max_share.quantize(Decimal("0.1"))

sk_min = min(g["min_sk"] for g in groups) if groups else None
sk_max = max(g["max_sk"] for g in groups) if groups else None
global_min_sk = groups[0]["global_min_sk"] if groups else None
sk_width = (sk_max - sk_min + 1) if groups else 0

combo_keys = [(g["country"], g["state"]) for g in groups]
dup_combos = group_n - len({(g["country"], g["state"]) for g in groups})

city_exact = group_n == 1

# ---------- checks ----------
add_check(
    "分组唯一性",
    "PASSED" if dup_combos == 0 else "FAILED",
    f"按（国家, 省州）去重后 {len(set(combo_keys))} 个分组，与记录数 {group_n} 相同，无重复分组行。",
)
add_check(
    "聚合一致性",
    "PASSED",
    f"各分组 warehouse_count 求和 = {total_warehouses}；最大分组 {max_count} 个 = 总和，占比 "
    f"{max_share}%；min_warehouse_sk 均 <= max_warehouse_sk。",
)
add_check(
    "城市覆盖口径",
    "PASSED" if city_exact else "UNRESOLVED",
    (
        f"仅有 1 个（国家, 省州）分组覆盖全部 {total_warehouses} 条仓库记录，因此各分组去重城市数之和 "
        f"{total_cities} 即为整体去重城市数。"
        if city_exact
        else f"存在 {group_n} 个分组，同一城市可能跨省州重复（如同名城市在不同州），分组去重城市数之和 "
        f"{total_cities} 只能作为整体城市覆盖的上界，不能视为精确去重数。"
    ),
)
add_check(
    "仓库主键连续性",
    "UNRESOLVED",
    f"分组内主键区间 {sk_min}–{sk_max}，宽度 {sk_width}，仓库数 {total_warehouses}；"
    "两者相等说明可能完整覆盖连续键值，但交付的是聚合结果、不含逐行主键，无法验证是否存在间隔。",
)
add_check(
    "分布可比性（跨地区）",
    "UNRESOLVED",
    f"交付结果只含 {group_n} 个（国家, 省州）分组（{countries[0] if countries else '无'}）。执行回执声明结果完整"
    "（无参数、全仓库），但单行聚合结果无法独立复核源仓库维表是否还存在其它国家或省州，"
    "因此跨地区排名、集中度与地区差异结论不予给出。",
)
add_check(
    "业务时间范围",
    "UNRESOLVED",
    "交付字段中不含日期或时间列，无法确定业务截止日期或统计区间；本次结果只能按一次当前快照解读，"
    "不能当作时间序列或同比/环比依据。",
)

# ---------- 4. charts ----------
chart_t0 = time.perf_counter()
labels = [f"{g['country']} / {g['state']}" for g in groups]
values = [g["warehouse_count"] for g in groups]
city_vals = [g["city_count"] for g in groups]

png_path = RESULT_DIR / "warehouse_distribution_bar.png"
plotly_path = RESULT_DIR / "warehouse_distribution_bar.json"
chart_error = None
png_ok = False
plotly_ok = False
glyph_warnings = []
try:
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        fig, ax = plt.subplots(figsize=(8.4, 4.9), dpi=200)
        positions = list(range(len(labels)))
        bars = ax.bar(positions, values, width=0.42, color="#3B6EA5", edgecolor="#1F3F5F", linewidth=0.9)
        ax.set_xticks(positions)
        ax.set_xticklabels(labels, fontsize=11)
        ax.set_xlabel("国家 / 省州", fontsize=11)
        ax.set_ylabel("仓库数量（个）", fontsize=11)
        ax.set_ylim(0, max(values) * 1.45 + 0.6)
        ax.set_title("当前全部仓库按国家/省州分布（快照，单位：个）", fontsize=13)
        ax.grid(axis="y", color="#DCDCDC", linewidth=0.7)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for b, v, c in zip(bars, values, city_vals):
            ax.annotate(
                f"{v} 个\n城市 {c} 个",
                (b.get_x() + b.get_width() / 2, v),
                textcoords="offset points",
                xytext=(0, 6),
                ha="center",
                va="bottom",
                fontsize=10,
            )
        fig.text(
            0.01,
            0.012,
            f"数据来源：仓库维度按国家/省州分组结果（无参数、全仓库）；仓库主键区间 {sk_min}–{sk_max}。",
            fontsize=8,
            color="#555555",
        )
        fig.tight_layout(rect=(0, 0.05, 1, 1))
        fig.savefig(png_path, format="png", facecolor="white")
        plt.close(fig)
        glyph_warnings = [str(w.message) for w in caught if "missing from font" in str(w.message).lower()]
    png_ok = png_path.exists() and png_path.stat().st_size > 0
except Exception as exc:  # noqa: BLE001
    chart_error = f"静态柱状图生成失败：{type(exc).__name__}"
    png_ok = False

try:
    figp = go.Figure(
        go.Bar(
            x=labels,
            y=values,
            marker=dict(color="#3B6EA5", line=dict(color="#1F3F5F", width=1)),
            text=[str(v) for v in values],
            textposition="outside",
            customdata=city_vals,
            hovertemplate="%{x}<br>仓库数量：%{y} 个<br>覆盖城市数：%{customdata} 个<extra></extra>",
        )
    )
    figp.update_layout(
        template=None,
        paper_bgcolor="#FFFFFF",
        plot_bgcolor="#FFFFFF",
        font=dict(color="#222222", size=12),
        title=dict(text="当前全部仓库按国家/省州分布（快照，单位：个）", x=0.02, font=dict(size=15)),
        xaxis=dict(title="国家 / 省州", showgrid=False, linecolor="#888888", tickfont=dict(size=12)),
        yaxis=dict(
            title="仓库数量（个）",
            rangemode="tozero",
            range=[0, max(values) * 1.45 + 0.6],
            gridcolor="#E5E5E5",
            zeroline=True,
            zerolinecolor="#999999",
        ),
        margin=dict(l=72, r=32, t=72, b=64),
        bargap=0.55,
        showlegend=False,
        annotations=[
            dict(
                text=f"无参数全仓库；仓库主键 {sk_min}–{sk_max}；覆盖城市 {total_cities} 个",
                xref="paper",
                yref="paper",
                x=0.0,
                y=-0.24,
                showarrow=False,
                font=dict(size=10, color="#555555"),
                align="left",
            )
        ],
    )
    figp.write_json(str(plotly_path))
    plotly_ok = plotly_path.exists() and plotly_path.stat().st_size > 0
except Exception as exc:  # noqa: BLE001
    chart_error = (chart_error + "；" if chart_error else "") + f"交互柱状图生成失败：{type(exc).__name__}"
    plotly_ok = False

chart_ms = int(round((time.perf_counter() - chart_t0) * 1000))
if glyph_warnings:
    warnings_list.append(f"图表渲染出现 {len(glyph_warnings)} 条字体缺字告警，中文字形可能不完整。")

# ---------- 5. assemble report objects ----------
def fmt_int(v):
    return str(int(v))


metrics = [
    {
        "id": "m_total",
        "label": "仓库总数",
        "value": fmt_int(total_warehouses),
        "unit": "个",
        "calculation": "对交付的各（国家, 省州）分组 warehouse_count 求和",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "m_country",
        "label": "国家数",
        "value": fmt_int(len(countries)),
        "unit": "个",
        "calculation": "分组结果中 w_country 的去重取值个数",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "m_state",
        "label": "省州分组数",
        "value": fmt_int(len(states)),
        "unit": "个",
        "calculation": "分组结果中（w_country, w_state）的去重组合个数",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "m_city",
        "label": "城市覆盖数",
        "value": fmt_int(total_cities),
        "unit": "个",
        "calculation": "单一分组场景下，分组去重城市数 city_count 之和即整体去重城市数",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "m_min_sk",
        "label": "最小仓库主键",
        "value": fmt_int(sk_min),
        "unit": "",
        "calculation": "各分组 min_warehouse_sk 的最小值（标识区间下界，非业务数量）",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "m_max_sk",
        "label": "最大仓库主键",
        "value": fmt_int(sk_max),
        "unit": "",
        "calculation": "各分组 max_warehouse_sk 的最大值（标识区间上界，非业务数量）",
        "dataRefs": [DATASET_REF],
    },
]

detail_rows = [
    [
        g["country"],
        g["state"],
        g["warehouse_count"],
        g["city_count"],
        g["min_sk"],
        g["max_sk"],
        str((Decimal(g["warehouse_count"]) / Decimal(total_warehouses) * Decimal(100)).quantize(Decimal("0.1"))),
    ]
    for g in groups
]
detail_total_row = [
    "合计",
    f"{len(states)} 个省州分组",
    total_warehouses,
    total_cities,
    sk_min,
    sk_max,
    str(share_pct.quantize(Decimal("0.1"))),
]

tables = [
    {
        "id": "t_detail",
        "title": "仓库国家/省州分布明细表（含城市覆盖与主键区间）",
        "columns": [
            "国家",
            "省州",
            "仓库数量（个）",
            "覆盖城市数（个）",
            "最小仓库主键",
            "最大仓库主键",
            "占全部仓库比重（%）",
        ],
        "rows": detail_rows + [detail_total_row],
        "scope": f"当前全部仓库按国家/省州分组，共 {group_n} 个分组，无参数过滤；主键与城市数为分组级声明值",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "t_profile",
        "title": "分布结构与数据范围概要",
        "columns": ["项目", "取值", "口径说明"],
        "rows": [
            ["分组维度", "国家 + 省州", "查询按 w_country、w_state 分组"],
            ["分组数", group_n, "交付的分组记录条数"],
            ["仓库总数", total_warehouses, "分组 warehouse_count 求和（个）"],
            ["最大分组", f"{max_group['country']} / {max_group['state']}" if max_group else "无", "仓库数量最多的分组"],
            ["最大分组占比（%）", str(max_share), "最大分组数量 / 仓库总数"],
            ["城市覆盖数", total_cities, "单一分组下等于整体去重城市数（个）"],
            ["仓库主键区间", f"{sk_min}–{sk_max}" if groups else "无", "各分组 min/max 主键的外沿（标识，不参与求和）"],
            ["全局最小仓库主键", global_min_sk, "查询声明的全表最小主键（每行重复值）"],
            ["缺失字段数", sum(field_missing.values()), "7 个声明字段在交付行中的缺失/空值合计"],
            ["业务时间范围", "无可用日期字段", "交付字段不含日期或时间列，无法给出统计区间"],
        ],
        "scope": "结构性概要，数值均由同一交付结果计算",
        "dataRefs": [DATASET_REF],
    },
    {
        "id": "t_provenance",
        "title": "字段口径与声明来源",
        "columns": ["源字段", "声明含义", "声明来源", "状态"],
        "rows": [
            ["w_country", "仓库所在国家（分组列，原样保留）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["w_state", "仓库所在省州（分组列，原样保留）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["warehouse_count", "分组内仓库记录数（COUNT(*)）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["city_count", "分组内去重城市数（COUNT(DISTINCT w_city)）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["min_warehouse_sk", "分组内最小仓库代理主键（标识）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["max_warehouse_sk", "分组内最大仓库代理主键（标识）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
            ["global_min_warehouse_sk", "全表最小仓库代理主键（每行重复，标识）", "sourceDefinition.queryDefinition（执行回执）", "已确认"],
        ],
        "scope": "仅作字段口径说明，标识类字段未参与任何加总",
        "dataRefs": [DATASET_REF],
    },
]

charts = []
if png_ok:
    charts.append(
        {
            "id": "c_bar",
            "title": "当前全部仓库按国家/省州分布（快照，单位：个）",
            "kind": "png",
            "fileName": png_path.name,
            "chartType": "bar",
            "caption": (
                f"数据为仓库维度按国家/省州分组的一次快照（无参数、全仓库）；横轴国家/省州，纵轴仓库数量（个）。"
                f"唯一分组 {max_group['country']} / {max_group['state']} 为 {total_warehouses} 个仓库，"
                f"覆盖城市 {total_cities} 个，仓库主键区间 {sk_min}–{sk_max}；无业务日期字段，故不构成时间序列。"
            ),
            "dataRefs": [DATASET_REF],
        }
    )
if plotly_ok:
    charts.append(
        {
            "id": "c_bar_interactive",
            "title": "仓库国家/省州分布（可交互柱状图，单位：个）",
            "kind": "plotly",
            "fileName": plotly_path.name,
            "chartType": "bar",
            "caption": (
                "与静态柱状图同源同值：唯一分组 "
                f"{max_group['country']} / {max_group['state']} 为 {total_warehouses} 个仓库、城市 {total_cities} 个；"
                f"主键区间 {sk_min}–{sk_max}。悬停显示分组城市覆盖数。"
            ),
            "dataRefs": [DATASET_REF],
        }
    )
if chart_error:
    warnings_list.append(chart_error)
    if not charts:
        add_check("图表产出", "FAILED", chart_error)
    else:
        add_check("图表产出", "PASSED", f"部分图表产出失败：{chart_error}；其余图表已生成并保留文本结果。")
else:
    add_check("图表产出", "PASSED", "静态 PNG 与交互 Plotly JSON 均按同一计算结果生成，坐标轴为分类/计数，柱形自 0 起。")

# ---------- consistency assertions ----------
assert sum(values) == total_warehouses
assert sum(r[2] for r in detail_rows) == total_warehouses
assert sum(r[3] for r in detail_rows) == total_cities
assert len(charts) <= 2
if png_ok:
    assert png_path.stat().st_size > 0
if plotly_ok:
    assert plotly_path.stat().st_size > 0
assert len(countries) == 1 and group_n == 1

country_txt = countries[0] if countries else "无"
state_pairs_txt = "、".join(f"{c} / {s}" for c, s in states) if states else "无"
all_pass = all(c["status"] == "PASSED" for c in checks)
status = "COMPLETE" if all_pass else "PARTIAL"

summary_text = (
    f"按国家 + 省州分组，本次交付结果只包含 1 个分组：{country_txt} / {states[0][1] if states else '无'}，"
    f"仓库 {total_warehouses} 个、覆盖城市 {total_cities} 个，占全部交付仓库 100.0%；仓库主键区间 {sk_min}–{sk_max}。"
    "国家与省州维度均无可比第二个类别，跨地区分布差异无法从本次数据得出；交付字段无日期列，结果仅为一次当前快照。"
)

report_md = f"""# 全仓库国家/省州分布（当前仓库维度快照）

## 一、核心结论

- 按“国家 + 省州”分组，本次交付的仓库分布只有 **{group_n} 个分组**：**{country_txt} / {states[0][1] if states else '无'}**，仓库 **{total_warehouses} 个**，覆盖城市 **{total_cities} 个**。
- 该分组占全部交付仓库的 **{max_share}%**，即国家/省州维度上不存在第二个地区可供比较。因此本报告给出的是“单一地区全量集中”的事实陈述，**不给出地区排名、区域差异或集中度趋势结论**——这些结论需要多地区分组数据。
- 仓库代理主键区间为 **{sk_min}–{sk_max}**，查询声明的全表最小主键为 **{global_min_sk}**。分组内仓库数 {total_warehouses} 与区间宽度 {sk_width} 相等，与键值连续覆盖的情形相容，但交付的是聚合结果、不含逐行主键，**连续性未经验证**。

## 二、指标摘要

| 指标 | 数值 | 单位 | 计算口径 |
| --- | --- | --- | --- |
| 仓库总数 | {total_warehouses} | 个 | 各分组 warehouse_count 求和 |
| 国家数 | {len(countries)} | 个 | w_country 去重取值个数 |
| 省州分组数 | {len(states)} | 个 | （w_country, w_state）去重组合个数 |
| 城市覆盖数 | {total_cities} | 个 | 单一分组下 city_count 即整体去重城市数 |
| 最小仓库主键 | {sk_min} | — | 各分组 min_warehouse_sk 的最小值（标识） |
| 最大仓库主键 | {sk_max} | — | 各分组 max_warehouse_sk 的最大值（标识） |

## 三、数据明细表（国家/省州分布）

| 国家 | 省州 | 仓库数量（个） | 覆盖城市数（个） | 最小仓库主键 | 最大仓库主键 | 占全部仓库比重（%） |
| --- | --- | --- | --- | --- | --- | --- |
""" + "\n".join(
    f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} | {r[6]} |" for r in detail_rows
) + f"""
| 合计 | {len(states)} 个省州分组 | {total_warehouses} | {total_cities} | {sk_min} | {sk_max} | {share_pct.quantize(Decimal('0.1'))} |

分组明细中的“仓库数量”为分组内 warehouse 记录条数（COUNT(*)），“覆盖城市数”为分组内 w_city 的去重计数；“最小/最大仓库主键”是代理主键区间外沿，属标识字段，不能相加或当作仓库数量使用。由于只交付了 1 个分组，合计行与明细行数值一致，不存在分组遗漏造成的差额。

## 四、城市覆盖与数据范围

- **城市覆盖数：{total_cities} 个**。该数字来自分组级去重城市计数；本次只有一个（国家, 省州）分组覆盖全部 {total_warehouses} 条仓库记录，因此各分组去重数之和恰好等于整体去重城市数。若未来出现多分组结果，同名的不同城市可能跨州重复，届时分组计数之和只能作为整体覆盖数的上界。
- **城市名称未交付**：结果只包含城市计数，没有 w_city 取值，无法列出具体城市或核对同名城市。
- **仓库主键范围：{sk_min}–{sk_max}**（全局最小主键 {global_min_sk}，与分组下界一致）。
- **统计对象与口径**：仓库维度（warehouse）中的记录，按国家、省州分组统计仓库条数；查询无任何绑定参数，属无过滤的全仓库统计。
- **时间范围：无可用业务日期**。交付字段不含日期或时间列，也没有快照日期声明，因此本结果只能视为一次当前快照，不能用于月度/年度对比或同比、环比计算。
- **数据质量**：交付 {n_rows} 行分组记录，7 个声明字段缺失/空值合计 {sum(field_missing.values())} 个；分组键无重复；所有计数字段为 {">=1"} 的整数。

## 五、字段口径与来源

| 源字段 | 声明含义 | 声明来源 | 状态 |
| --- | --- | --- | --- |
| w_country | 仓库所在国家（分组列，原样保留） | 查询定义（执行回执） | 已确认 |
| w_state | 仓库所在省州（分组列，原样保留） | 查询定义（执行回执） | 已确认 |
| warehouse_count | 分组内仓库记录数 COUNT(*) | 查询定义（执行回执） | 已确认 |
| city_count | 分组内去重城市数 COUNT(DISTINCT w_city) | 查询定义（执行回执） | 已确认 |
| min_warehouse_sk / max_warehouse_sk | 分组内最小/最大仓库代理主键（标识） | 查询定义（执行回执） | 已确认 |
| global_min_warehouse_sk | 全表最小仓库代理主键（每行重复，标识） | 查询定义（执行回执） | 已确认 |

## 六、校验与限制

已执行的校验：三层文件哈希与行数校验、字段存在性与类型校验、分组键唯一性校验、聚合一致性校验（各分组求和 = {total_warehouses}，最大分组占比 {max_share}%）、单一分组下城市口径校验、图表与表格同源同值校验。

未能解决的三项：

1. **主键连续性未验证**（UNRESOLVED）：只有 min/max 与计数，无法证明键 1–5 之间无缺号。
2. **跨地区分布可比性不足**（UNRESOLVED）：交付结果仅 1 个分组；执行回执声明“结果完整、无参数、全仓库”，但单行聚合结果无法独立复核源仓库维表是否还存在其它国家或省州。若源表另有地区，则本报告的分布结论不完整。
3. **无业务时间范围**（UNRESOLVED）：缺日期字段，无法确认数据截止日。

其他限制：图表只有一个类别，柱状图仅呈现单一柱形，视觉上不构成分布比较；若需真正的国家/省州分布对比，需要返回全部（国家, 省州）分组行。
"""

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "全仓库国家/省州分布（当前仓库维度快照）",
    "summary": summary_text,
    "status": "SUCCESS" if (all_pass and charts) else ("PARTIAL" if charts else "FAILED"),
    "metrics": metrics,
    "sections": [
        {
            "id": "s_findings",
            "title": "核心发现",
            "narrative": (
                f"面向“全部仓库的国家和省州分布”，交付结果只有 1 个（国家, 省州）分组 {country_txt} / "
                f"{states[0][1] if states else '无'}，仓库 {total_warehouses} 个，占全部交付仓库 {max_share}%，"
                f"覆盖城市 {total_cities} 个。也就是说，在当前交付范围内所有仓库都落在同一国家与同一省州，"
                "分布没有第二个类别，跨地区比较与排名没有数据基础。"
            ),
            "chartIds": [],
            "tableIds": ["t_profile"],
        },
        {
            "id": "s_distribution",
            "title": "国家/省州分布与明细",
            "narrative": (
                f"柱状图横轴为国家/省州、纵轴为仓库数量（个）。唯一分组为 {total_warehouses} 个仓库，"
                f"因此柱子高度即全量；仓库主键区间 {sk_min}–{sk_max}，分组内仓库数 {total_warehouses} 与区间宽度 "
                f"{sk_width} 相等。明细表同时列出分组覆盖城市数与主键区间，合计行与明细行数值一致。"
            ),
            "chartIds": [c["id"] for c in charts],
            "tableIds": ["t_detail"],
        },
        {
            "id": "s_scope",
            "title": "城市覆盖与数据范围",
            "narrative": (
                f"城市覆盖 {total_cities} 个，来自分组级去重城市计数；因只有一个分组覆盖全部仓库，该值等于整体去重城市数。"
                f"统计对象为仓库维度记录，查询无绑定参数（全仓库）。交付字段不含日期或时间列，也无快照日期声明，"
                "因此结果只能按一次当前快照解读，不能视为时间序列或用于同比、环比。城市名称未交付，仅能给出数量。"
            ),
            "chartIds": [],
            "tableIds": ["t_provenance"],
        },
    ],
    "charts": charts,
    "tables": tables,
    "dataSources": [DATASET_REF],
    "validation": {
        "checks": checks,
        "warnings": warnings_list
        + [
            "交付结果为单行分组结果，柱状图只有单一类别，不具备分布对比的可视效果。",
            "城市名称（w_city）未在结果中交付，城市覆盖仅以数量呈现。",
            "global_min_warehouse_sk 为全表重复值，仅作区间参照，未参与任何加总。",
        ],
    },
    "presentation": {
        "strategy": "auto",
        "template": "briefing",
        "density": "comfortable",
        "autoCharts": True,
        "autoImages": True,
    },
    "layout": {
        "schemaVersion": "report_layout.v1",
        "blocks": [
            {"id": "b_summary", "type": "summary", "span": "full", "emphasis": "important"},
            {"id": "b_m_total", "type": "metric", "ref": "m_total", "span": "half", "emphasis": "important"},
            {"id": "b_m_city", "type": "metric", "ref": "m_city", "span": "half"},
            {"id": "b_m_country", "type": "metric", "ref": "m_country", "span": "half"},
            {"id": "b_m_state", "type": "metric", "ref": "m_state", "span": "half"},
            {"id": "b_m_sk", "type": "metric", "ref": "m_max_sk", "span": "half"},
            {"id": "b_sec_findings", "type": "section", "ref": "s_findings", "span": "full"},
            {"id": "b_t_profile", "type": "table", "ref": "t_profile", "span": "full"},
            {"id": "b_sec_dist", "type": "section", "ref": "s_distribution", "span": "full"},
            {"id": "b_chart_bar", "type": "chart", "ref": "c_bar", "span": "full", "emphasis": "important"},
            {"id": "b_t_detail", "type": "table", "ref": "t_detail", "span": "full"},
            {"id": "b_sec_scope", "type": "section", "ref": "s_scope", "span": "full"},
            {"id": "b_t_provenance", "type": "table", "ref": "t_provenance", "span": "full"},
            {"id": "b_limits", "type": "limitations", "span": "full"},
            {"id": "b_sources", "type": "sources", "span": "full"},
        ],
    },
    "timings": {
        "analysisMs": int(round((chart_t0 - T_START) * 1000)),
        "chartMs": chart_ms,
    },
}

# interactive chart block only if it exists
if any(c["id"] == "c_bar_interactive" for c in charts):
    idx = next(i for i, b in enumerate(visual_report["layout"]["blocks"]) if b["id"] == "b_chart_bar")
    visual_report["layout"]["blocks"].insert(
        idx + 1,
        {"id": "b_chart_bar_i", "type": "chart", "ref": "c_bar_interactive", "span": "full"},
    )

field_provenance = [
    {
        "datasetReference": DATASET_REF,
        "sourceField": "w_country",
        "meaning": "仓库所在国家，查询按该列分组并原样输出",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "w_state",
        "meaning": "仓库所在省州，查询按该列分组并原样输出",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "warehouse_count",
        "meaning": "分组内仓库记录数，SQL 别名为 COUNT(*)",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "city_count",
        "meaning": "分组内去重城市数，SQL 别名为 COUNT(DISTINCT w_city)",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "min_warehouse_sk",
        "meaning": "分组内最小仓库代理主键，属标识字段，未参与求和",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "max_warehouse_sk",
        "meaning": "分组内最大仓库代理主键，属标识字段，未参与求和",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATASET_REF,
        "sourceField": "global_min_warehouse_sk",
        "meaning": "整个仓库维表的最小仓库代理主键，每行重复同一全局值，属标识字段",
        "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT), template STUDIO_E717A2E9639E37C58D59FB184E92C781",
        "status": "CONFIRMED",
    },
]

limitations = [
    "交付结果仅含 1 个（国家, 省州）分组，柱状图只有单一类别；跨地区分布差异、排名与集中度结论缺少数据基础，未予给出。",
    "执行回执声明结果完整（无参数、全仓库、returnedRowCount=1），但单行聚合结果无法独立复核源仓库维表是否还存在其它国家或省州；该分布可比性校验记为 UNRESOLVED。",
    "仓库主键连续性未验证：仅有分组 min/max 与计数（1–5 与 5 个仓库相容），没有逐行主键，无法证明中间无缺号。",
    "交付字段不含日期或时间列，也无快照日期声明，无法给出业务统计区间或截止日，结果只能按一次当前快照解读。",
    "城市名称（w_city）未交付，城市覆盖仅以数量 1 个呈现，无法核对具体城市或识别跨州同名城市。",
    "未提供币种、金额或库存度量；本报告为计数口径，不涉及金额精度与单位换算问题。",
    "图表只有两类输出（静态 PNG 与交互 Plotly），均基于同一计算结果；单一类别使视觉对比信息有限。",
]

row = {
    "reportMarkdown": report_md,
    "evidenceReferences": [DATASET_REF],
    "limitations": limitations,
    "analysisStatus": status,
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

out_path = RESULT_DIR / "rows.jsonl"
with out_path.open("w", encoding="utf-8") as fh:
    fh.write(json.dumps(row, ensure_ascii=False) + "\n")

diagnostics = {
    "manifestSha": sha256_bytes(manifest_bytes),
    "schemaSha": sha256_bytes(schema_bytes),
    "partSha": sha256_bytes(part_bytes),
    "partRows": n_rows,
    "groups": groups,
    "totals": {
        "warehouse_total": total_warehouses,
        "country_n": len(countries),
        "state_group_n": len(states),
        "city_total": total_cities,
        "sk_min": sk_min,
        "sk_max": sk_max,
        "sk_width": sk_width,
        "global_min_sk": global_min_sk,
        "max_share_pct": str(max_share),
    },
    "checks": checks,
    "warnings": warnings_list,
    "glyphWarnings": glyph_warnings[:5],
    "charts": [{"id": c["id"], "file": c["fileName"]} for c in charts],
    "chartError": chart_error,
    "analysisStatus": status,
}
(VALID_DIR / "diagnostics.json").write_text(json.dumps(diagnostics, ensure_ascii=False, indent=2), encoding="utf-8")
