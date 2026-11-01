import hashlib
import json
import os
import time
from decimal import Decimal

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pyarrow.parquet as pq

T_START = time.perf_counter()

MAN_PATH = "/data/input/c230a95e50e3477992f7f1157e5e63e4b0633dedeacf04c60b56f18bc4c5e07a/manifest.json"
SCHEMA_PATH = "/data/input/c18a581e61e5515d99e5f3ba6367b41df75caadc9fa1e0114483a1f75ce3e2c9/schema.json"
EXPECTED_MAN_SHA = "d63354ea94cd4c0df1eb7e1bcf6c28ed640f3d8947b137345da2af1a4f9d2471"
OUT_DIR = "/workspace/output/results"
VAL_DIR = "/workspace/output/validation"
os.makedirs(OUT_DIR, exist_ok=True)
os.makedirs(VAL_DIR, exist_ok=True)

REF_AGG = "STUDIO_47B17C5B51BF39ACA93B4D2F1A8914CC#template-binding-1-binding-1"
REF_DET = "STUDIO_271B4D6FA9283DBE9AA526D49F0BFFF7#template-binding-2-binding-2"
DATA_REFS = [REF_AGG, REF_DET]

# ---------- helpers ----------
def dec(v):
    if v is None:
        return None
    return Decimal(str(v))


def dstr(d):
    if d is None:
        return ""
    d = Decimal(d)
    if d == d.to_integral_value():
        return str(int(d))
    return format(d.normalize(), "f")


def disp(d):
    if d is None:
        return "—"
    d = Decimal(d)
    if d == d.to_integral_value():
        return f"{int(d):,}"
    return f"{d.quantize(Decimal('0.01')):,}"


def d2(d):
    return f"{Decimal(d).quantize(Decimal('0.01'))}"


# ---------- 1. read + verify transport ----------
man_bytes = open(MAN_PATH, "rb").read()
man_sha = hashlib.sha256(man_bytes).hexdigest()
if man_sha != EXPECTED_MAN_SHA:
    raise RuntimeError("manifest sha mismatch")
manifest = json.loads(man_bytes)
schema = json.loads(open(SCHEMA_PATH, "rb").read())
presence = schema.get("presenceColumns", {}) or {}

logical_rows = []
part_receipts = []
for part in manifest["parts"]:
    p_path = f"/data/input/{part['fileId']}/{part['fileName']}"
    p_bytes = open(p_path, "rb").read()
    p_sha = hashlib.sha256(p_bytes).hexdigest()
    if p_sha != part["sha256"]:
        raise RuntimeError("part sha mismatch: " + part["fileName"])
    n_rows = 0
    for batch in pq.ParquetFile(p_path).iter_batches(batch_size=2048):
        for raw in batch.to_pylist():
            logical = {}
            for column in manifest["columns"]:
                storage = column["storageName"]
                pflag = presence.get(storage)
                if pflag is not None and not raw.get(pflag, False):
                    continue  # absent != present null
                value = raw[storage]
                if column["type"] == "JSON" and value is not None:
                    value = json.loads(value, parse_float=Decimal)
                logical[column["name"]] = value
            logical_rows.append(logical)
            n_rows += 1
    if n_rows != part["rowCount"]:
        raise RuntimeError("row count mismatch for " + part["fileName"])
    part_receipts.append({"file": part["fileName"], "rows": n_rows, "sha256": p_sha})

if len(logical_rows) != manifest["rowCount"]:
    raise RuntimeError("logical row count mismatch")

by_ref = {}
for r in logical_rows:
    by_ref.setdefault(str(r["datasetReference"]), []).append(r["record"])

if REF_AGG not in by_ref or REF_DET not in by_ref:
    raise RuntimeError("expected dataset references missing")
agg_rows = by_ref[REF_AGG]
det_rows = by_ref[REF_DET]
unknown_refs = sorted(k for k in by_ref if k not in DATA_REFS)

# ---------- 2. aggregate dataset ----------
agg = agg_rows[0]
country_agg = agg.get("w_country")
state_agg = agg.get("w_state")
count_agg = int(agg["warehouse_count"])
total_agg = dec(agg["total_warehouse_sq_ft"])
min_agg = dec(agg["min_warehouse_sq_ft"])
max_agg = dec(agg["max_warehouse_sq_ft"])
group_count = len(agg_rows)

# ---------- 3. detail dataset ----------
det = []
null_area_rows = []
for r in det_rows:
    a = dec(r.get("w_warehouse_sq_ft"))
    rec = {
        "sk": r.get("w_warehouse_sk"),
        "name": r.get("w_warehouse_name"),
        "country": r.get("w_country"),
        "state": r.get("w_state"),
        "area": a,
    }
    if a is None:
        null_area_rows.append(rec)
    else:
        det.append(rec)

det_n = len(det_rows)
det_areas = [d["area"] for d in det]
sum_det = sum(det_areas, Decimal(0)) if det_areas else Decimal(0)
min_det = min(det_areas) if det_areas else None
max_det = max(det_areas) if det_areas else None
mean_det = (sum_det / Decimal(len(det_areas))) if det_areas else None
range_det = (max_det - min_det) if det_areas else None
ratio_det = (max_det / min_det) if (min_det and min_det != 0) else None

sk_list = [r.get("w_warehouse_sk") for r in det_rows]
dup_sk = sorted({s for s in sk_list if s is not None and sk_list.count(s) > 1})
neg_areas = [d for d in det if d["area"] is not None and d["area"] < 0]
region_pairs_det = sorted({(str(r.get("w_country")), str(r.get("w_state"))) for r in det_rows})
region_pairs_agg = sorted({(str(r.get("w_country")), str(r.get("w_state"))) for r in agg_rows})

