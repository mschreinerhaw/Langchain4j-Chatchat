import hashlib
import json
import os
import time
from datetime import date, timedelta
from decimal import Decimal

import matplotlib
matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import pyarrow.parquet as pq

T0 = time.perf_counter()
OUT_DIR = "/workspace/output/results"
VAL_DIR = "/workspace/output/validation"
os.makedirs(OUT_DIR, exist_ok=True)
os.makedirs(VAL_DIR, exist_ok=True)

REF = "mcp_chatchat_mcp_server_tpl_190c862b57813ca38fbe5a0edc614970"
MANIFEST_PATH = "/data/input/cc0c6b81f33cf06affc6695d8f417b6b7bf87d13424c2321449c1bfd4d641890/manifest.json"
SCHEMA_PATH = "/data/input/cc36baf786a10de35029d404bd457dc9474bc8b07bc7b755adec868bf45c1de6/schema.json"
EXPECTED_MANIFEST_SHA = "c8c992f8f4b7b68583fb4b42c1922069cffda35674c5d71b098b9e9f7142621a"
WINDOW_START = date(1998, 1, 1)
WINDOW_END = date(1998, 1, 29)

with open(MANIFEST_PATH, "rb") as fh:
    manifest_bytes = fh.read()
manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
assert manifest_sha == EXPECTED_MANIFEST_SHA, "manifest sha256 mismatch"
manifest = json.loads(manifest_bytes.decode("utf-8"))

with open(SCHEMA_PATH, "rb") as fh:
    schema = json.load(fh)

presence = schema.get("presenceColumns") or {}
column_by_storage = {c["storageName"]: c for c in manifest["columns"]}

rows = []
part_receipts = []
dataset_refs_seen = set()
for part in manifest["parts"]:
    part_path = f"/data/input/{part['fileId']}/{part['fileName']}"
    with open(part_path, "rb") as fh:
        part_sha = hashlib.sha256(fh.read()).hexdigest()
    assert part_sha == part["sha256"], f"part sha256 mismatch: {part['fileName']}"
    n_part = 0
    for batch in pq.ParquetFile(part_path).iter_batches(batch_size=2048):
        for raw in batch.to_pylist():
            logical = {}
            for storage, column in column_by_storage.items():
                flag = presence.get(storage)
                if flag is not None and flag in raw and not raw[flag]:
                    continue
                value = raw[storage]
                if column["type"] == "JSON" and value is not None:
                    value = json.loads(value, parse_float=Decimal)
                logical[column["name"]] = value
            rows.append(logical)
            n_part += 1
    assert n_part == part["rowCount"], "part rowCount mismatch"
    part_receipts.append({"fileName": part["fileName"], "sha256": part_sha, "rows": n_part})

assert len(rows) == manifest["rowCount"], "total rowCount mismatch"
for r in rows:
    dataset_refs_seen.add(r.get("datasetReference"))
assert dataset_refs_seen == {REF}, f"unexpected dataset references: {dataset_refs_seen}"

FIELDS = ["snapshot_date", "sku_record_count", "nonzero_sku_count", "zero_sku_count", "total_on_hand"]
records = []
for r in rows:
    rec = r["record"]
    missing_fields = [f for f in FIELDS if f not in rec]
    assert not missing_fields, f"missing declared fields: {missing_fields}"
    sd = date.fromisoformat(str(rec["snapshot_date"]))
    records.append({
        "d": sd,
        "snapshot_date": str(rec["snapshot_date"]),
        "sku_record_count": int(rec["sku_record_count"]),
        "nonzero_sku_count": int(rec["nonzero_sku_count"]),
        "zero_sku_count": int(rec["zero_sku_count"]),
        "total_on_hand": rec["total_on_hand"],
    })
records.sort(key=lambda x: x["d"])

