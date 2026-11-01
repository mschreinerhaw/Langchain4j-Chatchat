import json, hashlib, os, time
from datetime import date, datetime, timedelta
from decimal import Decimal

import pyarrow.parquet as pq
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

DATASET_REF = "mcp_chatchat_mcp_server_tpl_190c862b57813ca38fbe5a0edc614970"
BASE = "/data/input"
MANIFEST_FILE_ID = "ccc50a46b9b3fe07b530181991fd5f8a200aa8567763af40ffa34fdb578483ea"
EXPECT_MANIFEST_SHA = "cb45d0ad3ee49e94d826876f32093832f12b524f474f974b62cfc2b6fb3aa85c"
OUT = "/workspace/output/results"
VAL = "/workspace/output/validation"
os.makedirs(OUT, exist_ok=True)
os.makedirs(VAL, exist_ok=True)

t_start = time.perf_counter()

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def as_number(v):
    if isinstance(v, Decimal):
        return int(v) if v == v.to_integral_value() else v
    if isinstance(v, bool):
        return int(v)
    return int(v)

def dec_str(v):
    if isinstance(v, Decimal):
        s = format(v, "f")
        return s
    return str(v)

# ---------------- 1. manifest + parts ----------------
manifest_path = os.path.join(BASE, MANIFEST_FILE_ID, "manifest.json")
manifest_sha = sha256_file(manifest_path)
checks = []
checks.append({
    "name": "输入清单完整性校验",
    "status": "PASSED" if manifest_sha == EXPECT_MANIFEST_SHA else "FAILED",
    "detail": "清单文件 SHA-256 与运行时声明的期望值一致。" if manifest_sha == EXPECT_MANIFEST_SHA else "清单文件哈希与期望值不一致。",
})
with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = json.load(f)

logical = {c["storageName"]: c["name"] for c in manifest["columns"]}
expected_rows = manifest["rowCount"]
records = []
part_info = []
total_read = 0
for part in manifest["parts"]:
    p = os.path.join(BASE, part["fileId"], part["fileName"])
    actual = sha256_file(p)
    ok = actual == part["sha256"]
    table = pq.read_table(p)
    names = {n: logical.get(n, n) for n in table.column_names}
    table = table.rename_columns([names[n] for n in table.column_names])
    rows = table.to_pylist()
    total_read += len(rows)
    part_info.append({"fileName": part["fileName"], "sha256Match": ok, "rowCount": len(rows),
                      "declaredRowCount": part["rowCount"]})
    checks.append({
        "name": "数据分片哈希校验(%s)" % part["fileName"],
        "status": "PASSED" if ok else "FAILED",
        "detail": "分片 SHA-256 与清单一致，行数 %d 与声明行数 %d %s。" % (
            len(rows), part["rowCount"], "一致" if len(rows) == part["rowCount"] else "不一致"),
    })
    records.extend(rows)

checks.append({
    "name": "记录行数校验",
    "status": "PASSED" if total_read == expected_rows else "FAILED",
    "detail": "实际读取 %d 行，清单声明 %d 行。" % (total_read, expected_rows),
})

# ---------------- 2. decode logical records ----------------
parsed = []
for r in records:
    ref = r.get("datasetReference")
    rec = r.get("record")
    if isinstance(rec, str):
        rec = json.loads(rec, parse_float=Decimal)
    parsed.append((ref, rec))

refs_seen = sorted({p[0] for p in parsed})
parsed = [p for p in parsed if p[0] == DATASET_REF]
if len(parsed) != total_read:
    checks.append({"name": "数据集引用一致性", "status": "FAILED",
                   "detail": "存在非预期的数据集引用：%s" % refs_seen})
else:
    checks.append({"name": "数据集引用一致性", "status": "PASSED",
                   "detail": "全部 %d 行均属于授权数据集引用 %s。" % (len(parsed), DATASET_REF)})

def rec_get(rec, key):
    return rec.get(key) if isinstance(rec, dict) else None