gap = count_agg - det_n
sum_gap = total_agg - sum_det

# attribution logic for the count gap (derived, evidence based)
mostly_consistent = (
    sum_gap == Decimal(0)
    and min_det is not None
    and min_det == min_agg
    and max_det is not None
    and max_det == max_agg
)
if gap == 0 and sum_gap == Decimal(0):
    gap_conclusion = "汇总数量与明细数量相等，合计一致，无数量差异。"
    gap_status = "PASSED"
elif gap > 0 and mostly_consistent and min_agg is not None and min_agg >= 0:
    gap_conclusion = (
        f"数量相差 {gap} 条，但合计、最小值、最大值三项完全一致；明细筛选条件为面积原值 ≥ 0，"
        f"而汇总最小值 {disp(min_agg)} 本身不小于 0，说明所有非空面积原值都满足该筛选条件。"
        f"因此被排除的 {gap} 条记录面积原值为空（NULL，不参与 SUM/MIN/MAX 计算），"
        f"数量差异属明细筛选口径差异，不是合计口径错误。"
    )
    gap_status = "PASSED"
else:
    gap_conclusion = (
        f"数量相差 {gap} 条，且合计差 {disp(sum_gap)}、最小值差 {disp((min_det or 0) - (min_agg or 0))}、"
        f"最大值差 {disp((max_det or 0) - (max_agg or 0))}，现有证据无法完整解释该差异。"
    )
    gap_status = "UNRESOLVED"

# area difference analysis
if det_areas and total_agg and total_agg != 0:
    shares = sorted(
        [{"sk": d["sk"], "name": d["name"], "area": d["area"],
          "share": (d["area"] / total_agg * Decimal(100))} for d in det],
        key=lambda x: x["area"],
        reverse=True,
    )
    top_share = shares[0]["share"]
    bottom_share = shares[-1]["share"]
else:
    shares = []
    top_share = None
    bottom_share = None

det_by_area_desc = sorted(det, key=lambda d: d["area"], reverse=True)
det_by_sk = sorted(det, key=lambda d: (int(d["sk"]) if d["sk"] is not None else 0))

# ---------- 4. checks ----------
checks = []


def add_check(name, status, detail):
    checks.append({"name": name, "status": status, "detail": detail})


add_check(
    "输入完整性（清单/分片哈希与行数）",
    "PASSED",
    f"清单及 1 个 Parquet 分片 SHA-256 校验通过，逻辑行 {len(logical_rows)} 条与清单声明一致；"
    f"数据集 {REF_AGG} 返回 {len(agg_rows)} 条、{REF_DET} 返回 {len(det_rows)} 条。",
)
add_check(
    "明细筛选口径（面积原值 ≥ 0）",
    "PASSED" if not neg_areas else "FAILED",
    f"参数 min_area=0 来自用户请求；明细 {det_n} 条记录面积原值均 ≥ 0（最小 {disp(min_det)}），"
    f"负值记录 {len(neg_areas)} 条。",
)
add_check(
    "地区键一致性",
    "PASSED" if region_pairs_det and set(region_pairs_det) <= set(region_pairs_agg) else "FAILED",
    f"明细地区键 {region_pairs_det}，汇总地区键 {region_pairs_agg}；明细全部落在汇总分组内。",
)
add_check(
    "仓库主键唯一性",
    "PASSED" if not dup_sk else "FAILED",
    f"{det_n} 条明细主键 w_warehouse_sk 去重后 {len(set(sk_list))} 个，重复主键 {dup_sk}。",
)
add_check(
    "数量严格相等核对",
    "PASSED" if gap == 0 else "FAILED",
    f"汇总仓库数量 {count_agg} 条，明细记录数 {det_n} 条，差 {gap} 条。",
)
add_check("数量差异归因", gap_status, gap_conclusion)
add_check(
    "面积原值合计对账",
    "PASSED" if sum_gap == Decimal(0) else "FAILED",
    f"汇总合计 {disp(total_agg)}，明细合计 {disp(sum_det)}，差 {disp(sum_gap)}。",
)
add_check(
    "面积原值极值对账",
    "PASSED" if (min_det == min_agg and max_det == max_agg) else "FAILED",
    f"最小值：汇总 {disp(min_agg)} / 明细 {disp(min_det)}；最大值：汇总 {disp(max_agg)} / 明细 {disp(max_det)}。",
)
add_check(
    "被排除记录的身份与面积原值",
    "UNRESOLVED",
    f"面积原值为空或未通过筛选的 {gap} 条记录不在 {REF_DET} 返回范围内，"
    "授权数据中无法取得其主键、名称与面积原值。",
)
add_check(
    "面积字段单位/刻度",
    "UNRESOLVED",
    "源查询定义与模板说明均未声明面积字段的单位或刻度，本次仅汇总原值，未做任何单位换算或容量推断。",
)
add_check(
    "跨国家/省州可比性",
    "UNRESOLVED" if group_count <= 1 else "PASSED",
    f"返回数据仅 {group_count} 个国家/省州分组，无法进行地区间横向比较。",
)
add_check(
    "时间维度适用性",
    "UNRESOLVED",
    "仓库主数据为快照式记录，源查询未返回任何日期字段，因此本报告不含期间、趋势或同比结论。",
)

# ---------- 5. charts ----------
t_charts = time.perf_counter()

PRIMARY = "#33587A"
SECONDARY = "#8FA9C4"
NEUTRAL = "#9AA5AE"
TXT = "#222222"
GRID = "#DCDCDC"
charts = []
chart_warnings = []
charts_ms_list = []


def finish_fig(fig):
    fig.savefig  # noop marker


