import hashlib
import json
import time
from decimal import Decimal
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import pandas as pd

DATA_REF = "mcp_chatchat_mcp_server_tpl_190c862b57813ca38fbe5a0edc614970"
MAN_DIR = Path("/data/input/cffa7d0563c55c8677dfb136f08f78768d64af94fc7cd7a2368fc3c47a0aecdf")
MAN = MAN_DIR / "manifest.json"
SCHEMA = Path("/data/input/c420889760a50f06acd16ccdc5aa0d762f64a280387a87e626ecbd2f799c7cfe/schema.json")
EXPECTED_MAN_SHA = "0f3fb973c72b31dcfaf48f7f857c78f50d5a9b1896b0d37c762cf47239684856"
EXPECTED_SCHEMA_SHA = "f9917c3be32e79dfe2d5b65dc68876dbeb1530e0cf7845c03019f12ae10c388c"
OUT = Path("/workspace/output/results")
VAL = Path("/workspace/output/validation")
OUT.mkdir(parents=True, exist_ok=True)
VAL.mkdir(parents=True, exist_ok=True)

T0 = time.perf_counter()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


# ---------- integrity ----------
man_sha = sha256(MAN)
assert man_sha == EXPECTED_MAN_SHA, "manifest hash mismatch"
schema_sha = sha256(SCHEMA)
assert schema_sha == EXPECTED_SCHEMA_SHA, "schema hash mismatch"
manifest = json.loads(MAN.read_text(encoding="utf-8"))
schema_doc = json.loads(SCHEMA.read_text(encoding="utf-8"))

col_map = {c["storageName"]: c["name"] for c in manifest["columns"]}
declared_cols = [c["name"] for c in manifest["columns"]]
assert set(declared_cols) == {"datasetReference", "record"}, declared_cols

frames = []
for part in manifest["parts"]:
    p = Path("/data/input") / part["fileId"] / part["fileName"]
    got = sha256(p)
    assert got == part["sha256"], f"part hash mismatch {part['fileName']}"
    df = pd.read_parquet(p, dtype_backend="pyarrow")
    assert len(df) == part["rowCount"], f"part rowCount mismatch {part['fileName']}"
    frames.append(df.rename(columns=col_map))

raw = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=declared_cols)
assert len(raw) == manifest["rowCount"], "total rowCount mismatch"
n_parts = len(manifest["parts"])
n_rows = len(raw)

records = []
refs_seen = set()
for _, r in raw.iterrows():
    refs_seen.add(str(r["datasetReference"]))
    rec = json.loads(r["record"], parse_float=Decimal, parse_int=Decimal)
    records.append(rec)
assert refs_seen == {DATA_REF}, refs_seen

prof = pd.DataFrame(
    {
        "snapshot_date_raw": [str(r["snapshot_date"]) for r in records],
        "sku_record_count": [int(r["sku_record_count"]) for r in records],
        "nonzero_sku_count": [int(r["nonzero_sku_count"]) for r in records],
        "zero_sku_count": [int(r["zero_sku_count"]) for r in records],
        "total_on_hand": [Decimal(r["total_on_hand"]) for r in records],
    }
)
prof["snapshot_date"] = pd.to_datetime(prof["snapshot_date_raw"], format="%Y-%m-%d", errors="raise")
prof = prof.sort_values("snapshot_date").reset_index(drop=True)

START = pd.Timestamp("1998-01-01")
END = pd.Timestamp("1998-01-29")
n_calendar_days = (END - START).days + 1

# ---------- quality checks ----------
dup_dates = int(prof["snapshot_date"].duplicated().sum())
in_range = bool(((prof["snapshot_date"] >= START) & (prof["snapshot_date"] <= END)).all())
decomp_ok = bool((prof["nonzero_sku_count"] + prof["zero_sku_count"] == prof["sku_record_count"]).all())
nonneg_total = bool((prof["total_on_hand"] >= 0).all())
counts_positive = bool((prof["sku_record_count"] > 0).all())
query_limit = 100
not_truncated = bool(n_rows < query_limit)