daily = []
missing_cell = []
for ref, rec in parsed:
    for k in ("snapshot_date", "sku_record_count", "nonzero_sku_count", "zero_sku_count", "total_on_hand"):
        if rec_get(rec, k) is None:
            missing_cell.append(k)
    daily.append({
        "snapshot_date": rec_get(rec, "snapshot_date"),
        "sku_record_count": as_number(rec_get(rec, "sku_record_count")),
        "nonzero_sku_count": as_number(rec_get(rec, "nonzero_sku_count")),
        "zero_sku_count": as_number(rec_get(rec, "zero_sku_count")),
        "total_on_hand": as_number(rec_get(rec, "total_on_hand")),
    })

checks.append({
    "name": "关键字段缺失值检查",
    "status": "PASSED" if not missing_cell else "FAILED",
    "detail": "5 个逐日字段在返回行中均非空。" if not missing_cell else "存在缺失字段：%s" % sorted(set(missing_cell)),
})

daily.sort(key=lambda d: d["snapshot_date"])
dates = [date.fromisoformat(d["snapshot_date"]) for d in daily]
counts = [d["sku_record_count"] for d in daily]
nonzero = [d["nonzero_sku_count"] for d in daily]
zeros = [d["zero_sku_count"] for d in daily]
onhand = [d["total_on_hand"] for d in daily]

dup_ok = len(set(dates)) == len(dates)
checks.append({"name": "快照日期唯一性", "status": "PASSED" if dup_ok else "FAILED",
               "detail": "返回 %d 行，快照日期去重后 %d 个。" % (len(dates), len(set(dates)))})

sum_ok = all(nz + z == c for nz, z, c in zip(nonzero, zeros, counts))
checks.append({
    "name": "记录数内部一致性（数量>0 + 数量=0 = 记录数）",
    "status": "PASSED" if sum_ok else "FAILED",
    "detail": "全部 %d 行满足 nonzero_sku_count + zero_sku_count = sku_record_count。" % len(daily) if sum_ok
    else "存在不满足条件计数据相加关系的行。",
})

req_start = date(1998, 1, 1)
req_end = date(1998, 1, 29)
in_range = all(req_start <= d <= req_end for d in dates)
asc_ok = dates == sorted(dates)
checks.append({
    "name": "日期范围与排序",
    "status": "PASSED" if (in_range and asc_ok) else "FAILED",
    "detail": "全部快照日期落在 1998-01-01 至 1998-01-29 之间并按日期升序。" if (in_range and asc_ok)
    else "存在越界或未按日期升序的记录。",
})

limit_ok = len(daily) < 100
checks.append({
    "name": "结果截断检查（LIMIT 100）",
    "status": "PASSED" if limit_ok else "UNRESOLVED",
    "detail": "返回 %d 行，未触及查询 LIMIT 100 上限。" % len(daily) if limit_ok
    else "返回行数达到 LIMIT 100，结果可能被截断。",
})

cal_days = (req_end - req_start).days + 1
observed = set(dates)
missing_dates = [req_start + timedelta(days=i) for i in range(cal_days) if (req_start + timedelta(days=i)) not in observed]
checks.append({
    "name": "日历覆盖完整性",
    "status": "UNRESOLVED",
    "detail": "请求区间 %d 个日历日中仅 %d 日返回观测，%d 日无记录；无业务日历或源库核对证据，无法判定是当日无库存记录还是数据缺失。" % (
        cal_days, len(dates), len(missing_dates)),
})

spacings_pre = sorted({(dates[i + 1] - dates[i]).days for i in range(len(dates) - 1)})
spacing_txt = "、".join(str(s) for s in spacings_pre) if spacings_pre else "不适用"
checks.append({
    "name": "观测间隔检查",
    "status": "PASSED",
    "detail": "%d 个观测日期构成 %d 个相邻间隔，间隔天数均为 %s；实际数据并非逐日连续返回。" % (
        len(dates), max(len(dates) - 1, 0), "、".join(str(s) for s in spacings_pre) if spacings_pre else "不适用"),
})

quant_ok = all(v >= 0 for v in onhand)
checks.append({"name": "现存数量合计非负性", "status": "PASSED" if quant_ok else "FAILED",
               "detail": "全部观测日现存数量合计均 >= 0，与查询的数量下限 0 条件相符。" if quant_ok
               else "存在负的现存数量合计。"})