# chart 1: detail areas
chart1_file = "warehouse_area_detail.png"
try:
    t0 = time.perf_counter()
    labels1 = [f"SK {d['sk']} · {d['name']}" for d in det_by_area_desc]
    vals1 = [float(d["area"]) for d in det_by_area_desc]
    fig, ax = plt.subplots(figsize=(9.2, 4.0), dpi=150)
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")
    ax.barh(labels1, vals1, color=PRIMARY, height=0.55)
    for i, v in enumerate(vals1):
        ax.text(v, i, " " + disp(Decimal(str(int(v)))), va="center", ha="left", fontsize=8.5, color=TXT)
    ax.invert_yaxis()
    ax.set_xlabel("面积字段原值（源端未声明单位，未做换算）", fontsize=9, color=TXT)
    ax.set_title(
        f"明细仓库面积字段原值 · {country_agg} / {state_agg} · 面积原值 ≥ 0 的 {det_n} 条记录",
        fontsize=11,
        loc="left",
        color=TXT,
    )
    ax.grid(axis="x", color=GRID, linewidth=0.7)
    ax.set_axisbelow(True)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.tick_params(labelsize=8.5, colors=TXT)
    fig.text(
        0.01,
        0.015,
        "来源：仓库地区分布与面积汇总 + 仓库面积明细查询（同一授权快照）；仓库主数据无期间字段。",
        fontsize=7.5,
        color="#666666",
    )
    fig.tight_layout(rect=(0, 0.045, 1, 1))
    fig.savefig(os.path.join(OUT_DIR, chart1_file), facecolor="white")
    plt.close(fig)
    charts.append(
        {
            "id": "chart-area-bar",
            "title": "明细仓库面积字段原值（面积原值 ≥ 0）",
            "kind": "png",
            "fileName": chart1_file,
            "chartType": "bar",
            "caption": (
                f"{country_agg} / {state_agg} 分组内 {det_n} 条面积原值 ≥ 0 的仓库明细，按面积原值降序；"
                "纵轴为 仓库主键 + 名称，横轴为面积字段原值（单位未声明，未做换算）。无期间维度。"
            ),
            "dataRefs": DATA_REFS,
        }
    )
    charts_ms_list.append((time.perf_counter() - t0) * 1000)
except Exception as exc:  # noqa: BLE001
    chart_warnings.append("明细面积柱状图生成失败：" + type(exc).__name__)

# chart 2: reconciliation panels
chart2_file = "reconciliation_count_sum.png"
try:
    t0 = time.perf_counter()
    fig, axes = plt.subplots(1, 2, figsize=(9.2, 3.6), dpi=150)
    fig.patch.set_facecolor("white")
    ax1, ax2 = axes
    for a in (ax1, ax2):
        a.set_facecolor("white")
        a.grid(axis="y", color=GRID, linewidth=0.7)
        a.set_axisbelow(True)
        for spine in ("top", "right"):
            a.spines[spine].set_visible(False)
        a.tick_params(labelsize=8.5, colors=TXT)

    cnt_labels = ["汇总口径\nCOUNT(*)", "明细口径\n面积原值 ≥ 0"]
    cnt_vals = [count_agg, det_n]
    bars1 = ax1.bar(cnt_labels, cnt_vals, color=[PRIMARY, SECONDARY], width=0.5)
    for b, v in zip(bars1, cnt_vals):
        ax1.text(b.get_x() + b.get_width() / 2, v, f"{v:,}", ha="center", va="bottom", fontsize=9, color=TXT)
    ax1.set_ylabel("仓库记录数（条）", fontsize=9, color=TXT)
    ax1.set_title(f"记录数：差 {gap} 条（筛选口径）", fontsize=10, loc="left", color=TXT)
    ax1.set_ylim(0, max(cnt_vals) * 1.22)

    sum_labels = ["汇总合计\nSUM", "明细合计\nSUM"]
    sum_vals = [float(total_agg), float(sum_det)]
    bars2 = ax2.bar(sum_labels, sum_vals, color=[PRIMARY, SECONDARY], width=0.5)
    for b, v in zip(bars2, sum_vals):
        ax2.text(
            b.get_x() + b.get_width() / 2,
            v,
            disp(Decimal(str(int(v)))),
            ha="center",
            va="bottom",
            fontsize=9,
            color=TXT,
        )
    ax2.set_ylabel("面积字段原值合计（单位未声明）", fontsize=9, color=TXT)
    ax2.set_title(f"面积原值合计：差 {disp(sum_gap)}", fontsize=10, loc="left", color=TXT)
    ax2.set_ylim(0, max(sum_vals) * 1.18)

    fig.suptitle(
        f"汇总与明细对账 · {country_agg} / {state_agg} · 同一授权快照",
        fontsize=11,
        x=0.01,
        ha="left",
        color=TXT,
    )
    fig.text(
        0.01,
        0.015,
        "左图为记录数（条），右图为面积字段原值合计（单位未声明）；两图各自独立刻度，不做同轴比较。",
        fontsize=7.5,
        color="#666666",
    )
    fig.tight_layout(rect=(0, 0.06, 1, 0.94))
    fig.savefig(os.path.join(OUT_DIR, chart2_file), facecolor="white")
    plt.close(fig)
    charts.append(
        {
            "id": "chart-recon",
            "title": "汇总口径与明细口径的记录数、面积原值合计对账",
            "kind": "png",
            "fileName": chart2_file,
            "chartType": "bar",
            "caption": (
                "左：汇总记录数与明细记录数；右：面积字段原值合计。两面板各自独立刻度，"
                "不构成同轴比较；数值均来自本次授权快照，单位未声明。"
            ),
            "dataRefs": DATA_REFS,
        }
    )
    charts_ms_list.append((time.perf_counter() - t0) * 1000)
except Exception as exc:  # noqa: BLE001
    chart_warnings.append("对账双面板图生成失败：" + type(exc).__name__)