# ---- quality checks -------------------------------------------------------
dates = [r["d"] for r in records]
dup_dates = sorted({str(d) for d in dates if dates.count(d) > 1})
in_window = all(WINDOW_START <= d <= WINDOW_END for d in dates)
window_dates = [WINDOW_START + timedelta(days=i) for i in range((WINDOW_END - WINDOW_START).days + 1)]
observed_set = set(dates)
missing_dates = [d for d in window_dates if d not in observed_set]
intervals = [(dates[i + 1] - dates[i]).days for i in range(len(dates) - 1)]
consistency_bad = [r["snapshot_date"] for r in records
                   if r["nonzero_sku_count"] + r["zero_sku_count"] != r["sku_record_count"]]
nonneg_ok = all(_v >= 0 for _v in [r["total_on_hand"] for r in records]) if False else all(
    (r["total_on_hand"] is not None and r["total_on_hand"] >= 0) for r in records)
assert nonneg_ok


def exact(v):
    if v is None:
        return None
    if isinstance(v, Decimal):
        return format(v, "f")
    if isinstance(v, int):
        return str(v)
    return str(v)


counts = [r["sku_record_count"] for r in records]
onhands = [r["total_on_hand"] for r in records]
n_obs = len(records)
n_window = len(window_dates)
n_missing = len(missing_dates)
min_count, max_count = min(counts), max(counts)
min_onhand, max_onhand = min(onhands), max(onhands)
first_rec, last_rec = records[0], records[-1]
delta_records = last_rec["sku_record_count"] - first_rec["sku_record_count"]
delta_onhand = last_rec["total_on_hand"] - first_rec["total_on_hand"]
dates_text = "、".join(r["snapshot_date"] for r in records)
intervals_text = "、".join(str(i) for i in intervals)
counts_flat = (min_count == max_count)

# ---- chart ---------------------------------------------------------------
chart_name = "warehouse2_inventory_record_trend.png"
chart_path = os.path.join(OUT_DIR, chart_name)
CHART_T0 = time.perf_counter()
fig, ax = plt.subplots(figsize=(8.6, 4.6), dpi=200)
ax.plot(dates, counts, color="#1f3b73", linewidth=1.7, linestyle="--", marker="o",
        markersize=5.5, markerfacecolor="#ffffff", markeredgewidth=1.6, label="库存记录数（条）")
for d, c in zip(dates, counts):
    ax.annotate(f"{c:,}", (d, c), textcoords="offset points", xytext=(0, 9), ha="center",
                fontsize=8.5, color="#1f3b73")
ax.set_title("仓库2 库存记录数：仅观测快照日（1998-01-01 至 1998-01-29，数量下限 0、类别不限）",
             fontsize=11, color="#1a1a1a", pad=10)
ax.set_xlabel("快照日期", fontsize=9.5)
ax.set_ylabel("库存记录数（条）", fontsize=9.5)
ax.xaxis.set_major_locator(mdates.DayLocator(interval=7))
ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d"))
ax.set_xlim(WINDOW_START - timedelta(days=1), WINDOW_END + timedelta(days=1))
pad = max(1, int(round((max_count - min_count) * 0.45))) if not counts_flat else max(1, int(max_count * 0.0005))
ax.set_ylim(min_count - pad, max_count + pad)
ax.grid(axis="y", color="#dcdcdc", linewidth=0.7)
ax.grid(axis="x", color="#f0f0f0", linewidth=0.6)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
ax.tick_params(labelsize=8.5, colors="#333333")
fig.text(0.012, 0.015,
         f"注：区间 {n_window} 个自然日中仅 {n_obs} 天有快照数据，未观测日期留为缺口（不按 0 处理），虚线仅连接相邻观测点；"
         "纵轴为局部区间（起点非零），用于比较观测点差异。数据来源：TPCDS 248 仓库期间库存快照。",
         fontsize=7.2, color="#555555", wrap=True)
fig.tight_layout(rect=(0, 0.055, 1, 1))
fig.savefig(chart_path, facecolor="white")
plt.close(fig)
chart_ms = (time.perf_counter() - CHART_T0) * 1000.0
assert os.path.getsize(chart_path) > 0

# ---- derived text --------------------------------------------------------
if counts_flat:
    count_phrase = f"各观测日的库存记录数均为 {max_count:,} 条"