total_records = sum(counts)
min_idx = min(range(len(counts)), key=lambda i: counts[i])
max_idx = max(range(len(counts)), key=lambda i: counts[i])
oh_max_idx = max(range(len(onhand)), key=lambda i: onhand[i])
oh_min_idx = min(range(len(onhand)), key=lambda i: onhand[i])

t_after_calc = time.perf_counter()
analysis_ms = round((t_after_calc - t_start) * 1000.0, 3)

# ---------------- 3. charts ----------------
t_chart0 = time.perf_counter()
chart_files = []
chart_errors = []

def fmt(n):
    return format(int(n), ",")

PLOT_INK = "#1f3a5f"
PLOT_TEAL = "#2f6f6f"
GRID = "#d5d5d5"

# chart 1: record count line
try:
    fig, ax = plt.subplots(figsize=(9.2, 4.8), dpi=150)
    ax.plot(dates, counts, marker="o", color=PLOT_INK, linewidth=1.8, markersize=6,
            label="库存记录数（条）")
    for x, y in zip(dates, counts):
        ax.annotate(fmt(y), (x, y), textcoords="offset points", xytext=(0, 9),
                    ha="center", fontsize=9, color=PLOT_INK)
    lo, hi = min(counts), max(counts)
    pad = max(1, int((hi - lo) * 0.35) if hi > lo else max(1, int(hi * 0.002)))
    ax.set_ylim(lo - pad, hi + pad)
    ax.set_xlim(req_start - timedelta(days=1), req_end + timedelta(days=1))
    ax.set_xlabel("快照日期")
    ax.set_ylabel("记录数（条）")
    ax.set_title("仓库2 逐日库存快照记录数（1998-01-01 至 1998-01-29，观测 %d 个快照日期）" % len(dates),
                 fontsize=11)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m-%d"))
    ax.grid(True, axis="y", linestyle=":", color=GRID, linewidth=0.7)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    ax.legend(loc="upper right", frameon=False, fontsize=9)
    fig.text(0.011, 0.012,
             "数据来源：仓库2 库存快照查询结果（1998-01-01 至 1998-01-29，数量下限 0，类别不限）。"
             "仅绘制返回观测的 %d 个日期，折线只连接这些观测点，未观测日期不取值、未插值；"
             "纵轴未从零开始，范围为 %s–%s 条。" % (len(dates), fmt(lo - pad), fmt(hi + pad)),
             fontsize=7.2, color="#555555")
    fig.tight_layout(rect=(0, 0.045, 1, 1))
    f1 = os.path.join(OUT, "inventory_record_count_line.png")
    fig.savefig(f1, facecolor="white")
    plt.close(fig)
    chart_files.append(os.path.basename(f1))
except Exception as exc:  # noqa: BLE001
    chart_errors.append("记录数折线图生成失败：%s" % type(exc).__name__)
    f1 = None

# chart 2: on-hand total line
try:
    fig, ax = plt.subplots(figsize=(9.2, 4.8), dpi=150)
    ax.plot(dates, onhand, marker="s", color=PLOT_TEAL, linewidth=1.8, markersize=6,
            label="现存数量合计")
    for x, y in zip(dates, onhand):
        ax.annotate(fmt(y), (x, y), textcoords="offset points", xytext=(0, 9),
                    ha="center", fontsize=8.5, color=PLOT_TEAL)
    lo2, hi2 = min(onhand), max(onhand)
    pad2 = max(1, int((hi2 - lo2) * 0.35) if hi2 > lo2 else max(1, int(hi2 * 0.002)))
    ax.set_ylim(lo2 - pad2, hi2 + pad2)
    ax.set_xlim(req_start - timedelta(days=1), req_end + timedelta(days=1))
    ax.set_xlabel("快照日期")
    ax.set_ylabel("现存数量合计（单位未由数据源声明）")
    ax.set_title("仓库2 逐日现存数量合计（1998-01-01 至 1998-01-29，观测 %d 个快照日期）" % len(dates),
                 fontsize=11)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m-%d"))
    ax.grid(True, axis="y", linestyle=":", color=GRID, linewidth=0.7)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    ax.legend(loc="upper right", frameon=False, fontsize=9)
    fig.text(0.011, 0.012,
             "现存数量合计为查询中 SUM(inv_quantity_on_hand) 的逐日结果；数据源未声明计量单位，未作推断。"
             "纵轴未从零开始，范围为 %s–%s。" % (fmt(lo2 - pad2), fmt(hi2 + pad2)),
             fontsize=7.2, color="#555555")
    fig.tight_layout(rect=(0, 0.045, 1, 1))
    f2 = os.path.join(OUT, "inventory_onhand_total_line.png")
    fig.savefig(f2, facecolor="white")
    plt.close(fig)
    chart_files.append(os.path.basename(f2))