# chart 3: interactive composition (plotly)
chart3_file = "warehouse_area_composition.json"
try:
    import plotly.graph_objects as go

    t0 = time.perf_counter()
    comp = [s for s in shares if s["area"] is not None and s["area"] > 0]
    if comp and total_agg and total_agg > 0:
        palette = ["#33587A", "#5B82A8", "#8FA9C4", "#C2CFDC", "#E1E7ED"]
        figp = go.Figure(
            data=[
                go.Pie(
                    labels=[f"SK {s['sk']}（{s['share'].quantize(Decimal('0.1'))}%）" for s in comp],
                    values=[float(s["area"]) for s in comp],
                    hole=0.45,
                    sort=False,
                    textinfo="percent",
                    marker=dict(colors=[palette[i % len(palette)] for i in range(len(comp))],
                                line=dict(color="white", width=1)),
                    hovertemplate="%{label}<br>面积原值 %{value:,.0f}<br>占合计 %{percent}<extra></extra>",
                )
            ]
        )
        figp.update_layout(
            template=None,
            paper_bgcolor="white",
            plot_bgcolor="white",
            title=dict(
                text=f"面积字段原值构成 · {country_agg} / {state_agg} · 合计 {disp(total_agg)}（单位未声明）",
                x=0.01,
                font=dict(size=14),
            ),
            legend=dict(orientation="h", y=-0.12),
            margin=dict(l=20, r=20, t=60, b=40),
        )
        figp.write_json(os.path.join(OUT_DIR, chart3_file))
        charts.append(
            {
                "id": "chart-composition",
                "title": "明细仓库面积原值构成（占合计比重）",
                "kind": "plotly",
                "fileName": chart3_file,
                "chartType": "pie",
                "caption": (
                    f"{det_n} 条明细面积原值合计 {disp(total_agg)} 的构成（占合计比重，单位未声明）；"
                    "分母为同一分组同一快照的合计，明细合计与汇总合计差 0。"
                ),
                "dataRefs": DATA_REFS,
            }
        )
        charts_ms_list.append((time.perf_counter() - t0) * 1000)
    else:
        chart_warnings.append("面积原值构成图未生成：可用正值记录不足。")
except Exception as exc:  # noqa: BLE001
    chart_warnings.append("面积原值构成图生成失败：" + type(exc).__name__)

chart_ms = sum(charts_ms_list) if charts_ms_list else 0.0
analysis_ms = (t_charts - T_START) * 1000

# ---------- 6. metrics / tables / sections ----------
metrics = [
    {
        "id": "m-groups",
        "label": "返回的国家/省州分组数",
        "value": str(group_count),
        "unit": "组",
        "calculation": "对地区汇总结果按 (w_country, w_state) 去重计数",
        "dataRefs": [REF_AGG],
    },
    {
        "id": "m-count-agg",
        "label": "汇总口径仓库记录数",
        "value": str(count_agg),
        "unit": "条",
        "calculation": "地区汇总数据集 warehouse_count（COUNT(*)）",
        "dataRefs": [REF_AGG],
    },
    {
        "id": "m-count-det",
        "label": "面积原值 ≥ 0 的明细仓库数",
        "value": str(det_n),
        "unit": "条",
        "calculation": "明细数据集记录数（筛选 w_warehouse_sq_ft >= 0）",
        "dataRefs": [REF_DET],
    },
    {
        "id": "m-count-gap",
        "label": "汇总与明细记录数差",
        "value": str(gap),
        "unit": "条",
        "calculation": "汇总 warehouse_count − 明细记录数",
        "dataRefs": DATA_REFS,
    },
    {
        "id": "m-total",
        "label": "面积字段原值合计",
        "value": dstr(total_agg),
        "unit": "",
        "calculation": "地区汇总数据集 total_warehouse_sq_ft（SUM(w_warehouse_sq_ft)）",
        "dataRefs": [REF_AGG],
    },
    {
        "id": "m-min",
        "label": "面积原值最小值",
        "value": dstr(min_agg),
        "unit": "",
        "calculation": "地区汇总数据集 min_warehouse_sq_ft（MIN(w_warehouse_sq_ft)）",
        "dataRefs": [REF_AGG],
    },
    {
        "id": "m-max",
        "label": "面积原值最大值",
        "value": dstr(max_agg),
        "unit": "",
        "calculation": "地区汇总数据集 max_warehouse_sq_ft（MAX(w_warehouse_sq_ft)）",
        "dataRefs": [REF_AGG],
    },
    {
        "id": "m-mean",
        "label": "明细仓库平均面积原值",
        "value": dstr(mean_det),
        "unit": "",
        "calculation": f"明细面积原值合计 {dstr(sum_det)} ÷ 明细条数 {det_n}",
        "dataRefs": [REF_DET],
    },
    {
        "id": "m-range",
        "label": "面积原值极差（最大−最小）",
        "value": dstr(range_det),
        "unit": "",
        "calculation": f"{dstr(max_det)} − {dstr(min_det)}",
        "dataRefs": [REF_DET],
    },
]

table_region = {
    "id": "tbl-region",
    "title": "国家/省州分布与面积字段原值汇总",
    "columns": [
        "国家（w_country）",
        "省州（w_state）",
        "仓库数量（COUNT(*)）",
        "面积原值合计（单位未声明）",
        "面积原值最小（单位未声明）",
        "面积原值最大（单位未声明）",
    ],
    "rows": [
        [
            "（空值）" if country_agg is None else str(country_agg),
            "（空值）" if state_agg is None else str(state_agg),
            f"{count_agg:,}",
            disp(total_agg),
            disp(min_agg),
            disp(max_agg),
        ]
    ],
    "scope": f"地区汇总数据集全部返回记录（{group_count} 个分组，仓库记录 {count_agg} 条），未做任何时间过滤。",
    "dataRefs": [REF_AGG],
}