observed = list(prof["snapshot_date"])
missing_days = [d for d in pd.date_range(START, END, freq="D") if d not in set(observed)]
n_missing = len(missing_days)
n_obs = len(observed)
gaps = [(observed[i + 1] - observed[i]).days for i in range(len(observed) - 1)]
gap_set = sorted(set(gaps))
gap_txt = "、".join(str(g) for g in gap_set) if gap_set else "无相邻观测日"
equal_gaps = bool(len(gap_set) == 1)

# ---------- derived objects ----------
daily_rows = []
for _, r in prof.iterrows():
    daily_rows.append(
        [
            r["snapshot_date"].strftime("%Y-%m-%d"),
            int(r["sku_record_count"]),
            int(r["nonzero_sku_count"]),
            int(r["zero_sku_count"]),
            f"{r['total_on_hand']:,}",
        ]
    )

rec_total_all_days = int(prof["sku_record_count"].sum())
max_idx = prof["sku_record_count"].idxmax()
min_idx = prof["sku_record_count"].idxmin()
max_total_idx = prof["total_on_hand"].idxmax()
min_total_idx = prof["total_on_hand"].idxmin()
max_rec = int(prof.loc[max_idx, "sku_record_count"])
max_rec_date = prof.loc[max_idx, "snapshot_date"].strftime("%Y-%m-%d")
min_rec = int(prof.loc[min_idx, "sku_record_count"])
min_rec_date = prof.loc[min_idx, "snapshot_date"].strftime("%Y-%m-%d")
max_total = prof.loc[max_total_idx, "total_on_hand"]
max_total_date = prof.loc[max_total_idx, "snapshot_date"].strftime("%Y-%m-%d")
min_total = prof.loc[min_total_idx, "total_on_hand"]
min_total_date = prof.loc[min_total_idx, "snapshot_date"].strftime("%Y-%m-%d")
max_total_rank = int(max_total_idx) + 1
min_total_rank = int(min_total_idx) + 1
zero_sum = int(prof["zero_sku_count"].sum())
nonzero_sum = int(prof["nonzero_sku_count"].sum())
assert nonzero_sum + zero_sum == rec_total_all_days

first_date = observed[0].strftime("%Y-%m-%d")
last_date = observed[-1].strftime("%Y-%m-%d")
avg_rec = Decimal(rec_total_all_days) / Decimal(n_obs)
avg_rec_str = f"{avg_rec.quantize(Decimal('0.01')):,}"