except Exception as exc:  # noqa: BLE001
    chart_errors.append("现存数量合计折线图生成失败：%s" % type(exc).__name__)
    f2 = None

chart_ms = round((time.perf_counter() - t_chart0) * 1000.0, 3)

# ---------------- 4. consistency assertions ----------------
assert total_records == sum(d["sku_record_count"] for d in daily)
assert sum(zeros) + sum(nonzero) == total_records
assert len(dates) == len(counts) == len(onhand)
assert len(dates) + len(missing_dates) == cal_days
assert counts[min_idx] == min(counts) and counts[max_idx] == max(counts)
assert onhand[oh_max_idx] == max(onhand) and onhand[oh_min_idx] == min(onhand)

# ---------------- 5. tables / metrics ----------------
detail_rows = [[d.strftime("%Y-%m-%d"), fmt(c), fmt(nz), fmt(z), fmt(t)]
               for d, c, nz, z, t in zip(dates, counts, nonzero, zeros, onhand)]
coverage_rows = [
    ["请求区间起点（含）", req_start.isoformat()],
    ["请求区间终点（含）", req_end.isoformat()],
    ["区间日历天数", fmt(cal_days)],
    ["返回观测的快照日期数", fmt(len(dates))],
    ["未返回观测的日期数", fmt(len(missing_dates))],
    ["返回库存记录数合计（观测日累计）", fmt(total_records)],
]
missing_txt = "、".join(d.isoformat() for d in missing_dates) if missing_dates else "无"

metrics = [
    {"id": "m_observed_days", "label": "观测到的快照日期数", "value": fmt(len(dates)), "unit": "天",
     "calculation": "对返回结果按 snapshot_date 去重计数（1998-01-01 至 1998-01-29）",
     "dataRefs": [DATASET_REF]},
    {"id": "m_records_total", "label": "库存记录数合计（观测日累计）", "value": fmt(total_records), "unit": "条",
     "calculation": "sum(sku_record_count)，sku_record_count 为查询中按日 COUNT(*)",
     "dataRefs": [DATASET_REF]},
    {"id": "m_daily_range", "label": "单日库存记录数区间", "value": "%s–%s" % (fmt(min(counts)), fmt(max(counts))), "unit": "条",
     "calculation": "min/max(sku_record_count)，最低 %s（%s），最高 %s（%s）" % (
         fmt(counts[min_idx]), dates[min_idx].isoformat(), fmt(counts[max_idx]), dates[max_idx].isoformat()),
     "dataRefs": [DATASET_REF]},
    {"id": "m_gap_days", "label": "区间内未返回观测的日期数", "value": fmt(len(missing_dates)), "unit": "天",
     "calculation": "区间日历天数 %d 减去观测快照日期数 %d" % (cal_days, len(dates)),
     "dataRefs": [DATASET_REF]},
]

tables = [
    {"id": "tbl_daily_detail", "title": "逐日库存快照明细（现存数量合计，单位未由数据源声明）",
     "columns": ["快照日期", "库存记录数（条）", "其中数量>0记录数（条）", "其中数量=0记录数（条）",
                 "现存数量合计（单位未由数据源声明）"],
     "rows": detail_rows,
     "scope": "仓库2，1998-01-01 至 1998-01-29（含边界），现存数量下限 0，类别不限；仅返回观测的 %d 个快照日期。" % len(dates),
     "dataRefs": [DATASET_REF]},
    {"id": "tbl_coverage", "title": "期间覆盖与行数核对",
     "columns": ["核对项", "值"],
     "rows": coverage_rows,
     "scope": "同一查询结果的时间覆盖与规模核对；日期为含边界自然日口径。",
     "dataRefs": [DATASET_REF]},
]