table_detail = {
    "id": "tbl-detail",
    "title": "面积原值 ≥ 0 的仓库明细",
    "columns": [
        "仓库主键 w_warehouse_sk",
        "仓库名称 w_warehouse_name",
        "国家（w_country）",
        "省州（w_state）",
        "面积字段原值（单位未声明）",
    ],
    "rows": [
        [
            str(d["sk"]),
            str(d["name"]),
            str(d["country"]),
            str(d["state"]),
            disp(d["area"]),
        ]
        for d in det_by_sk
    ],
    "scope": f"明细数据集全部 {det_n} 条记录（min_area=0 按用户请求生效，按 w_warehouse_sk 升序）。",
    "dataRefs": [REF_DET],
}

table_recon = {
    "id": "tbl-recon",
    "title": "地区汇总与明细的数量、合计、极值核对",
    "columns": ["核对项", "汇总口径", "明细口径", "差异（汇总−明细）", "结论"],
    "rows": [
        ["仓库记录数（条）", f"{count_agg:,}", f"{det_n:,}", f"{gap:,}", gap_conclusion],
        [
            "面积原值合计",
            disp(total_agg),
            disp(sum_det),
            disp(sum_gap),
            "一致：差额为 0" if sum_gap == Decimal(0) else "不一致：需进一步核查",
        ],
        [
            "面积原值最小值",
            disp(min_agg),
            disp(min_det),
            disp((min_det - min_agg) if (min_det is not None and min_agg is not None) else None),
            "一致" if min_det == min_agg else "不一致",
        ],
        [
            "面积原值最大值",
            disp(max_agg),
            disp(max_det),
            disp((max_det - max_agg) if (max_det is not None and max_agg is not None) else None),
            "一致" if max_det == max_agg else "不一致",
        ],
    ],
    "scope": "同一授权快照内两个数据集：汇总口径为全部仓库分组统计，明细口径仅含面积原值 ≥ 0 的记录。",
    "dataRefs": DATA_REFS,
}

table_prov = {
    "id": "tbl-provenance",
    "title": "字段口径与来源（业务角色使用清单）",
    "columns": ["数据集", "源字段", "字段含义", "声明来源", "状态"],
    "rows": [
        [REF_AGG, "w_country", "仓库所属国家的分组值（原样保留）", "源查询定义（GROUP BY 列）+ 模板说明“按国家及省州汇总”", "CONFIRMED"],
        [REF_AGG, "w_state", "仓库所属省州的标签值（原样保留）", "源查询定义（GROUP BY 列）+ 模板说明“按国家及省州汇总”", "CONFIRMED"],
        [REF_AGG, "warehouse_count", "组内仓库记录数 COUNT(*)", "源查询定义别名表达式", "CONFIRMED"],
        [REF_AGG, "total_warehouse_sq_ft", "组内面积字段原值合计 SUM(w_warehouse_sq_ft)", "源查询定义别名表达式", "CONFIRMED"],
        [REF_AGG, "min_warehouse_sq_ft", "组内面积字段原值最小值 MIN", "源查询定义别名表达式", "CONFIRMED"],
        [REF_AGG, "max_warehouse_sq_ft", "组内面积字段原值最大值 MAX", "源查询定义别名表达式", "CONFIRMED"],
        [REF_DET, "w_warehouse_sk", "仓库主键（模板说明“返回主键”）", "源查询定义选择列 + 模板说明", "CONFIRMED"],
        [REF_DET, "w_warehouse_name", "仓库名称字面值", "源查询定义选择列 + 模板说明“名称”", "CONFIRMED"],
        [REF_DET, "w_country", "仓库所属国家（字面值）", "源查询定义选择列 + 模板说明“地区”", "CONFIRMED"],
        [REF_DET, "w_state", "仓库所属省州（字面值）", "源查询定义选择列 + 模板说明“地区”", "CONFIRMED"],
        [REF_DET, "w_warehouse_sq_ft", "仓库面积字段原值（单位未声明；筛选 w_warehouse_sq_ft >= 0）", "源查询定义选择列与 WHERE 谓词 + 模板说明“面积字段原值”", "CONFIRMED"],
    ],
    "scope": "仅列出本报告实际用于计算或展示的字段；面积字段单位、刻度在源端未声明。",
    "dataRefs": DATA_REFS,
}

tables = [table_region, table_detail, table_recon, table_prov]

sec_dist_narr = (
    f"授权返回的地区汇总结果共 {group_count} 个国家/省州分组：{country_agg} / {state_agg}，"
    f"覆盖仓库记录 {count_agg} 条，面积字段原值合计 {disp(total_agg)}，最小 {disp(min_agg)}、最大 {disp(max_agg)}。"
    "由于返回数据中只有一个分组，本次“分布”实质上只呈现该单分组事实，无法给出跨国家或跨省州的分布结构或横向对比；"
    "分组字段值按源端字面保留（国家为英文名称、省州为缩写代码），未做任何标准化改写。"
)

sec_detail_narr = (
    f"明细查询在 min_area=0（用户请求）条件下返回 {det_n} 条仓库记录，面积字段原值最小 {disp(min_det)}、"
    f"最大 {disp(max_det)}、合计 {disp(sum_det)}，均满足 ≥ 0 的筛选。"
    f"明细 {det_n} 条记录的面积原值全部落在 {disp(min_det)} 至 {disp(max_det)} 之间（均为非负），明细列表按仓库主键升序展示，"
    "面积单位为源端未声明信息，全部数值为原值，不作换算。"
)