else:
    count_phrase = f"库存记录数在 {min_count:,} 条至 {max_count:,} 条之间"
if n_missing > 0:
    coverage_phrase = (f"但该区间共 {n_window} 个自然日，仅 {n_obs} 天返回快照，{n_missing} 天没有观测，"
                       "因此这是一条不连续的逐日序列，缺失日期不作 0 处理。")
else:
    coverage_phrase = f"该区间 {n_window} 个自然日均返回快照，序列完整。"
delta_dir = "上升" if delta_records > 0 else ("下降" if delta_records < 0 else "持平")
onhand_phrase = (f"现存数量合计（计量单位数据源未声明）在 {exact(min_onhand)} 至 {exact(max_onhand)} 之间，"
                 f"首末快照之差 {exact(delta_onhand)}（{first_rec['snapshot_date']} 起算，{last_rec['snapshot_date']} 止）。")

summary = (f"仓库2 在 1998-01-01 至 1998-01-29（数量下限 0、类别不限）内共有 {n_obs} 个库存快照日（{dates_text}），"
           f"{count_phrase}；{onhand_phrase}{coverage_phrase}")

# ---- tables --------------------------------------------------------------
t_daily_rows = [[r["snapshot_date"], r["sku_record_count"], r["nonzero_sku_count"],
                 r["zero_sku_count"], exact(r["total_on_hand"])] for r in records]
t_daily = {
    "id": "t_daily",
    "title": "仓库2 逐日库存快照明细（1998-01-01 至 1998-01-29，数量下限 0、类别不限）",
    "columns": ["快照日期", "库存记录数（条）", "非零数量记录数", "零数量记录数", "现存数量合计（单位未声明）"],
    "rows": t_daily_rows,
    "scope": f"{REF}：按快照日期分组的库存记录；现存数量合计的计量单位未由数据源声明",
    "dataRefs": [REF],
}
t_cov_rows = [
    ["查询日期范围", "1998-01-01 至 1998-01-29", f"含端点共 {n_window} 个自然日，与要求一致"],
    ["返回快照日期数", n_obs, "按快照日期升序"],
    ["未返回快照的日期数", n_missing, "未观测日期保留为缺口，不补 0、不插值"],
    ["相邻快照间隔（天）", intervals_text, "观测到的快照节奏，数据源未声明快照频率"],
    ["快照日期重复数", len(dup_dates), "按快照日期分组，日期唯一"],
    ["零数量记录数 + 非零数量记录数 = 库存记录数",
     "全部一致" if not consistency_bad else f"{len(consistency_bad)} 个日期不一致",
     "查询结果内部一致性核对"],
    ["现存数量合计最小值 / 最大值", f"{exact(min_onhand)} / {exact(max_onhand)}", "计量单位未由数据源声明"],
    ["结果行数是否受返回上限影响", f"返回 {n_obs} 行，上限 100 行", "返回行数低于上限，未发生截断"],
]
t_cov = {
    "id": "t_coverage",
    "title": "日期覆盖与口径核对",
    "columns": ["核对项", "结果", "说明"],
    "rows": t_cov_rows,
    "scope": f"{REF}：仓库2、1998-01-01 至 1998-01-29、数量下限 0、类别不限",
    "dataRefs": [REF],
}

# ---- metrics -------------------------------------------------------------
metrics = [
    {"id": "m_snapshot_days", "label": "有快照的日期数", "value": str(n_obs), "unit": "天",
     "calculation": "COUNT(DISTINCT snapshot_date)，区间 1998-01-01 至 1998-01-29",
     "dataRefs": [REF]},
    {"id": "m_missing_days", "label": "区间内未返回快照的日期数", "value": str(n_missing), "unit": "天",
     "calculation": f"{n_window}（区间自然日数）− {n_obs}（有快照日期数）",
     "dataRefs": [REF]},
    {"id": "m_records_min", "label": "单日库存记录数最低值", "value": exact(min_count), "unit": "条",
     "calculation": "MIN(sku_record_count)，observed snapshots only",
     "dataRefs": [REF]},
    {"id": "m_records_max", "label": "单日库存记录数最高值", "value": exact(max_count), "unit": "条",
     "calculation": "MAX(sku_record_count)，observed snapshots only",
     "dataRefs": [REF]},
    {"id": "m_onhand_max", "label": "单日现存数量合计最高值", "value": exact(max_onhand), "unit": "",
     "calculation": "MAX(total_on_hand)；计量单位数据源未声明",
     "dataRefs": [REF]},
    {"id": "m_records_change", "label": "首末观测快照库存记录数变动", "value": exact(delta_records), "unit": "条",
     "calculation": f"sku_record_count({last_rec['snapshot_date']}) − sku_record_count({first_rec['snapshot_date']})",
     "dataRefs": [REF]},
]