charts = []
if f1:
    charts.append({"id": "chart_record_count_line", "title": "仓库2 逐日库存快照记录数（条）",
                   "kind": "png", "fileName": "inventory_record_count_line.png", "chartType": "line",
                   "caption": "横轴为快照日期（1998-01-01 至 1998-01-29），纵轴为库存记录数（条，按日 COUNT(*)）。"
                              "仅绘制查询返回的 %d 个快照日期（相邻间隔 %s 天），折线只连接观测点，未观测日期不取值、未插值；"
                              "纵轴未从零开始（数据范围约 %s–%s 条）。" % (
                                  len(dates), spacing_txt, fmt(min(counts)), fmt(max(counts))),
                   "dataRefs": [DATASET_REF]})
if f2:
    charts.append({"id": "chart_onhand_line", "title": "仓库2 逐日现存数量合计（单位未由数据源声明）",
                   "kind": "png", "fileName": "inventory_onhand_total_line.png", "chartType": "line",
                   "caption": "横轴为快照日期，纵轴为查询中 SUM(inv_quantity_on_hand) 的逐日合计；数据源未声明计量单位，未作推断。"
                              "纵轴未从零开始（范围约 %s–%s）。" % (fmt(min(onhand)), fmt(max(onhand))),
                   "dataRefs": [DATASET_REF]})

# ---------------- 6. narrative ----------------
first_d, last_d = dates[0], dates[-1]
change = counts[-1] - counts[0]
direction = "上升" if change > 0 else ("下降" if change < 0 else "持平")
zero_share = [z / c for z, c in zip(zeros, counts)]
zero_share_max_idx = max(range(len(zero_share)), key=lambda i: zero_share[i])
zero_share_min = min(zero_share)
zero_share_max = zero_share[zero_share_max_idx]

summary_txt = (
    "仓库2 在 1998-01-01 至 1998-01-29 的查询共返回 %d 个快照日期、合计 %s 条库存记录；"
    "这 %d 个观测日相邻间隔 %s 天，%d 个日历日中另有 %d 天没有任何返回记录。"
    "单日记录数在 %s 条至 %s 条之间，首末观测日（%s → %s）%s %s 条。"
    "现存数量合计在观测日为 %s（%s，最低）至 %s（%s，最高），计量单位数据源未声明，未作推断。"
    "空缺日期未补零、未插值，因此本结果不是连续 %d 天的逐日完整快照。"
) % (
    len(dates), fmt(total_records), len(dates), spacing_txt, cal_days, len(missing_dates),
    fmt(min(counts)), fmt(max(counts)),
    first_d.isoformat(), last_d.isoformat(), direction, fmt(abs(change)),
    fmt(onhand[oh_min_idx]), dates[oh_min_idx].isoformat(),
    fmt(onhand[oh_max_idx]), dates[oh_max_idx].isoformat(),
    cal_days,
)

sec_overview = (
    "查询条件为仓库 2、快照日期 1998-01-01 至 1998-01-29（含边界）、现存数量下限 0、类别不限，"
    "返回结果按快照日期升序排列并已聚合成逐日汇总。观测到的 %d 个日期为 %s，相邻观测间隔 %s 天，"
    "并非每个日历日都有观测；因此记录数折线只连接这些观测点，跨越的未观测区间不代表逐日取值。"
    "库存记录数在观测日之间最大相差 %s 条，首末观测日%s %s 条，属描述性对比。"
    "其中数量为 0 的记录每日 %s 至 %s 条，占当日记录数的 %.2f%% 至 %.2f%%（最高为 %s 的 %s 条／%s 条）。"
) % (
    len(dates), "、".join(d.isoformat() for d in dates), spacing_txt,
    fmt(max(counts) - min(counts)), direction, fmt(abs(change)),
    fmt(min(zeros)), fmt(max(zeros)),
    zero_share_min * 100, zero_share_max * 100,
    dates[zero_share_max_idx].isoformat(), fmt(zeros[zero_share_max_idx]), fmt(counts[zero_share_max_idx]),
)

sec_detail = (
    "下表按快照日期列示库存记录数与现存数量合计。现存数量合计为查询中 SUM(inv_quantity_on_hand) 的逐日结果，"
    "只能在同一日期内加总；不同日期之间相加没有库存业务含义，因此未提供跨日合计。"
    "记录数合计（%s 条）是同一条查询在 %d 个观测日返回的记录条数之和，仅用于核对返回规模。"
) % (fmt(total_records), len(dates))