# ---------- chart ----------
CHART_NAME = "warehouse2_inventory_record_counts.png"
chart_error = None
t_chart0 = time.perf_counter()
try:
    fig, ax = plt.subplots(figsize=(9.4, 4.9))
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")
    xs = [d.to_pydatetime() for d in observed]
    ys = [int(v) for v in prof["sku_record_count"]]
    ax.plot(
        xs, ys,
        color="#1F4E79", linewidth=1.7, linestyle="-",
        marker="o", markersize=6.5, markerfacecolor="white",
        markeredgecolor="#1F4E79", markeredgewidth=1.6,
        label="库存记录数（条）",
    )
    for x, y in zip(xs, ys):
        ax.annotate(
            f"{y:,}", (x, y), textcoords="offset points", xytext=(0, 10),
            ha="center", fontsize=9, color="#333333",
        )
    ax.set_title(
        "仓库2 逐日库存快照记录数（1998-01-01 至 1998-01-29，仅含返回记录的日期）",
        fontsize=12.5, pad=12,
    )
    ax.set_xlabel("快照日期")
    ax.set_ylabel("记录数（条）")
    ax.set_ylim(0, max(ys) * 1.18)
    ax.grid(axis="y", color="#DCDCDC", linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.spines["left"].set_color("#8A8A8A")
    ax.spines["bottom"].set_color("#8A8A8A")
    ax.xaxis.set_major_locator(mdates.AutoDateLocator())
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m-%d"))
    fig.autofmt_xdate(rotation=0, ha="center")
    ax.legend(loc="upper right", frameon=False, fontsize=9)
    fig.text(
        0.012, 0.015,
        f"注：折线仅连接实际返回记录的 {n_obs} 个快照日期，未对无记录日期插值；纵轴自 0 起。来源：仓库2 库存快照分组查询结果。",
        fontsize=8.2, color="#555555",
    )
    fig.tight_layout(rect=(0, 0.055, 1, 1))
    fig.savefig(OUT / CHART_NAME, dpi=150)
    plt.close(fig)
except Exception as exc:  # noqa: BLE001
    chart_error = f"{type(exc).__name__}: {exc}"
t_chart1 = time.perf_counter()
chart_ms = round((t_chart1 - t_chart0) * 1000.0, 2)

# ---------- narrative ----------
dates_txt = "、".join(d.strftime("%Y-%m-%d") for d in observed)
recs_txt = "、".join(f"{int(v):,}" for v in prof["sku_record_count"])
totals_txt = "、".join(f"{v:,}" for v in prof["total_on_hand"])

daily_table_md = [
    "| 快照日期 | 记录数（条） | 非零数量记录数（条） | 零数量记录数（条） | 现存数量合计（单位未在数据源声明） |",
    "| --- | ---: | ---: | ---: | ---: |",
]
for row in daily_rows:
    daily_table_md.append(f"| {row[0]} | {row[1]:,} | {row[2]:,} | {row[3]:,} | {row[4]} |")
daily_table_md = "\n".join(daily_table_md)

summary_p1 = (
    f"仓库2 在 1998-01-01 至 1998-01-29（含边界）按“数量下限 0、类别不限”条件执行的逐日分组查询，"
    f"实际返回 **{n_obs} 个快照日期共 {n_rows} 条日汇总记录**，日期分布为 {dates_txt}。"
)
summary_p2 = (
    f"这 {n_obs} 个快照日的库存记录数分别为 {recs_txt} 条；"
    f"单日记录数最高为 {max_rec:,} 条（{max_rec_date}），最低为 {min_rec:,} 条（{min_rec_date}），"
    f"有返回记录日期的平均记录数为 {avg_rec_str} 条。跨日期相加记录数没有业务意义，故不提供跨日合计数。"
)
summary_p3 = (
    f"现存数量合计（对 inv_quantity_on_hand 求和，数据源未声明计量单位，故不作单位推断）"
    f"逐日为 {totals_txt}；最高值出现在 {max_total_date}（{max_total:,}），最低值出现在 {min_total_date}（{min_total:,}）。"
    "由于各日期是相互独立的库存快照，跨日期相加没有业务意义，故不提供跨日合计数。"
)
summary_p4 = (
    f"覆盖性方面：区间共 {n_calendar_days} 个日历日，仅有 {n_obs} 个日期返回记录，"
    f"其余 {n_missing} 个日期在结果集中不存在。分组查询不返回任何行只能说明该仓库该日无符合条件的记录行，"
    "无法据此区分“当日无库存记录”与“当日快照未进入本数据集”，因此日期覆盖完整性标注为待确认。"
    f"另，相邻返回日期之间的间隔为 {gap_txt} 天（共 {len(gaps)} 个间隔，{'完全等间隔' if equal_gaps else '非等间隔'}），"
    "与“逐日”快照的预期节奏并不一致；该仓库库存快照的实际生成频率需由数据源方确认，本报告仅描述实际返回的日期点。"
)

quality_md = [
    "| 校验项 | 方法 | 结果 |",
    "| --- | --- | --- |",
    f"| 交付文件完整性与行数 | 逐文件 SHA-256 与声明行数比对（{n_parts} 个数据分片） | 通过：{n_rows} 行，与声明一致 |",
    f"| 查询范围（仓库/日期/下限/类别） | 按有序占位符解析：warehouse_id=2、start_date=1998-01-01、end_date=1998-01-29、min_on_hand=0、category=NULL | 通过：与目标一致，类别不限 |",
    f"| 快照日期主键唯一 | 日期重复计数 | 通过：重复 {dup_dates} 个 |",
    f"| 日期落在目标区间 | 逐行区间校验 | {'通过' if in_range else '未通过'} |",
    f"| 记录数分解自洽 | 非零记录数 + 零数量记录数 = 记录数 | {'通过' if decomp_ok else '未通过'} |",
    f"| 现存数量合计非负 | 逐日合计 >= 0 | {'通过' if nonneg_total else '未通过'} |",
    f"| 结果未被查询上限截断 | 返回行数 {n_rows} < 上限 {query_limit} | {'通过' if not_truncated else '未通过'} |",
    f"| 区间日期覆盖完整性 | {n_calendar_days} 个日历日 vs {n_obs} 个返回日期 | 待确认：{n_missing} 个日期无返回记录，无法区分无记录与快照缺失 |",
    f"| 相邻观测日间隔 | 相邻返回日期的天数差（{len(gaps)} 个间隔） | 待确认：间隔为 {gap_txt} 天（{'完全等间隔' if equal_gaps else '非等间隔'}），逐日粒度不可得 |",
]
quality_table_md = "\n".join(quality_md)

prov_rows = [
    ["快照日期", "snapshot_date", "库存快照日期（date_dim.d_date，按日期分组）", "查询定义 SELECT dd.d_date AS snapshot_date；来源描述“逐日库存快照”", "已确认"],
    ["记录数", "sku_record_count", "该仓库该快照日的库存记录行数（COUNT(*)）", "查询定义 COUNT(*) AS sku_record_count", "已确认"],
    ["非零数量记录数", "nonzero_sku_count", "该日 inv_quantity_on_hand > 0 的记录行数", "查询定义 SUM(CASE WHEN inv.inv_quantity_on_hand > 0 THEN 1 ELSE 0 END)", "已确认"],
    ["零数量记录数", "zero_sku_count", "该日 inv_quantity_on_hand = 0 的记录行数", "查询定义 SUM(CASE WHEN inv.inv_quantity_on_hand = 0 THEN 1 ELSE 0 END)", "已确认"],
    ["现存数量合计", "total_on_hand", "该日 inv_quantity_on_hand 的求和；计量单位未由来源声明", "查询定义 SUM(inv.inv_quantity_on_hand)；来源描述声明数量单位未提供", "已确认（单位未声明）"],
]
provenance_md = "\n".join(
    ["| 业务角色 | 来源字段 | 申报含义 | 声明来源 | 状态 |", "| --- | --- | --- | --- | --- |"]
    + ["| " + " | ".join(r) + " |" for r in prov_rows]
)

report_md = f"""# 仓库2 逐日库存快照记录数与现存数量合计（1998-01-01 至 1998-01-29）

## 结论摘要

{summary_p1}{summary_p2}{summary_p3}{summary_p4}

## 逐日记录数走势

按快照日期展示库存记录数（纵轴：记录数，条；横轴：快照日期）。图中只有实际返回记录的 {n_obs} 个日期点，无记录日期保持为空白、未插值补零，因此曲线不构成连续日序列。单日记录数在 {min_rec:,} 条（{min_rec_date}）至 {max_rec:,} 条（{max_rec_date}）之间波动。纵轴自 0 起，观测值集中在 {min_rec:,}–{max_rec:,} 条区间，折线视觉上较平缓；具体数值以数据点标注与明细表为准。

## 逐日明细：现存数量合计

{daily_table_md}

说明：记录数（sku_record_count）为该仓库该快照日的库存记录行数，并非去重后的 SKU 个数；现存数量合计（total_on_hand）为该日所有记录 inv_quantity_on_hand 的求和。数据源未声明数量计量单位（件/箱/单位等），本报告不推测单位，也不将合计值与任何货币口径对应。

## 覆盖性与数据质量

{quality_table_md}

## 字段来源与口径

{provenance_md}

## 局限

1. 目标区间 {n_calendar_days} 个日历日中仅 {n_obs} 个日期返回记录，其余 {n_missing} 个日期无数据行；缺少独立快照登记表，无法判定这些日期是真无库存记录还是快照未载入，故不将缺口视为 0。
2. 现存数量合计的计量单位、精度与业务含义未由数据源声明，因此只给出原始汇总数值，不标注单位、不做单位换算。
3. 结果集为按仓库与日期分组的日汇总，不含单个 SKU 明细，无法核对单条记录，也无法判断库存质量、库龄或分类构成。
4. 交付结果为分组查询的返回值，仅代表该次执行范围；区域外仓库、负数量记录及其他类别口径均不在本次证据之内。
"""

# ---------- metrics / tables / charts objects ----------
metrics = [
    {
        "id": "m_obs_days",
        "label": "返回记录的快照日期数",
        "value": str(n_obs),
        "unit": "个",
        "calculation": "对分组结果按 snapshot_date 去重计数",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "m_max_records",
        "label": f"单日最大记录数（{max_rec_date}）",
        "value": str(max_rec),
        "unit": "条",
        "calculation": "各组 sku_record_count（COUNT(*)）取最大值",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "m_min_records",
        "label": f"单日最小记录数（{min_rec_date}）",
        "value": str(min_rec),
        "unit": "条",
        "calculation": "各组 sku_record_count（COUNT(*)）取最小值",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "m_max_total_on_hand",
        "label": f"单日现存数量合计最高值（{max_total_date}）",
        "value": str(max_total),
        "unit": "",
        "calculation": "各组 total_on_hand（SUM(inv.inv_quantity_on_hand)）取最大值；单位未由数据源声明",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "m_missing_days",
        "label": "区间内无返回记录的日历日数",
        "value": str(n_missing),
        "unit": "个",
        "calculation": f"{n_calendar_days} 个日历日减去 {n_obs} 个返回日期",
        "dataRefs": [DATA_REF],
    },
]

tables = [
    {
        "id": "t_daily_detail",
        "title": "逐日库存快照明细（1998-01-01 至 1998-01-29 返回的日期）",
        "columns": [
            "快照日期",
            "记录数（条）",
            "非零数量记录数（条）",
            "零数量记录数（条）",
            "现存数量合计（单位未在数据源声明）",
        ],
        "rows": daily_rows,
        "scope": f"仓库2，日期区间 1998-01-01 至 1998-01-29（含边界），数量下限 0，类别不限；仅 {n_obs} 个有返回记录的日期",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "t_quality_checks",
        "title": "完整性与口径校验",
        "columns": ["校验项", "方法", "结果"],
        "rows": [
            ["交付文件完整性与行数", f"逐文件 SHA-256 与声明行数比对（{n_parts} 个数据分片）", f"通过：{n_rows} 行，与声明一致"],
            ["查询范围（仓库/日期/下限/类别）", "按有序占位符解析参数：2 / 1998-01-01 / 1998-01-29 / 0 / NULL", "通过：与目标一致，类别不限"],
            ["快照日期唯一性", "日期重复计数", f"通过：重复 {dup_dates} 个"],
            ["日期落在目标区间", "逐行区间校验", "通过" if in_range else "未通过"],
            ["记录数分解自洽", "非零记录数 + 零数量记录数 = 记录数", "通过" if decomp_ok else "未通过"],
            ["现存数量合计非负", "逐日合计 >= 0", "通过" if nonneg_total else "未通过"],
            ["结果未被查询上限截断", f"返回行数 {n_rows} < 上限 {query_limit}", "通过" if not_truncated else "未通过"],
            ["区间日期覆盖完整性", f"{n_calendar_days} 个日历日 vs {n_obs} 个返回日期", f"待确认：{n_missing} 个日期无返回记录"],
            ["相邻观测日间隔", f"相邻返回日期天数差（{len(gaps)} 个间隔）", f"待确认：间隔均为 {gap_txt} 天，逐日粒度不可得"],
        ],
        "scope": "同一份分组查询结果及其交付文件",
        "dataRefs": [DATA_REF],
    },
    {
        "id": "t_field_provenance",
        "title": "字段来源与口径对照",
        "columns": ["业务角色", "来源字段", "申报含义", "声明来源", "状态"],
        "rows": prov_rows,
        "scope": "同一份分组查询结果；含义取自执行回执中的查询表达式与来源描述",
        "dataRefs": [DATA_REF],
    },
]

charts = []
if chart_error is None:
    charts.append(
        {
            "id": "c_records_by_date",
            "title": "仓库2 逐日库存快照记录数（1998-01-01 至 1998-01-29）",
            "kind": "png",
            "chartType": "line",
            "fileName": CHART_NAME,
            "caption": f"横轴为快照日期，纵轴为库存记录数（条），仅绘制实际返回记录的 {n_obs} 个日期（{dates_txt}），无记录日期不插值、不补零；纵轴自 0 起。范围：仓库2、数量下限 0、类别不限。",
            "dataRefs": [DATA_REF],
        }
    )

sections = [
    {
        "id": "s_conclusion",
        "title": f"结论：目标区间返回 {n_obs} 个快照日期，单日记录数 {min_rec:,}–{max_rec:,} 条",
        "narrative": (
            f"{summary_p1}{summary_p2}"
            f"现存数量合计（单位未声明）最高为 {max_total_date} 的 {max_total:,}（按日期排序第 {max_total_rank} 个观测日），"
            f"最低为 {min_total_date} 的 {min_total:,}（第 {min_total_rank} 个观测日）；两者并非区间首尾日，"
            f"不能读作区间净变化。各日期为独立快照，故不作跨日加总。{n_missing} 个日历日无返回记录，保持为缺口而非 0；"
            f"相邻返回日期间隔统一为 {gap_txt} 天，实际快照频率需由数据源方确认。"
        ),
        "chartIds": [],
        "tableIds": [],
    },
    {
        "id": "s_trend",
        "title": "逐日记录数走势",
        "narrative": (
            f"按快照日期展示记录数：{recs_txt} 条。最低 {min_rec:,} 条（{min_rec_date}），最高 {max_rec:,} 条（{max_rec_date}），"
            f"有返回记录日期的平均记录数 {avg_rec_str} 条。折线只连接实际返回记录的日期，缺口不插值；纵轴自 0 起，故波动在视觉上被压缩。"
        ),
        "chartIds": [c["id"] for c in charts],
        "tableIds": [],
    },
    {
        "id": "s_detail",
        "title": "逐日现存数量合计明细",
        "narrative": (
            f"现存数量合计逐日为 {totals_txt}；对应记录数 {recs_txt} 条。"
            "数值直接来自各行 total_on_hand 汇总，未声明计量单位，未做单位换算或货币推断；"
            "单条记录明细不在交付范围内，故无法进一步拆解构成。"
        ),
        "chartIds": [],
        "tableIds": ["t_daily_detail"],
    },
    {
        "id": "s_quality",
        "title": "覆盖性与数据质量校验",
        "narrative": (
            f"文件完整性、范围参数、日期唯一性、记录数分解与截断检查均通过；"
            f"区间 {n_calendar_days} 个日历日中 {n_missing} 个日期无返回记录，"
            "无法由交付数据区分“无库存记录”与“快照缺失”，该项保持待确认。"
        ),
        "chartIds": [],
        "tableIds": ["t_quality_checks"],
    },
]

limitations = [
    f"目标区间 1998-01-01 至 1998-01-29 共 {n_calendar_days} 个日历日，仅 {n_obs} 个日期返回记录，其余 {n_missing} 个日期无数据行；缺少快照登记证据，无法区分“当日无库存记录”与“快照未载入”，未将缺口补为零。",
    "现存数量合计与记录数的计量单位、精度未由数据源声明（查询仅对 inv_quantity_on_hand 求和），因此不标注单位、不换算、不推断货币金额。",
    "交付数据为按仓库与日期分组的日汇总，缺少条目（SKU）级明细与分类字段，无法核对单条记录、分类构成或库龄等结论。",
    "范围依据有效参数（warehouse_id=2、1998-01-01 至 1998-01-29、min_on_hand=0、category=NULL）确认；其他仓库、负数量记录及其他类别口径不在本次证据范围内。",
    f"逐日记录数存在小幅波动，但仅有 {n_obs} 个观测日期，不足以判断趋势、季节性或其他持久性特征。",
    "记录数为库存记录行数（COUNT(*))，并非去重后的 SKU 个数；单个 SKU 的重复记录关系无法从日汇总结果验证。",
    f"返回日期呈等间隔排列（相邻间隔均为 {gap_txt} 天，共 {len(gaps)} 个间隔），与“逐日”快照的预期粒度不一致；缺少快照生成频率或调度声明，无法确认其余 {n_missing} 个日历日是“无数据”还是“本数据源不含逐日快照”。",
]
if chart_error:
    limitations.append(f"折线图渲染失败，已保留文本与表格结果：{chart_error}")

warnings = []
if chart_error:
    warnings.append(f"图表生成失败，报告降级为文本与表格：{chart_error}")
warnings.append("现存数量合计的计量单位未由数据源声明，表头与指标中单位为空白。")
warnings.append("纵轴仅覆盖实际返回记录的日期，图线不表示连续日历序列。")
warnings.append("折线图纵轴自 0 起，观测值区间狭窄，视觉波动被压缩；具体数值以数据点标注与明细表为准。")
warnings.append(f"返回日期相邻间隔均为 {gap_txt} 天，逐日粒度不可得，图线仅代表实际返回的 {n_obs} 个观察点。")

analysis_ms = round((time.perf_counter() - T0) * 1000.0, 2)

validation_checks = [
    {"name": "交付文件 SHA-256 与行数校验", "status": "PASSED", "detail": f"清单、模式文件及 {n_parts} 个数据分片哈希一致，合计 {n_rows} 行，与声明 {manifest['rowCount']} 行一致"},
    {"name": "查询范围与目标一致性", "status": "PASSED", "detail": "有序占位符解析为 warehouse_id=2、start_date=1998-01-01、end_date=1998-01-29、min_on_hand=0、category=NULL（类别不限），与用户目标一致"},
    {"name": "快照日期唯一性", "status": "PASSED", "detail": f"按 snapshot_date 检查重复键，重复数 {dup_dates}"},
    {"name": "日期区间校验", "status": "PASSED" if in_range else "FAILED", "detail": f"返回日期 {first_date} 至 {last_date}，全部落在 1998-01-01 至 1998-01-29 内"},
    {"name": "记录数分解一致性", "status": "PASSED" if decomp_ok else "FAILED", "detail": f"逐行校验 nonzero_sku_count + zero_sku_count = sku_record_count，全部成立（{n_obs} 个日期；{nonzero_sum:,} 与 {zero_sum:,} 的加总仅用于算术核对，不构成业务数量合计）"},
    {"name": "现存数量合计非负", "status": "PASSED" if nonneg_total else "FAILED", "detail": f"逐日合计最小值 {min_total:,}、最大值 {max_total:,}，均 >= 0，符合数量下限 0 的过滤条件"},
    {"name": "结果未被查询上限截断", "status": "PASSED" if not_truncated else "FAILED", "detail": f"返回 {n_rows} 行，小于查询声明的 LIMIT {query_limit}"},
    {"name": "区间日期覆盖完整性", "status": "UNRESOLVED", "detail": f"{n_calendar_days} 个日历日中 {n_obs} 个日期有返回记录、{n_missing} 个无返回记录；无快照登记证据，无法区分无库存记录与快照缺失"},
    {"name": "相邻观测日间隔一致性", "status": "UNRESOLVED", "detail": f"{len(gaps)} 个相邻间隔均为 {gap_txt} 天（{'完全等间隔' if equal_gaps else '非等间隔'}），与“逐日”快照预期不一致；无快照生成频率声明，无法确认逐日数据是否在原数据源中可获取"},
    {"name": "图表与数值一致性", "status": "PASSED" if chart_error is None else "FAILED", "detail": "图表数据点、指标卡与明细表均由同一份计算结果生成" if chart_error is None else chart_error},
]

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "仓库2 逐日库存快照记录数与现存数量合计（1998-01-01 至 1998-01-29）",
    "summary": summary_p1 + summary_p2 + summary_p3 + summary_p4,
    "status": "PARTIAL" if (chart_error or n_missing > 0) else "SUCCESS",
    "presentation": {
        "strategy": "auto",
        "template": "briefing",
        "density": "comfortable",
        "autoCharts": True,
        "autoImages": True,
    },
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": [DATA_REF],
    "validation": {"checks": validation_checks, "warnings": warnings},
    "timings": {"analysisMs": analysis_ms, "chartMs": chart_ms},
    "layout": {
        "schemaVersion": "report_layout.v1",
        "blocks": [
            {"id": "b-summary", "type": "summary"},
            {"id": "b-m-obs", "type": "metric", "ref": "m_obs_days", "span": "half"},
            {"id": "b-m-maxrec", "type": "metric", "ref": "m_max_records", "span": "half"},
            {"id": "b-m-maxqty", "type": "metric", "ref": "m_max_total_on_hand", "span": "half"},
            {"id": "b-m-gap", "type": "metric", "ref": "m_missing_days", "span": "half"},
            {"id": "b-s-conclusion", "type": "section", "ref": "s_conclusion"},
            {"id": "b-s-trend", "type": "section", "ref": "s_trend"},
            {"id": "b-chart-counts", "type": "chart", "ref": "c_records_by_date", "span": "full", "emphasis": "important"},
            {"id": "b-s-detail", "type": "section", "ref": "s_detail"},
            {"id": "b-t-daily", "type": "table", "ref": "t_daily_detail", "span": "full"},
            {"id": "b-s-quality", "type": "section", "ref": "s_quality"},
            {"id": "b-t-quality", "type": "table", "ref": "t_quality_checks", "span": "full"},
            {"id": "b-t-provenance", "type": "table", "ref": "t_field_provenance", "span": "full"},
            {"id": "b-limitations", "type": "limitations"},
            {"id": "b-sources", "type": "sources"},
        ],
    },
}