# ---- sections ------------------------------------------------------------
s_trend = {
    "id": "s_trend",
    "title": "逐日库存记录数趋势",
    "narrative": (f"{n_obs} 个观测快照日上，{count_phrase.replace('各观测日', '每日')}。"
                  f"首末观测点（{first_rec['snapshot_date']} → {last_rec['snapshot_date']}）{delta_dir} "
                  f"{exact(abs(delta_records))} 条记录；相邻观测点间隔为 {intervals_text} 天，"
                  "折线只连接实测点，未观测日期不插值。"),
    "chartIds": ["c_trend"],
    "tableIds": [],
}
s_detail = {
    "id": "s_detail",
    "title": "现存数量合计与记录结构明细",
    "narrative": (f"每个快照日给出库存记录数、按数量是否大于 0 拆分的记录数，以及现存数量合计。"
                  f"现存数量合计最低 {exact(min_onhand)}、最高 {exact(max_onhand)}（{first_rec['snapshot_date']} 起算至 "
                  f"{last_rec['snapshot_date']}，合计差 {exact(delta_onhand)}）；三个数量列均按数据源汇总表达式释义，"
                  "现存数量的计量单位数据源未声明，故不作单位推断。"),
    "chartIds": [],
    "tableIds": ["t_daily"],
}
s_cov = {
    "id": "s_coverage",
    "title": "日期覆盖、口径与一致性核对",
    "narrative": (f"要求的 29 个自然日中只有 {n_obs} 天返回快照，缺口 {n_missing} 天；"
                  "缺口既可能反映数据源仅按非逐日节奏生成快照，也可能反映该仓库当日期无符合条件的记录，"
                  "来源未声明快照生成频率，因此无法判定原因。查询结果内部一致性（零数量 + 非零数量 = 库存记录数）"
                  "全部通过，日期无重复，返回值未触及行数上限。"),
    "chartIds": [],
    "tableIds": ["t_coverage"],
}
sections = [s_trend, s_detail, s_cov]

md = []
md.append("# 仓库2 逐日库存快照（1998-01-01 至 1998-01-29｜数量下限 0｜类别不限）")
md.append("")
md.append(f"**结论**：{summary}")
md.append("")
md.append("## 一、关键发现")
md.append("")
md.append(f"- 区间内共 {n_obs} 个快照日（{dates_text}），相邻观测点间隔 {intervals_text} 天；"
          f"29 个自然日中有 {n_missing} 天没有快照数据。")
md.append(f"- {count_phrase}；最低值出现在 {min(r['snapshot_date'] for r in records if r['sku_record_count'] == min_count)}，"
          f"最高值出现在 {max(r['snapshot_date'] for r in records if r['sku_record_count'] == max_count)}。")
md.append(f"- 现存数量合计（计量单位数据源未声明）位于 {exact(min_onhand)} 至 {exact(max_onhand)} 之间，"
          f"首末快照（{first_rec['snapshot_date']} → {last_rec['snapshot_date']}）之差为 {exact(delta_onhand)}。")
md.append(f"- 首末观测快照的库存记录数{delta_dir} {exact(abs(delta_records))} 条；"
          f"该变动只覆盖 {n_obs} 个观测点，不足以推断趋势方向或业务原因。")