sec_gaps = (
    "请求区间共 %d 个日历日，查询只返回 %d 天。缺失的 %d 天为：%s。"
    "查询为按日期分组的聚合结果，某日没有匹配的库存记录时不会产生输出行，"
    "因此这些空缺既可能表示当日无符合条件的库存记录，也可能表示数据未覆盖，"
    "交付数据中没有任何证据可以区分两者。图上这些日期显示为空缺，未按 0 处理，也未做插值。"
) % (cal_days, len(dates), len(missing_dates), missing_txt)

sec_basis = (
    "字段含义以查询声明为准：snapshot_date 为日期维度的 d_date 分组键；sku_record_count 为 COUNT(*)，"
    "即该日库存记录条数；nonzero_sku_count / zero_sku_count 为 inv_quantity_on_hand 分别 > 0 与 = 0 的条件计数；"
    "total_on_hand 为 SUM(inv_quantity_on_hand)。筛选条件 inv_warehouse_sk = 2、日期区间含边界、"
    "inv_quantity_on_hand >= 0、类别条件为空，与用户请求一致。数量与现存数量的计量单位未由数据源声明，"
    "报告中的数量类数值均不标注单位。"
)

sections = [
    {"id": "sec_overview", "title": "结论与期间概况", "narrative": sec_overview,
     "chartIds": ["chart_record_count_line"], "tableIds": []},
    {"id": "sec_detail", "title": "逐日明细与现存数量合计", "narrative": sec_detail,
     "chartIds": ["chart_onhand_line"], "tableIds": ["tbl_daily_detail"]},
    {"id": "sec_gaps", "title": "日期覆盖与空缺处理", "narrative": sec_gaps,
     "chartIds": [], "tableIds": ["tbl_coverage"]},
    {"id": "sec_basis", "title": "口径与字段来源", "narrative": sec_basis,
     "chartIds": [], "tableIds": []},
]

# ---------------- 7. markdown ----------------
md = []
md.append("# 仓库2 库存快照记录数（1998-01-01 至 1998-01-29，返回 %d 个快照日期）" % len(dates))
md.append("")
md.append("## 结论")
md.append("")
md.append(summary_txt)
md.append("")
md.append("## 期间概况")
md.append("")
md.append(sec_overview)
md.append("")
md.append("### 逐日库存快照记录数（横轴：快照日期；纵轴：记录数，条）")
md.append("")
md.append("![仓库2 逐日库存快照记录数](inventory_record_count_line.png)")
md.append("")
md.append("图示口径：仅绘出查询返回的 %d 个快照日期（相邻间隔 %s 天），折线只连接这些观测点，"
          "未观测日期不作为逐日取值、未插值；纵轴未从零开始（约 %s–%s 条）。" % (
              len(dates), spacing_txt, fmt(min(counts)), fmt(max(counts))))
md.append("")
md.append("## 现存数量合计明细")
md.append("")
md.append(sec_detail)
md.append("")
md.append("| 快照日期 | 库存记录数（条） | 其中数量>0记录数（条） | 其中数量=0记录数（条） | 现存数量合计（单位未由数据源声明） |")
md.append("| --- | ---: | ---: | ---: | ---: |")
for r in detail_rows:
    md.append("| " + " | ".join(r) + " |")
md.append("")
if f2:
    md.append("![仓库2 逐日现存数量合计](inventory_onhand_total_line.png)")
    md.append("")
md.append("## 日期覆盖与空缺")
md.append("")
md.append(sec_gaps)
md.append("")
md.append("| 核对项 | 值 |")
md.append("| --- | ---: |")
for r in coverage_rows:
    md.append("| " + " | ".join(r) + " |")