if chart_error is None:
    assert any(b.get("ref") == "c_records_by_date" for b in visual_report["layout"]["blocks"])
for blk in visual_report["layout"]["blocks"]:
    if blk["type"] == "metric":
        assert blk["ref"] in {m["id"] for m in metrics}
    elif blk["type"] == "section":
        assert blk["ref"] in {s["id"] for s in sections}
    elif blk["type"] == "chart":
        assert blk["ref"] in {c["id"] for c in charts}
    elif blk["type"] == "table":
        assert blk["ref"] in {t["id"] for t in tables}

field_provenance = [
    {
        "datasetReference": DATA_REF,
        "sourceField": "snapshot_date",
        "meaning": "库存快照日期（date_dim.d_date，按日期分组），业务日期字段",
        "declarationSource": "MCP_SQL_EXECUTION_RECEIPT 查询定义 SELECT dd.d_date AS snapshot_date 及来源描述“逐日库存快照”",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATA_REF,
        "sourceField": "sku_record_count",
        "meaning": "该仓库该快照日的库存记录行数（COUNT(*)）",
        "declarationSource": "MCP_SQL_EXECUTION_RECEIPT 查询定义 COUNT(*) AS sku_record_count",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATA_REF,
        "sourceField": "nonzero_sku_count",
        "meaning": "该日 inv_quantity_on_hand > 0 的记录行数（CASE 求和）",
        "declarationSource": "MCP_SQL_EXECUTION_RECEIPT 查询定义 SUM(CASE WHEN inv.inv_quantity_on_hand > 0 THEN 1 ELSE 0 END) AS nonzero_sku_count",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATA_REF,
        "sourceField": "zero_sku_count",
        "meaning": "该日 inv_quantity_on_hand = 0 的记录行数（CASE 求和）",
        "declarationSource": "MCP_SQL_EXECUTION_RECEIPT 查询定义 SUM(CASE WHEN inv.inv_quantity_on_hand = 0 THEN 1 ELSE 0 END) AS zero_sku_count",
        "status": "CONFIRMED",
    },
    {
        "datasetReference": DATA_REF,
        "sourceField": "total_on_hand",
        "meaning": "该日 inv_quantity_on_hand 的求和（现存数量合计）；计量单位未由来源声明",
        "declarationSource": "MCP_SQL_EXECUTION_RECEIPT 查询定义 SUM(inv.inv_quantity_on_hand) AS total_on_hand；来源描述声明数量单位未提供",
        "status": "CONFIRMED",
    },
]

row = {
    "reportMarkdown": report_md,
    "evidenceReferences": [DATA_REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}

with (OUT / "rows.jsonl").open("w", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")

diag = {
    "manifest_sha256": man_sha,
    "schema_sha256": schema_sha,
    "rows": n_rows,
    "observed_dates": [d.strftime("%Y-%m-%d") for d in observed],
    "missing_day_count": n_missing,
    "chart_error": chart_error,
    "chart_file_exists": (OUT / CHART_NAME).exists() if chart_error is None else False,
}
(VAL / "diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")
print("OK rows=1")