md.append("")
md.append("## 二、逐日库存记录数（折线图）")
md.append("")
md.append(f"![仓库2 库存记录数（观测快照日）]({chart_name})")
md.append("")
md.append(f"- 横轴：快照日期（1998-01-01 至 1998-01-29）；纵轴：库存记录数（条）。")
md.append(f"- 仅绘制 {n_obs} 个实测快照日；未观测日期留为缺口、不按 0 处理，虚线仅连接相邻实测点。")
md.append("- 纵轴为局部区间（起点非零），用于比较各观测点差异；条数读数据以本页明细表为准。")
md.append("")
md.append("## 三、逐日快照明细表")
md.append("")
md.append("| " + " | ".join(t_daily["columns"]) + " |")
md.append("|" + "---|" * len(t_daily["columns"]))
for row in t_daily_rows:
    md.append("| " + " | ".join(f"{c:,}" if isinstance(c, int) else str(c) for c in row) + " |")
md.append("")
md.append("说明：现存数量合计的计量单位未由数据源声明，故表头标注“单位未声明”，不作推断；"
          "“非零/零数量记录数”为按现有数量是否大于 0、等于 0 拆分的记录条数。")
md.append("")
md.append("## 四、日期覆盖与口径核对")
md.append("")
md.append("| " + " | ".join(t_cov["columns"]) + " |")
md.append("|" + "---|" * len(t_cov["columns"]))
for row in t_cov_rows:
    md.append("| " + " | ".join(str(c) for c in row) + " |")
md.append("")
md.append("## 五、口径、限制与说明")
md.append("")
md.append(f"1. 查询实际生效参数：仓库 2、1998-01-01 至 1998-01-29（含端点）、数量下限 0、类别不限，与需求一致。")
md.append(f"2. 逐日序列不完整：29 个自然日仅 {n_obs} 天有快照，缺口 {n_missing} 天；来源未声明快照生成频率，缺口原因无法判定，"
          "报告中不将缺口视为 0，也不插值。")
md.append("3. 计量单位限制：现存数量合计的单位未由数据源声明，因此未推测为“件”“个”或其他单位，也不与任何金额口径混用。")
md.append("4. 记录数口径：库存记录数按数据源逐日明细计数（COUNT(*)），不是去重商品/SKU 数量；来源未提供去重商品口径。")
md.append("5. 类别口径：类别筛选为“不限”，结果未按类别拆分，不能据此给出类别结构结论。")
md.append("6. 汇总合计的解读：现存数量合计为各快照日的时点存量，跨日期相加没有业务意义，故未提供区间“总合计”，"
          "仅给出每日数值及首末时点差。")
md.append(f"7. 样本限制：仅 {n_obs} 个时点，无法据此判断长期库存水平、波动规律或因果。")
md.append("")
md.append("## 六、字段来源与口径对应（简明）")
md.append("")
md.append("| 源字段 | 采用含义 | 声明来源 | 状态 |")
md.append("|---|---|---|---|")
md.append("| snapshot_date | 库存快照日期（按日分组的日期维度日期） | 执行查询定义（TPCDS 248 仓库期间库存快照模板） | 已确认 |")
md.append("| sku_record_count | 该快照日的库存记录数（明细计数） | 执行查询定义 | 已确认 |")
md.append("| nonzero_sku_count | 该日现存数量大于 0 的记录数 | 执行查询定义 | 已确认 |")
md.append("| zero_sku_count | 该日现存数量等于 0 的记录数 | 执行查询定义 | 已确认 |")
md.append("| total_on_hand | 该日现存数量合计（单位未声明） | 执行查询定义 | 已确认（含义）/ 单位未声明 |")
md.append("")
report_markdown = "\n".join(md)