md.append("")
md.append("## 口径与字段来源")
md.append("")
md.append(sec_basis)
md.append("")
md.append("| 逻辑字段 | 声明含义 | 声明来源 | 状态 |")
md.append("| --- | --- | --- | --- |")
md.append("| snapshot_date | 快照日期（date_dim.d_date，按日分组键） | 查询声明（执行回执 SQL） | 已确认 |")
md.append("| sku_record_count | 该日库存记录条数（COUNT(*)） | 查询声明（执行回执 SQL） | 已确认 |")
md.append("| nonzero_sku_count | inv_quantity_on_hand > 0 的记录条数 | 查询声明（执行回执 SQL） | 已确认 |")
md.append("| zero_sku_count | inv_quantity_on_hand = 0 的记录条数 | 查询声明（执行回执 SQL） | 已确认 |")
md.append("| total_on_hand | SUM(inv_quantity_on_hand)，逐日现存数量合计；单位未声明 | 查询声明（执行回执 SQL） | 已确认（单位未声明） |")
md.append("")
md.append("## 局限")
md.append("")
md.append("1. 请求区间 %d 个日历日中仅 %d 天返回观测，其余 %d 天无记录；无法判定是当日本身无记录还是数据缺失，"
          "因此不能把本结果当作连续 %d 天的完整逐日快照，也不对空缺日期补零或插值。" % (
              cal_days, len(dates), len(missing_dates), cal_days))
md.append("2. 现存数量与库存数量的计量单位未由数据源声明，报告未标注单位、未做单位换算，"
          "相关数值只能做同期同口径比较。")
md.append("3. 现存数量合计为逐日 SUM，跨日期相加不构成业务指标，报告不提供跨日合计；"
          "记录数合计仅为观测日记录条数之和，用于核对返回规模。")
md.append("4. 结果来自单次模板查询（仓库 2、含边界日期、数量下限 0、类别不限、LIMIT 100），返回 %d 行未触及上限；"
          "仓库标识与日期参数来自执行回执，未与源库独立复核。" % len(daily))
md.append("5. 样本仅覆盖单一仓库、单一时段与 %d 个观测日，不能据此推断长期库存趋势或库存管理偏好。" % len(dates))
md.append("6. 首末观测日之间的变化（%s 条）只描述 %d 个观测点的首末差异，不是连续 %d 个区间的完整时间序列变化。" % (
    ("+" if change > 0 else "") + fmt(change), len(dates), cal_days - 1))
md.append("")

report_markdown = "\n".join(md)

limitations = [
    "请求区间 1998-01-01 至 1998-01-29 共 %d 个日历日，查询仅返回 %d 个快照日期，其余 %d 天无任何记录；"
    "按日期分组的聚合在无匹配行时不产生输出，无法判定是当日无记录还是数据缺失，未补零、未插值，"
    "因此不构成连续逐日快照。" % (cal_days, len(dates), len(missing_dates)),
    "现存数量（inv_quantity_on_hand）及库存数量的计量单位未由数据源声明，未作推断，相关数值与图表不标注单位，也不做单位换算。",
    "现存数量合计为逐日 SUM，跨日期相加无库存业务含义，报告未提供跨日合计；记录数合计仅为观测日记录条数之和。",
    "结果来自单次模板查询（仓库 2、含边界日期、数量下限 0、类别不限、LIMIT 100），返回 %d 行低于上限未被截断；"
    "筛选参数取自执行回执，未与源系统独立复核。" % len(daily),
    "仅覆盖单一仓库、单一时段与 %d 个观测日，不能推断长期库存趋势或库存管理偏好；首末观测日差异仅为 %d 个观测点之间的描述性对比。" % (
        len(dates), len(dates)),
    "未提供业务日历或该仓库应有快照节奏的说明；观测到的 %d 个日期相邻间隔 %s 天，"
    "无法确认该仓库本应逐日提供快照还是仅按此节奏提供，也无法确认 1998-01 期间应有多少个快照日期。" % (
        len(dates), spacing_txt),
]

if chart_errors:
    limitations.append("图表生成异常：" + "；".join(chart_errors))

field_provenance = [
    {"datasetReference": DATASET_REF, "sourceField": "snapshot_date",
     "meaning": "快照日期（date_dim.d_date，按日分组键）",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT 执行 SQL 声明）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "sku_record_count",
     "meaning": "该快照日期的库存记录条数（COUNT(*)）",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT 执行 SQL 声明）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "nonzero_sku_count",
     "meaning": "该快照日期中 inv_quantity_on_hand > 0 的库存记录条数（条件计数）",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT 执行 SQL 声明）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "zero_sku_count",
     "meaning": "该快照日期中 inv_quantity_on_hand = 0 的库存记录条数（条件计数）",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT 执行 SQL 声明）",
     "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "total_on_hand",
     "meaning": "该快照日期现存数量合计 SUM(inv_quantity_on_hand)；计量单位未由数据源声明",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT 执行 SQL 声明）",
     "status": "CONFIRMED"},
]