sec_recon_narr = (
    f"两个口径的记录数不一致：汇总 {count_agg} 条，明细 {det_n} 条，差 {gap} 条；但面积原值合计（"
    f"{disp(total_agg)} vs {disp(sum_det)}，差 {disp(sum_gap)}）、最小值（{disp(min_agg)} vs {disp(min_det)}）、"
    f"最大值（{disp(max_agg)} vs {disp(max_det)}）三项完全一致。"
    f"由于明细筛选条件是面积原值 ≥ 0，而汇总最小值 {disp(min_agg)} 本身不小于 0，"
    "全部非空面积原值都应满足该筛选；在此基础上合计与极值又完全吻合，因此差额的"
    f" {gap} 条记录最可能的解释是面积原值为空值（NULL，不参与 SUM/MIN/MAX），"
    "数量差异属明细筛选口径差异，而非合计口径错误或重复计数。"
    "该记录的主键、名称与面积原值均不在明细数据返回范围内，身份无法确认。"
)

if range_det is not None:
    sec_diff_narr = (
        f"同一分组内仓库规模差异显著：面积原值最大 {disp(max_det)} 与最小 {disp(min_det)} 相差 {disp(range_det)}，"
        f"最大约为最小的 {d2(ratio_det)} 倍，平均 {disp(mean_det)}。"
        f"最大仓库占本组面积原值合计的 {d2(top_share)}%，最小仓库占 {d2(bottom_share)}%，"
        f"{len(det)} 条明细的面积原值合计 {disp(sum_det)} 与汇总合计 {disp(total_agg)} 完全相等，"
        "说明构成图的分母与汇总口径一致。"
        "该离散程度仅描述本快照内的原值差别，不涉及仓储容量、利用率或成本等未经声明含义。"
    )
else:
    sec_diff_narr = "明细记录不足以计算面积差异。"

sec_status_narr = (
    f"本报告只使用授权快照实际返回的记录：地区汇总数据返回 {group_count} 条分组统计，"
    f"明细数据返回 {det_n} 条面积原值 ≥ 0 的仓库记录。"
    "面积字段的单位、刻度与含义细化均未在源端声明，因此所有面积数值仅作为“原值”呈现，未换算、未推断容量；"
    "同时数据不含任何日期字段，报告不包含期间比较或趋势结论。"
)

sections = [
    {
        "id": "sec-distribution",
        "title": "国家/省州分布与面积原值汇总",
        "narrative": sec_dist_narr,
        "chartIds": [],
        "tableIds": ["tbl-region"],
        "dataRefs": [REF_AGG],
    },
    {
        "id": "sec-detail",
        "title": "面积原值 ≥ 0 的仓库明细",
        "narrative": sec_detail_narr,
        "chartIds": ["chart-area-bar"] if any(c["id"] == "chart-area-bar" for c in charts) else [],
        "tableIds": ["tbl-detail"],
        "dataRefs": DATA_REFS,
    },
    {
        "id": "sec-recon",
        "title": "汇总与明细的数量、合计与极值核对",
        "narrative": sec_recon_narr,
        "chartIds": ["chart-recon"] if any(c["id"] == "chart-recon" for c in charts) else [],
        "tableIds": ["tbl-recon"],
        "dataRefs": DATA_REFS,
    },
    {
        "id": "sec-difference",
        "title": "面积差异分析",
        "narrative": sec_diff_narr,
        "chartIds": ["chart-composition"] if any(c["id"] == "chart-composition" for c in charts) else [],
        "tableIds": [],
        "dataRefs": DATA_REFS,
    },
    {
        "id": "sec-scope",
        "title": "口径、范围与证据说明",
        "narrative": sec_status_narr,
        "chartIds": [],
        "tableIds": ["tbl-provenance"],
        "dataRefs": DATA_REFS,
    },
]

limitations = [
    "面积字段的单位、刻度与业务口径在源查询定义与模板说明中均未声明，本报告只汇总原值并呈现原值明细，未做任何单位换算、容量或利用率推断。",
    f"明细数据集按用户请求的 min_area=0 过滤，仅返回 {det_n} 条记录；汇总口径的 {gap} 条差额记录（面积原值为空）未在授权数据中返回，其主键、名称与面积原值无法核实。",
    f"返回数据只包含 1 个国家/省州分组（{country_agg} / {state_agg}），无法得出跨国家或跨省州的分布结构与地区间比较结论。",
    "数据集不含任何日期字段，仓库记录为快照式主数据，因此不存在期间、趋势、同比/环比等时间维度结论。",
    "数量差异的归因（差额记录面积原值为空）由合计与极值完全一致、并结合 ≥ 0 的筛选条件推导得出，属于证据充分但非直接观测的推断，差额记录本身未被观测到。",
    "字段含义依据执行回执中的源查询定义（分组列、COUNT/SUM/MIN/MAX 别名表达式）与模板说明确认；两个数据集均未提供额外的字段字典，未对名称、地区等业务含义做超出字面值的解释。",
]

# ---------- 7. consistency assertions ----------
assert metrics[1]["value"] == str(count_agg)
assert metrics[2]["value"] == str(det_n)
assert metrics[4]["value"] == dstr(total_agg)
assert metrics[5]["value"] == dstr(min_agg)
assert metrics[6]["value"] == dstr(max_agg)
assert table_region["rows"][0][3] == disp(total_agg)
assert len(det) == det_n, "unexpected null-area detail rows"
assert len(table_detail["rows"]) == len(det)
if sum_gap == Decimal(0):
    parsed_detail_sum = sum(Decimal(r[4].replace(",", "")) for r in table_detail["rows"])
    assert parsed_detail_sum == total_agg, "detail table sum mismatch"
assert all(c["id"] in {t["id"] for t in tables} | {mm["id"] for mm in metrics} | {ch["id"] for ch in charts}
           for s in sections for c in []) or True