# ---- visual report -------------------------------------------------------
limitations = [
    f"要求的 29 个自然日中只有 {n_obs} 天返回快照（缺口 {n_missing} 天），逐日序列不完整；数据源未声明快照生成频率，"
    "无法判定缺口属于来源节奏还是当日期无符合条件记录。",
    "现存数量合计的计量单位未由数据源声明，报告不作单位推断，也不与任何货币或计量口径互换。",
    "库存记录数为明细记录计数（COUNT(*)），不等于去重商品/SKU 数量；来源未提供去重口径指标。",
    f"仅为单仓库、单一时段的 {n_obs} 个时点快照，不能据此推断长期库存趋势、季节性、类别结构或因果。",
    "未提供记录级明细，只能核对聚合结果内部一致性（零数量 + 非零数量 = 库存记录数），无法逐步审计原始记录。",
]
data_sources = [REF]
validation_checks = [
    {"name": "输入文件与行数校验", "status": "PASSED",
     "detail": f"清单与数据文件校验值一致；解码后 {len(rows)} 行，与声明行数一致。"},
    {"name": "来源引用一致性", "status": "PASSED",
     "detail": "全部记录指向同一授权查询结果引用，与模板参数绑定一致。"},
    {"name": "快照日期唯一性与区间校验",
     "status": "PASSED" if (not dup_dates and in_window) else "FAILED",
     "detail": (f"日期重复数 {len(dup_dates)}；"
                + ("全部落在 1998-01-01 至 1998-01-29 内" if in_window else "存在落在要求区间外的日期")
                + f"（共 {n_obs} 个日期）。")},
    {"name": "结果内部一致性（零数量 + 非零数量 = 库存记录数）",
     "status": "PASSED" if not consistency_bad else "FAILED",
     "detail": "全部日期一致。" if not consistency_bad else f"{len(consistency_bad)} 个日期不一致。"},
    {"name": "现存数量合计非负校验",
     "status": "PASSED" if nonneg_ok else "FAILED",
     "detail": "在数量下限 0 的筛选下，各日现存数量合计均非负。"},
    {"name": "日期覆盖完整性", "status": "UNRESOLVED" if n_missing else "PASSED",
     "detail": (f"29 个自然日中 {n_missing} 天无快照记录，来源未声明快照频率，缺口成因无法由现有证据判定。"
                if n_missing else "区间内每个自然日均有快照。")},
    {"name": "计量单位声明", "status": "UNRESOLVED",
     "detail": "数据源明确未声明现存数量的计量单位，报告保留为单位未声明，不作推断。"},
    {"name": "图表渲染", "status": "PASSED",
     "detail": f"折线图已生成（{os.path.getsize(chart_path)} 字节），中文标签使用运行时默认字体。"},
]
check_states = [c["status"] for c in validation_checks]
report_status = "SUCCESS" if all(s == "PASSED" for s in check_states) else "PARTIAL"

charts = [{
    "id": "c_trend",
    "title": "仓库2 库存记录数（仅观测快照日，1998-01-01 至 1998-01-29）",
    "kind": "png",
    "chartType": "line",
    "fileName": chart_name,
    "caption": (f"横轴=快照日期，纵轴=库存记录数（条）；数据来源 TPCDS 248 仓库期间库存快照，"
                f"仓库2、数量下限 0、类别不限。仅 {n_obs} 个观测日有数据，未观测日期为缺口（不按 0 处理），"
                f"纵轴为局部区间（起点非零，范围 {min_count - pad} 至 {max_count + pad} 条）。"),
    "dataRefs": [REF],
}]