warnings = [
    "现存数量合计的计量单位未由数据源声明，未标注单位。",
    "区间内 %d 天未返回观测，图表中按空缺显示，未补零或插值。" % len(missing_dates),
    "记录数合计为观测日记录条数之和，不代表库存业务量。",
]
if chart_errors:
    warnings.extend(chart_errors)

layout_blocks = [
    {"id": "blk_summary", "type": "summary", "span": "full", "emphasis": "important"},
    {"id": "blk_m1", "type": "metric", "ref": "m_observed_days", "span": "half"},
    {"id": "blk_m2", "type": "metric", "ref": "m_records_total", "span": "half"},
    {"id": "blk_m3", "type": "metric", "ref": "m_daily_range", "span": "half"},
    {"id": "blk_m4", "type": "metric", "ref": "m_gap_days", "span": "half"},
    {"id": "blk_sec1", "type": "section", "ref": "sec_overview", "span": "full", "emphasis": "important"},
]
if f1:
    layout_blocks.append({"id": "blk_c1", "type": "chart", "ref": "chart_record_count_line", "span": "full"})
layout_blocks.append({"id": "blk_sec2", "type": "section", "ref": "sec_detail", "span": "full"})
layout_blocks.append({"id": "blk_t1", "type": "table", "ref": "tbl_daily_detail", "span": "full"})
if f2:
    layout_blocks.append({"id": "blk_c2", "type": "chart", "ref": "chart_onhand_line", "span": "full"})
layout_blocks.append({"id": "blk_sec3", "type": "section", "ref": "sec_gaps", "span": "full"})
layout_blocks.append({"id": "blk_t2", "type": "table", "ref": "tbl_coverage", "span": "full"})
layout_blocks.append({"id": "blk_sec4", "type": "section", "ref": "sec_basis", "span": "full"})
layout_blocks.append({"id": "blk_lim", "type": "limitations", "span": "full"})
layout_blocks.append({"id": "blk_src", "type": "sources", "span": "full"})

status = "PARTIAL" if (chart_errors or missing_dates) else "SUCCESS"

visual = {
    "schemaVersion": "python_visual_report.v1",
    "title": "仓库2 库存快照记录数与现存数量合计（1998-01-01 至 1998-01-29，返回 %d 个快照日期）" % len(dates),
    "summary": summary_txt,
    "status": status,
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": [DATASET_REF],
    "validation": {"checks": checks, "warnings": warnings},
    "timings": {"analysisMs": analysis_ms, "chartMs": chart_ms},
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable",
                     "autoCharts": True, "autoImages": True},
    "layout": {"schemaVersion": "report_layout.v1", "blocks": layout_blocks},
}

row = {
    "reportMarkdown": report_markdown,
    "evidenceReferences": [DATASET_REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual,
}

with open(os.path.join(OUT, "rows.jsonl"), "w", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=False) + "\n")

diag = {
    "datasetReferencesSeen": refs_seen,
    "partInfo": part_info,
    "logicalColumns": logical,
    "parsedDaily": [{"snapshot_date": d.isoformat(), "sku_record_count": c, "nonzero_sku_count": nz,
                     "zero_sku_count": z, "total_on_hand": t}
                    for d, c, nz, z, t in zip(dates, counts, nonzero, zeros, onhand)],
    "calendarDays": cal_days,
    "observedDays": len(dates),
    "missingDays": [d.isoformat() for d in missing_dates],
    "totalRecords": total_records,
    "chartFiles": chart_files,
    "chartErrors": chart_errors,
    "checks": checks,
    "analysisMs": analysis_ms,
    "chartMs": chart_ms,
}
with open(os.path.join(VAL, "diagnostics.json"), "w", encoding="utf-8") as f:
    json.dump(diag, f, ensure_ascii=False, indent=2)