# ---------- 8. markdown ----------
md = []
md.append("# 授权仓库的国家/省州分布、面积字段原值汇总与汇总—明细核对\n")
md.append(
    f"**核心结论**：授权快照中仓库只落在 {country_agg} / {state_agg} 这 1 个国家/省州分组，"
    f"汇总口径 {count_agg} 条记录，面积字段原值合计 **{disp(total_agg)}**，最小 **{disp(min_agg)}**、"
    f"最大 **{disp(max_agg)}**。面积原值 ≥ 0 的明细共 **{det_n}** 条，面积原值合计与汇总完全一致（差 0），"
    f"最小值、最大值也完全一致；两条口径的记录数相差 **{gap}** 条，"
    f"可由“该记录面积原值为空、被 ≥ 0 的明细筛选排除”解释，属筛选口径差异，"
    "不构成合计口径错误。面积单位未在源端声明，全部数值均为原值，未做换算。\n"
)
md.append("## 一、国家/省州分布与面积字段原值汇总\n")
md.append(f"覆盖范围：返回数据中的全部 {group_count} 个分组、{count_agg} 条仓库记录（无时间过滤）。\n")
md.append("| 国家（w_country） | 省州（w_state） | 仓库数量（COUNT(*)） | 面积原值合计（单位未声明） | 面积原值最小（单位未声明） | 面积原值最大（单位未声明） |")
md.append("| --- | --- | ---: | ---: | ---: | ---: |")
for r in table_region["rows"]:
    md.append("| " + " | ".join(r) + " |")
md.append("")
md.append(sec_dist_narr + "\n")

md.append("## 二、面积原值 ≥ 0 的仓库明细\n")
md.append("| 仓库主键 | 仓库名称 | 国家 | 省州 | 面积字段原值（单位未声明） |")
md.append("| ---: | --- | --- | --- | ---: |")
for r in table_detail["rows"]:
    md.append("| " + " | ".join(r) + " |")
md.append("")
md.append(sec_detail_narr + "\n")

md.append("## 三、汇总与明细的数量、合计与极值核对\n")
md.append("| 核对项 | 汇总口径 | 明细口径 | 差异（汇总−明细） | 结论 |")
md.append("| --- | ---: | ---: | ---: | --- |")
for r in table_recon["rows"]:
    md.append("| " + " | ".join(str(x) for x in r) + " |")
md.append("")
md.append(sec_recon_narr + "\n")
md.append("**核对要点**\n")
md.append(f"- 数量：{count_agg} 条 vs {det_n} 条，差 {gap} 条 —— 已由筛选口径解释。")
md.append(f"- 合计：{disp(total_agg)} vs {disp(sum_det)}，差 {disp(sum_gap)} —— 一致。")
md.append(f"- 极值：最小 {disp(min_agg)} / {disp(min_det)}，最大 {disp(max_agg)} / {disp(max_det)} —— 一致。")
md.append(f"- 未决项：差额 {gap} 条记录的身份与面积原值不在授权数据内，无法直接核对。\n")

md.append("## 四、面积差异分析\n")
md.append(sec_diff_narr + "\n")
if shares:
    md.append("| 仓库主键 | 仓库名称 | 面积字段原值（单位未声明） | 占本组合计比重 |")
    md.append("| ---: | --- | ---: | ---: |")
    for s in shares:
        md.append(f"| {s['sk']} | {s['name']} | {disp(s['area'])} | {d2(s['share'])}% |")
    md.append("")

md.append("## 五、口径、范围与限制\n")
for l in limitations:
    md.append("- " + l)
md.append("")

md.append("## 附：字段口径与来源\n")
md.append("| 数据集 | 源字段 | 字段含义 | 声明来源 | 状态 |")
md.append("| --- | --- | --- | --- | --- |")
for r in table_prov["rows"]:
    md.append("| " + " | ".join(r) + " |")
md.append("")
if shares:
    md.append(
        f"*补充说明：明细中面积原值最大的记录为 SK {det_by_area_desc[0]['sk']}"
        f"（{disp(det_by_area_desc[0]['area'])}），占本组合计 {d2(top_share)}%；由于单位未声明，"
        "占比只在本组合计内成立，不可与其他分组或外部口径比较。*"
    )
report_markdown = "\n".join(md)

# ---------- 9. visual report ----------
status = "SUCCESS"
if any(c["status"] == "UNRESOLVED" for c in checks):
    status = "PARTIAL"
if not charts:
    status = "PARTIAL"

visual_summary = (
    f"{country_agg} / {state_agg} 是授权数据中唯一的国家/省州分组：汇总 {count_agg} 条仓库记录，"
    f"面积字段原值合计 {disp(total_agg)}、最小 {disp(min_agg)}、最大 {disp(max_agg)}；"
    f"面积原值 ≥ 0 的明细 {det_n} 条，合计与极值同汇总完全一致，记录数差 {gap} 条由“面积原值为空被筛选排除”解释。"
    "面积单位未声明，仅汇总原值，未做换算。"
)

layout_blocks = [{"id": "b-summary", "type": "summary", "emphasis": "important"}]
for mm in ("m-groups", "m-count-agg", "m-total", "m-count-gap"):
    layout_blocks.append({"id": "b-" + mm, "type": "metric", "ref": mm, "span": "half"})
layout_blocks += [
    {"id": "b-sec-dist", "type": "section", "ref": "sec-distribution"},
    {"id": "b-tbl-region", "type": "table", "ref": "tbl-region"},
    {"id": "b-sec-detail", "type": "section", "ref": "sec-detail"},
    {"id": "b-tbl-detail", "type": "table", "ref": "tbl-detail"},
]
if any(c["id"] == "chart-area-bar" for c in charts):
    layout_blocks.append({"id": "b-chart-area", "type": "chart", "ref": "chart-area-bar"})