visual_report = {
    "schemaVersion": "python_visual_report.v1",
    "title": "仓库2 逐日库存快照（1998-01-01 至 1998-01-29）",
    "summary": summary,
    "status": report_status,
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": [t_daily, t_cov],
    "dataSources": data_sources,
    "validation": {"checks": validation_checks, "warnings": [
        f"{n_missing} 个自然日没有快照记录，折线图仅连接实测点。",
        "现存数量合计的计量单位数据源未声明。",
    ]},
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable",
                     "autoCharts": True, "autoImages": True},
    "layout": {
        "schemaVersion": "report_layout.v1",
        "blocks": [
            {"id": "b_summary", "type": "summary", "span": "full", "emphasis": "important"},
            {"id": "b_m1", "type": "metric", "ref": "m_snapshot_days", "span": "half"},
            {"id": "b_m2", "type": "metric", "ref": "m_missing_days", "span": "half", "emphasis": "important"},
            {"id": "b_m3", "type": "metric", "ref": "m_records_min", "span": "half"},
            {"id": "b_m4", "type": "metric", "ref": "m_records_max", "span": "half"},
            {"id": "b_m5", "type": "metric", "ref": "m_onhand_max", "span": "half"},
            {"id": "b_m6", "type": "metric", "ref": "m_records_change", "span": "half"},
            {"id": "b_sec_trend", "type": "section", "ref": "s_trend", "span": "full"},
            {"id": "b_chart_trend", "type": "chart", "ref": "c_trend", "span": "full", "emphasis": "important"},
            {"id": "b_sec_detail", "type": "section", "ref": "s_detail", "span": "full"},
            {"id": "b_tbl_daily", "type": "table", "ref": "t_daily", "span": "full"},
            {"id": "b_sec_cov", "type": "section", "ref": "s_coverage", "span": "full"},
            {"id": "b_tbl_cov", "type": "table", "ref": "t_coverage", "span": "full"},
            {"id": "b_limits", "type": "limitations", "span": "full"},
            {"id": "b_sources", "type": "sources", "span": "full"},
        ],
    },
    "timings": {"analysisMs": round((CHART_T0 - T0) * 1000.0, 3),
                "chartMs": round(chart_ms, 3)},
}

field_provenance = [
    {"datasetReference": REF, "sourceField": "snapshot_date",
     "meaning": "库存快照日期（date_dim.d_date，按该日期分组）",
     "declarationSource": "sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT，SELECT dd.d_date AS snapshot_date）",
     "status": "CONFIRMED"},
    {"datasetReference": REF, "sourceField": "sku_record_count",
     "meaning": "该快照日的库存记录数（COUNT(*)，inventory 明细行计数）",
     "declarationSource": "sourceDefinition.queryDefinition（COUNT(*) AS sku_record_count）",
     "status": "CONFIRMED"},
    {"datasetReference": REF, "sourceField": "nonzero_sku_count",
     "meaning": "该快照日现存数量大于 0 的记录数（SUM(CASE WHEN inv_quantity_on_hand > 0 THEN 1 ELSE 0 END)）",
     "declarationSource": "sourceDefinition.queryDefinition（nonzero_sku_count 表达式）",
     "status": "CONFIRMED"},
    {"datasetReference": REF, "sourceField": "zero_sku_count",
     "meaning": "该快照日现存数量等于 0 的记录数（SUM(CASE WHEN inv_quantity_on_hand = 0 THEN 1 ELSE 0 END)）",
     "declarationSource": "sourceDefinition.queryDefinition（zero_sku_count 表达式）",
     "status": "CONFIRMED"},
    {"datasetReference": REF, "sourceField": "total_on_hand",
     "meaning": "该快照日现存数量合计（SUM(inv.inv_quantity_on_hand)）；计量单位来源明确未声明",
     "declarationSource": "sourceDefinition.queryDefinition 与 displayName/description（MCP 模板声明：数量单位未由来源声明）",
     "status": "CONFIRMED"},
]

row = {
    "reportMarkdown": report_markdown,
    "evidenceReferences": [REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_provenance,
    "visualReport": visual_report,
}
with open(os.path.join(OUT_DIR, "rows.jsonl"), "w", encoding="utf-8") as fh:
    fh.write(json.dumps(row, ensure_ascii=False) + "\n")

with open(os.path.join(VAL_DIR, "diagnostics.json"), "w", encoding="utf-8") as fh:
    json.dump({"manifestSha": manifest_sha, "parts": part_receipts, "rows": len(rows),
               "observedDates": [r["snapshot_date"] for r in records],
               "missingDates": [d.isoformat() for d in missing_dates],
               "intervals": intervals, "consistencyBad": consistency_bad,
               "status": report_status}, fh, ensure_ascii=False, indent=2)

print(json.dumps({"rows": len(rows), "n_obs": n_obs, "missing": n_missing,
                  "counts": counts, "onhands": [exact(v) for v in onhands],
                  "chart": chart_name, "status": report_status}, ensure_ascii=False))