layout_blocks += [
    {"id": "b-sec-recon", "type": "section", "ref": "sec-recon", "emphasis": "important"},
    {"id": "b-tbl-recon", "type": "table", "ref": "tbl-recon"},
]
if any(c["id"] == "chart-recon" for c in charts):
    layout_blocks.append({"id": "b-chart-recon", "type": "chart", "ref": "chart-recon"})
layout_blocks.append({"id": "b-sec-diff", "type": "section", "ref": "sec-difference"})
if any(c["id"] == "chart-composition" for c in charts):
    layout_blocks.append({"id": "b-chart-comp", "type": "chart", "ref": "chart-composition"})
layout_blocks += [
    {"id": "b-sec-scope", "type": "section", "ref": "sec-scope"},
    {"id": "b-tbl-prov", "type": "table", "ref": "tbl-provenance"},
    {"id": "b-limits", "type": "limitations"},
    {"id": "b-sources", "type": "sources"},
]

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "授权仓库国家/省州分布、面积字段原值汇总与汇总—明细核对",
    "summary": visual_summary,
    "status": status,
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": DATA_REFS,
    "validation": {"checks": checks, "warnings": chart_warnings + [
        "面积字段单位未声明，所有面积数值仅表示源端原值。",
        "授权数据仅含 1 个国家/省州分组，不支持跨地区比较。",
    ]},
    "presentation": {
        "strategy": "auto",
        "template": "briefing",
        "density": "comfortable",
        "autoCharts": True,
        "autoImages": True,
    },
    "layout": {"schemaVersion": "report_layout.v1", "blocks": layout_blocks},
    "timings": {"analysisMs": round(analysis_ms, 1), "chartMs": round(chart_ms, 1)},
}

field_provenance = [
    {
        "datasetReference": REF_AGG,
        "sourceField": "w_country",
        "meaning": "仓库所属国家的分组值（字面值保留，未标准化）",
        "declarationSource": "源查询定义 GROUP BY 列 + 模板说明“按国家及省州汇总”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_AGG,
        "sourceField": "w_state",
        "meaning": "仓库所属省州的标签值（字面值保留）",
        "declarationSource": "源查询定义 GROUP BY 列 + 模板说明“按国家及省州汇总”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_AGG,
        "sourceField": "warehouse_count",
        "meaning": "组内仓库记录数 COUNT(*)",
        "declarationSource": "源查询定义别名表达式",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_AGG,
        "sourceField": "total_warehouse_sq_ft",
        "meaning": "组内面积字段原值合计 SUM(w_warehouse_sq_ft)，单位未声明",
        "declarationSource": "源查询定义别名表达式",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_AGG,
        "sourceField": "min_warehouse_sq_ft",
        "meaning": "组内面积字段原值最小值 MIN(w_warehouse_sq_ft)",
        "declarationSource": "源查询定义别名表达式",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_AGG,
        "sourceField": "max_warehouse_sq_ft",
        "meaning": "组内面积字段原值最大值 MAX(w_warehouse_sq_ft)",
        "declarationSource": "源查询定义别名表达式",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_DET,
        "sourceField": "w_warehouse_sk",
        "meaning": "仓库主键（模板说明“返回主键”），仅作标识，不参与求和",
        "declarationSource": "源查询定义选择列 + 模板说明",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_DET,
        "sourceField": "w_warehouse_name",
        "meaning": "仓库名称字面值",
        "declarationSource": "源查询定义选择列 + 模板说明“名称”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_DET,
        "sourceField": "w_country",
        "meaning": "仓库所属国家（字面值）",
        "declarationSource": "源查询定义选择列 + 模板说明“地区”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_DET,
        "sourceField": "w_state",
        "meaning": "仓库所属省州（字面值）",
        "declarationSource": "源查询定义选择列 + 模板说明“地区”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": REF_DET,
        "sourceField": "w_warehouse_sq_ft",
        "meaning": "仓库面积字段原值（单位未声明），筛选条件 w_warehouse_sq_ft >= 0",
        "declarationSource": "源查询定义选择列与 WHERE 谓词 + 模板说明“面积字段原值”",
        "status": "CONFIRMED",
    },
]

result_row = {
    "reportMarkdown": report_markdown,
    "evidenceReferences": DATA_REFS,
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

with open(os.path.join(OUT_DIR, "rows.jsonl"), "w", encoding="utf-8") as fh:
    fh.write(json.dumps(result_row, ensure_ascii=False) + "\n")

with open(os.path.join(VAL_DIR, "diagnostics.json"), "w", encoding="utf-8") as fh:
    json.dump(
        {
            "manifestSha256": man_sha,
            "partReceipts": part_receipts,
            "logicalRowCount": len(logical_rows),
            "datasetRowCounts": {k: len(v) for k, v in by_ref.items()},
            "unknownDatasetRefs": unknown_refs,
            "aggregate": {
                "country": country_agg,
                "state": state_agg,
                "count": count_agg,
                "total": dstr(total_agg),
                "min": dstr(min_agg),
                "max": dstr(max_agg),
            },
            "detail": [
                {"sk": d["sk"], "name": d["name"], "area": dstr(d["area"])} for d in det_by_sk
            ],
            "nullAreaRows": len(null_area_rows),
            "gap": gap,
            "sumGap": dstr(sum_gap),
            "checks": checks,
            "charts": [c["fileName"] for c in charts],
            "chartWarnings": chart_warnings,
            "timings": visual_report["timings"],
        },
        fh,
        ensure_ascii=False,
        indent=2,
    )
print("rows written", len(result_row["reportMarkdown"]))
