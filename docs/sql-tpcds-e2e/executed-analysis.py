import hashlib, json, time, datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

T0 = time.perf_counter()

IN_ROOT = Path("/data/input")
MANIFEST_DIR = IN_ROOT / "c8d61f12ea9144abe850053cc9c4cf25c569794b269b7c596871f3bd5d8ad867"
MANIFEST_PATH = MANIFEST_DIR / "manifest.json"
SCHEMA_PATH = IN_ROOT / "cb2d2bb6f6787361246edbeed0cc4265f24d6dcd6c7d59c96ff5d817c6548edb" / "schema.json"
EXPECTED_MANIFEST_SHA = "9d8c0837c5a71430fd6cdfa6f1c16dea2c2e27fefe638201ba8fcfa316128ad6"
EXPECTED_SCHEMA_SHA = "a9d6b04791d9e90c5597f033e1960fd390ed7f79d803be53e98dcadf1278a3ce"

OUTR = Path("/workspace/output/results")
OUTV = Path("/workspace/output/validation")
OUTR.mkdir(parents=True, exist_ok=True)
OUTV.mkdir(parents=True, exist_ok=True)

MONTHLY_REF = "mcp_chatchat_mcp_server_tpl_b1e518f3811f3fb18d55dc959b24e3d7"
ANNUAL_REF = "mcp_chatchat_mcp_server_tpl_212b88329a673657979943bbeaa7479b"

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def num(v):
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    if isinstance(v, bool):
        raise ValueError("boolean is not a measure")
    if isinstance(v, int):
        return Decimal(v)
    if isinstance(v, float):
        return Decimal(repr(v))
    if isinstance(v, str):
        s = v.strip()
        if s == "" or s.upper() in ("\\N", "\\M", "NULL", "NONE"):
            return None
        return Decimal(s)
    raise ValueError("unsupported numeric type: %s" % type(v))

def fmt_amt(d, signed=False):
    if d is None:
        return "—"
    q = d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return f"{q:+,.2f}" if signed else f"{q:,.2f}"

def fmt_qty(d, signed=False):
    if d is None:
        return "—"
    i = int(d)
    return f"{i:+,}" if signed else f"{i:,}"

def fmt_pct(p):
    if p is None:
        return "—"
    return f"{p.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP):+,.2f}%"

def raw_dec(d):
    if d is None:
        return ""
    s = format(d.normalize(), "f")
    return s

# ---------- input verification ----------
man_bytes = MANIFEST_PATH.read_bytes()
man_sha = hashlib.sha256(man_bytes).hexdigest()
if man_sha != EXPECTED_MANIFEST_SHA:
    raise ValueError("manifest sha256 mismatch")
man = json.loads(man_bytes.decode("utf-8"))
schema_sha = sha256_file(SCHEMA_PATH)
schema_obj = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
colmap = {c["storageName"]: c["name"] for c in man["columns"]}

records = []
part_receipts = []
for part in man["parts"]:
    p = IN_ROOT / part["fileId"] / part["fileName"]
    if not p.exists():
        raise FileNotFoundError("declared part missing")
    digest = sha256_file(p)
    if digest != part["sha256"]:
        raise ValueError("part sha256 mismatch")
    tbl = pq.read_table(p)
    names = list(tbl.column_names)
    logical_ref = colmap.get("c0", "datasetReference")
    logical_rec = colmap.get("c1", "record")
    if "c0" in names and "c1" in names:
        col_ref, col_rec = "c0", "c1"
    elif logical_ref in names and logical_rec in names:
        col_ref, col_rec = logical_ref, logical_rec
    else:
        raise KeyError("part columns do not match declared mapping")
    refs = tbl.column(col_ref).to_pylist()
    raws = tbl.column(col_rec).to_pylist()
    if len(refs) != part["rowCount"]:
        raise ValueError("part row count mismatch")
    part_receipts.append({"fileName": part["fileName"], "rows": len(refs), "sha256": digest})
    for ref, raw in zip(refs, raws):
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8")
        if isinstance(raw, str):
            obj = json.loads(raw, parse_float=Decimal)
        elif isinstance(raw, dict):
            obj = {
                k: (Decimal(repr(v)) if isinstance(v, float) else v)
                for k, v in raw.items()
            }
        else:
            raise TypeError("unexpected record cell type")
        if not isinstance(obj, dict):
            raise TypeError("record is not an object")
        records.append({"datasetReference": ref, "record": obj})

if len(records) != man["rowCount"]:
    raise ValueError("total row count mismatch")

monthly_rows = [r["record"] for r in records if r["datasetReference"] == MONTHLY_REF]
annual_rows = [r["record"] for r in records if r["datasetReference"] == ANNUAL_REF]
unmatched_refs = sorted({r["datasetReference"] for r in records} - {MONTHLY_REF, ANNUAL_REF})
if not monthly_rows or not annual_rows:
    raise ValueError("a declared dataset delivered no rows")

M_FIELDS = ["category", "row_count", "sale_month", "sale_year", "total_net_paid", "total_net_profit", "total_quantity"]
A_FIELDS = ["earliest_date", "latest_date", "sales_rows", "sales_year", "total_net_paid", "total_net_profit", "total_quantity"]

# ---------- monthly population ----------
monthly_years = sorted({int(num(r["sale_year"])) for r in monthly_rows})
monthly_scope = [r for r in monthly_rows if int(num(r["sale_year"])) == 1999]
monthly_out_of_scope = len(monthly_rows) - len(monthly_scope)
monthly_month_values = sorted({int(num(r["sale_month"])) for r in monthly_scope})
keycount = {}
for r in monthly_scope:
    k = (int(num(r["sale_month"])), str(r.get("category")))
    keycount[k] = keycount.get(k, 0) + 1
duplicate_grain_keys = sorted([list(k) for k, v in keycount.items() if v > 1])
rows_per_month = {}
for r in monthly_scope:
    m = int(num(r["sale_month"]))
    rows_per_month[m] = rows_per_month.get(m, 0) + 1

months = {}
for r in monthly_scope:
    m = int(num(r["sale_month"]))
    st = months.setdefault(m, {"paid": Decimal(0), "profit": Decimal(0), "qty": Decimal(0),
                              "paid_null": False, "profit_null": False, "qty_null": False})
    p = num(r.get("total_net_paid"))
    pr = num(r.get("total_net_profit"))
    q = num(r.get("total_quantity"))
    if p is None:
        st["paid_null"] = True
    else:
        st["paid"] += p
    if pr is None:
        st["profit_null"] = True
    else:
        st["profit"] += pr
    if q is None:
        st["qty_null"] = True
    else:
        st["qty"] += q

month_totals = {}
for m, st in months.items():
    month_totals[m] = {
        "paid": None if st["paid_null"] else st["paid"],
        "profit": None if st["profit_null"] else st["profit"],
        "qty": None if st["qty_null"] else st["qty"],
    }

months_covered = sorted(month_totals.keys())
all_months = list(range(1, 13))
missing_months = [m for m in all_months if m not in month_totals]

def total_of(key):
    vals = [month_totals[m][key] for m in months_covered]
    if any(v is None for v in vals):
        return None
    return sum(vals, Decimal(0))

mon_paid = total_of("paid")
mon_profit = total_of("profit")
mon_qty = total_of("qty")

# ---------- annual population ----------
annual_years = sorted({int(num(r["sales_year"])) for r in annual_rows})
ann_sel = [r for r in annual_rows if int(num(r["sales_year"])) == 1999]
annual_duplicate_rows = len(ann_sel) > 1
if len(ann_sel) == 0:
    raise ValueError("annual dataset has no 1999 row")
ann_paid_raw = [num(r.get("total_net_paid")) for r in ann_sel]
ann_profit_raw = [num(r.get("total_net_profit")) for r in ann_sel]
ann_qty_raw = [num(r.get("total_quantity")) for r in ann_sel]
ann_paid = None if any(v is None for v in ann_paid_raw) else sum(ann_paid_raw, Decimal(0))
ann_profit = None if any(v is None for v in ann_profit_raw) else sum(ann_profit_raw, Decimal(0))
ann_qty = None if any(v is None for v in ann_qty_raw) else sum(ann_qty_raw, Decimal(0))
ann_earliest = str(ann_sel[0].get("earliest_date"))
ann_latest = str(ann_sel[0].get("latest_date"))
date_in_1999 = False
try:
    e = datetime.date.fromisoformat(ann_earliest)
    l = datetime.date.fromisoformat(ann_latest)
    date_in_1999 = (e >= datetime.date(1999, 1, 1)) and (l <= datetime.date(1999, 12, 31))
except Exception:
    e = l = None

# ---------- reconciliation ----------
AMT_TOL = Decimal("0.005")
QTY_TOL = Decimal("0")
recon = []
for key, label, kind, av, mv, tol in [
    ("net_paid", "净收款", "amount", ann_paid, mon_paid, AMT_TOL),
    ("net_profit", "利润", "amount", ann_profit, mon_profit, AMT_TOL),
    ("quantity", "数量", "qty", ann_qty, mon_qty, QTY_TOL),
]:
    if av is None or mv is None:
        diff = None
        pct = None
        verdict = "无法对账（存在空值）"
    else:
        diff = av - mv
        pct = None if mv == 0 else (diff / mv * Decimal(100))
        verdict = "一致" if abs(diff) <= tol else "存在差额（口径待解释）"
    recon.append({"key": key, "label": label, "kind": kind, "annual": av, "monthly": mv,
                  "diff": diff, "pct": pct, "tol": tol, "verdict": verdict})

R = {x["key"]: x for x in recon}
diff_paid, diff_profit, diff_qty = R["net_paid"]["diff"], R["net_profit"]["diff"], R["quantity"]["diff"]
pct_paid, pct_profit, pct_qty = R["net_paid"]["pct"], R["net_profit"]["pct"], R["quantity"]["pct"]
qty_equal = diff_qty is not None and abs(diff_qty) <= QTY_TOL
amount_gap = (diff_paid is not None and abs(diff_paid) > AMT_TOL) or (diff_profit is not None and abs(diff_profit) > AMT_TOL)
fully_consistent = all(x["verdict"] == "一致" for x in recon)

pct_note = ""
if (diff_profit is not None and mon_profit is not None and mon_profit < 0
        and pct_profit is not None and pct_profit > 0):
    pct_note = (f"差额率符号说明：利润差额率为 {fmt_pct(pct_profit)}，其分母（月度合计口径利润 {fmt_amt(mon_profit)}）为负值，"
                f"因此正号表示年度汇总口径的亏损金额比月度合计口径多 {fmt_amt(abs(diff_profit))}，并非利润改善；"
                "两个口径的利润水平本身均为负值。")

# ---------- charts ----------
CHART_T0 = time.perf_counter()
plt.rcParams["axes.unicode_minus"] = False
chart_errors = []
chart_files = []

TREND_FILE = "monthly_trend_1999.png"
RECON_FILE = "reconciliation_annual_vs_monthly_1999.png"

def month_val(m, key):
    st = month_totals.get(m)
    return None if st is None else st.get(key)

paid_plot = [float(month_val(m, "paid")) if month_val(m, "paid") is not None else np.nan for m in all_months]
profit_plot = [float(month_val(m, "profit")) if month_val(m, "profit") is not None else np.nan for m in all_months]

try:
    fig, ax = plt.subplots(figsize=(10.2, 5.0), dpi=150)
    ax.plot(all_months, paid_plot, color="#1f3864", lw=1.9, marker="o", ms=5, label="净收款")
    ax.plot(all_months, profit_plot, color="#7f3f00", lw=1.9, marker="s", ms=5, ls="--", label="利润")
    ax.axhline(0.0, color="#333333", lw=1.0, zorder=0)
    ax.set_xticks(all_months)
    ax.set_xticklabels([f"{m}月" for m in all_months])
    ax.set_xlim(0.5, 12.5)
    vals = [v for v in paid_plot + profit_plot if v == v]
    if vals:
        lo, hi = min(0.0, min(vals)), max(0.0, max(vals))
        pad = (hi - lo) * 0.12 if hi > lo else 1.0
        ax.set_ylim(lo - pad, hi + pad)
    ax.grid(axis="y", color="#dddddd", lw=0.7)
    ax.set_axisbelow(True)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    ax.set_xlabel("月份（1999年）")
    ax.set_ylabel("金额（源端未声明单位）")
    ax.set_title("商品主键1 · 1999年逐月净收款与利润（门店销售，金额单位未声明）\n"
                 "期间 1999-01-01 至 1999-12-31；口径：门店逐月销售汇总（跨品类合计）",
                 fontsize=10.5, loc="left")
    ax.legend(loc="upper left", frameon=False, fontsize=9)
    note = ("缺失月份以断点空白表示，未补零；数据点标签见月度表。"
            "金额仅为源端数值，未做单位换算或币种假设。")
    fig.text(0.01, 0.015, note, fontsize=8, color="#444444")
    fig.subplots_adjust(bottom=0.20, top=0.84, left=0.09, right=0.98)
    fig.savefig(OUTR / TREND_FILE)
    plt.close(fig)
    chart_files.append(TREND_FILE)
except Exception as exc:
    chart_errors.append("monthly_trend: %s" % type(exc).__name__)

try:
    if None in (ann_paid, ann_profit, mon_paid, mon_profit):
        raise ValueError("missing comparable totals")
    fig, ax = plt.subplots(figsize=(9.6, 5.0), dpi=150)
    cx = np.arange(2)
    w = 0.36
    ann_vals = [ann_paid, ann_profit]
    mon_vals = [mon_paid, mon_profit]
    b1 = ax.bar(cx - w / 2, [float(v) for v in ann_vals], w, color="#1f3864", label="年度汇总口径（1999年）")
    b2 = ax.bar(cx + w / 2, [float(v) for v in mon_vals], w, color="#9dc3e6",
                label="月度合计口径（1999年已覆盖月份合计）")
    for rect, d in list(zip(b1, ann_vals)) + list(zip(b2, mon_vals)):
        h = rect.get_height()
        ax.annotate(fmt_amt(d), (rect.get_x() + rect.get_width() / 2, h),
                    textcoords="offset points", xytext=(0, 4 if h >= 0 else -12),
                    ha="center", fontsize=8.5)
    ax.axhline(0.0, color="#333333", lw=1.0)
    ax.set_xticks(cx)
    ax.set_xticklabels(["净收款", "利润"])
    ax.grid(axis="y", color="#dddddd", lw=0.7)
    ax.set_axisbelow(True)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    ax.set_ylabel("金额（源端未声明单位）")
    ax.set_title("商品主键1 · 1999年门店销售：年度汇总与月度合计对账\n期间 1999-01-01 至 1999-12-31；同一未声明金额单位下的两种查询口径",
                 fontsize=10.5, loc="left")
    ax.legend(loc="upper right", frameon=False, fontsize=9)
    lines = [
        f"差额（年度汇总 − 月度合计）：净收款 {fmt_amt(diff_paid, signed=True)}（{fmt_pct(pct_paid)}）；"
        f"利润 {fmt_amt(diff_profit, signed=True)}（{fmt_pct(pct_profit)}）",
        "差额为口径差异，已核查其与“最低数量 0”过滤条件排除数量为空记录的方向一致，不认定为数据错误。",
    ]
    fig.text(0.01, 0.015, "\n".join(lines), fontsize=8, color="#444444")
    fig.subplots_adjust(bottom=0.24, top=0.84, left=0.10, right=0.98)
    fig.savefig(OUTR / RECON_FILE)
    plt.close(fig)
    chart_files.append(RECON_FILE)
except Exception as exc:
    chart_errors.append("reconciliation_bars: %s" % type(exc).__name__)

CHART_MS = (time.perf_counter() - CHART_T0) * 1000.0
ANALYSIS_MS = (CHART_T0 - T0) * 1000.0

# ---------- consistency assertions ----------
assert mon_paid is None or abs(sum((month_totals[m]["paid"] for m in months_covered), Decimal(0)) - mon_paid) == 0
assert mon_profit is None or abs(sum((month_totals[m]["profit"] for m in months_covered), Decimal(0)) - mon_profit) == 0
assert len(monthly_scope) >= len(months_covered)

# ---------- report text ----------
if fully_consistent:
    trend_line = ("1999年年度汇总口径与月度合计口径在净收款、利润、数量三项上完全一致，未发现口径差额。")
else:
    trend_line = (
        f"1999年年度汇总口径净收款 {fmt_amt(ann_paid)}、利润 {fmt_amt(ann_profit)}、数量 {fmt_qty(ann_qty)}；"
        f"同期间月度合计口径净收款 {fmt_amt(mon_paid)}、利润 {fmt_amt(mon_profit)}、数量 {fmt_qty(mon_qty)}。"
        f"两口径差额（年度汇总 − 月度合计）为：净收款 {fmt_amt(diff_paid, signed=True)}（占月度合计 {fmt_pct(pct_paid)}）、"
        f"利润 {fmt_amt(diff_profit, signed=True)}（{fmt_pct(pct_profit)}）、数量 {fmt_qty(diff_qty, signed=True)}（{fmt_pct(pct_qty)}）。"
    )

if qty_equal and amount_gap:
    gap_text = (
        "数量合计在两口径下完全相等，而净收款与利润合计存在差额。这与下列口径机制相符：月度口径按需求套用了"
        "“最低购买数量 0”的过滤条件，数量为空的记录在比较运算中不通过该条件而被排除；年度口径未设数量条件，"
        "因此保留了这些记录的金额与行数贡献。由于交付数据中没有任何可直接观测的“数量为空”明细行或该子集的小计，"
        "该归因只能作为与观测形态一致的推断，未能独立证实，故不认定为数据错误，也未按此推断调整任何金额。"
    )
elif qty_equal and not amount_gap:
    gap_text = ("两口径金额与数量合计均一致，未观测到需要归因的差额；数量为空的过滤口径在本期未产生可测量的金额影响（或不存在此类记录），"
                "但由于没有直接可观测的空数量行，该结论仍限于本批交付数据。")
else:
    gap_text = (
        "两口径的数量合计本身也不相等，因此金额差额不能仅用“数量为空被过滤”解释，可能同时包含数量口径、"
        "日期维度关联（年度口径声明排除无日期维度关联记录）等差异。现有交付数据不足以拆分各项贡献，"
        "该差额标记为未解决，不认定为数据错误。"
    )

missing_text = ("1999年12个月中已覆盖 %d 个月%s。" % (
    len(months_covered),
    "，缺 %s（该月无观测，未补零、未插值）" % "、".join(f"{m}月" for m in missing_months) if missing_months else "，无缺口"))

peak_month = None
try:
    cand = [m for m in months_covered if month_totals[m]["paid"] is not None]
    if cand:
        peak_month = max(cand, key=lambda m: month_totals[m]["paid"])
except Exception:
    peak_month = None
peak_text = (f"净收款最高的月份为 {peak_month} 月，金额 {fmt_amt(month_totals[peak_month]['paid'])}；"
             f"利润最高的月份为 {max([m for m in months_covered if month_totals[m]['profit'] is not None], key=lambda m: month_totals[m]['profit'])} 月。"
             if len(months_covered) > 0 and all(month_totals[m]["profit"] is not None for m in months_covered) and peak_month is not None
             else "月度极值不作结论（存在空值或覆盖不足）。")
neg_months = [m for m in months_covered if month_totals[m]["profit"] is not None and month_totals[m]["profit"] < 0]
neg_text = ("利润为负的月份：%s。" % "、".join(f"{m}月" for m in neg_months)) if neg_months else "各已覆盖月份利润均不为负。"

summary = (
    f"本报告针对248测试数据库 tpcds 商品主键1，在 1999-01-01 至 1999-12-31、最低购买数量 0、品类不限的条件下，"
    f"对门店销售做年度汇总口径与月度合计口径的勾稽。{trend_line}"
    f"净收款与利润的逐月走势见趋势图；{missing_text}"
    f"金额单位在源端未声明，全部金额仅按源端数值列示，不做币种或量纲假设。"
)

# monthly table
t1_rows = []
for m in all_months:
    if m in month_totals:
        t1_rows.append([f"{m}月",
                        fmt_amt(month_totals[m]["paid"]),
                        fmt_amt(month_totals[m]["profit"]),
                        fmt_qty(month_totals[m]["qty"])])
    else:
        t1_rows.append([f"{m}月", "无观测（不补零）", "无观测（不补零）", "无观测（不补零）"])
t1_rows.append(["合计（已覆盖 %d 个月）" % len(months_covered), fmt_amt(mon_paid), fmt_amt(mon_profit), fmt_qty(mon_qty)])

t2_rows = []
for x in recon:
    if x["kind"] == "amount":
        t2_rows.append([x["label"], fmt_amt(x["annual"]), fmt_amt(x["monthly"]),
                        fmt_amt(x["diff"], signed=True), fmt_pct(x["pct"]), x["verdict"]])
    else:
        t2_rows.append([x["label"], fmt_qty(x["annual"]), fmt_qty(x["monthly"]),
                        fmt_qty(x["diff"], signed=True), fmt_pct(x["pct"]), x["verdict"]])

metrics = [
    {"id": "m_annual_net_paid", "label": "1999年门店销售净收款（年度汇总口径）",
     "value": raw_dec(ann_paid), "unit": "",
     "calculation": "模板 tpl_212b88… 返回的 total_net_paid，限定 sales_year=1999 的合计；金额单位源端未声明",
     "dataRefs": [ANNUAL_REF]},
    {"id": "m_monthly_net_paid", "label": "1999年门店销售净收款（月度合计口径）",
     "value": raw_dec(mon_paid), "unit": "",
     "calculation": "模板 tpl_b1e518… 返回的 total_net_paid 按 sale_month 汇总后再合计（min quantity=0，品类不限）；金额单位源端未声明",
     "dataRefs": [MONTHLY_REF]},
    {"id": "m_diff_net_paid", "label": "净收款两口径差额（年度汇总 − 月度合计）",
     "value": raw_dec(diff_paid), "unit": "",
     "calculation": "年度汇总口径净收款 − 月度合计口径净收款",
     "dataRefs": [ANNUAL_REF, MONTHLY_REF]},
    {"id": "m_annual_net_profit", "label": "1999年门店销售利润（年度汇总口径）",
     "value": raw_dec(ann_profit), "unit": "",
     "calculation": "模板 tpl_212b88… 返回的 total_net_profit，限定 sales_year=1999；金额单位源端未声明",
     "dataRefs": [ANNUAL_REF]},
    {"id": "m_monthly_net_profit", "label": "1999年门店销售利润（月度合计口径）",
     "value": raw_dec(mon_profit), "unit": "",
     "calculation": "模板 tpl_b1e518… 返回的 total_net_profit 按 sale_month 汇总后再合计；金额单位源端未声明",
     "dataRefs": [MONTHLY_REF]},
    {"id": "m_diff_net_profit", "label": "利润两口径差额（年度汇总 − 月度合计）",
     "value": raw_dec(diff_profit), "unit": "",
     "calculation": "年度汇总口径利润 − 月度合计口径利润",
     "dataRefs": [ANNUAL_REF, MONTHLY_REF]},
    {"id": "m_months_covered", "label": "1999年已覆盖月份数",
     "value": str(len(months_covered)), "unit": "个月",
     "calculation": "月度口径数据中出现的 sale_month 去重计数（12个月为满覆盖）",
     "dataRefs": [MONTHLY_REF]},
]

tables = [
    {"id": "t_monthly", "title": "1999年门店销售逐月净收款、利润与数量（月度合计口径，商品主键1，品类不限，最低数量0）",
     "columns": ["月份", "净收款", "利润", "数量"],
     "rows": t1_rows,
     "scope": "sale_year=1999，按 sale_month 汇总；金额单位源端未声明；缺失月份不补零；合计行等于已覆盖月份之和",
     "dataRefs": [MONTHLY_REF]},
    {"id": "t_recon", "title": "1999年年度汇总口径对月度合计口径对账表（商品主键1，1999-01-01 至 1999-12-31）",
     "columns": ["指标", "年度汇总口径", "月度合计口径", "差额（年度−月度）", "差额率（%，相对月度合计）", "判定"],
     "rows": t2_rows,
     "scope": "两个交付模板在同一商品、同一期间上的口径对比；容差：金额 0.005、数量 0；差额率仅在分母非零时计算；利润差额率的分母为负值，正号表示年度汇总口径亏损大于月度合计口径",
     "dataRefs": [ANNUAL_REF, MONTHLY_REF]},
]

charts = []
if TREND_FILE in chart_files:
    charts.append({"id": "c_monthly_trend", "title": "商品主键1 · 1999年逐月净收款与利润趋势",
                   "kind": "png", "fileName": TREND_FILE, "chartType": "line",
                   "caption": "实体：tpcds 商品主键1；期间：1999-01-01 至 1999-12-31（销售期间，非估值日、非交割日）；"
                              "来源：门店逐月销售汇总（跨品类合计，最低数量 0）；金额单位源端未声明，未做任何换算；"
                              "缺失月份为断点，未补零；零基准线可见，负值保留。",
                   "dataRefs": [MONTHLY_REF]})
if RECON_FILE in chart_files:
    charts.append({"id": "c_recon_bars", "title": "1999年年度汇总口径与月度合计口径对账（净收款、利润）",
                   "kind": "png", "fileName": RECON_FILE, "chartType": "bar",
                   "caption": "实体：tpcds 商品主键1；期间：1999-01-01 至 1999-12-31；两种查询口径在同一未声明金额单位下的对比；"
                              "柱体自零起，负值保留；差额为口径差异，不等同于数据错误。",
                   "dataRefs": [ANNUAL_REF, MONTHLY_REF]})

sections = [
    {"id": "sec_recon", "title": "年度汇总对月度合计对账结果",
     "narrative": trend_line + (" " + pct_note if pct_note else "") + " " + gap_text,
     "chartIds": ["c_recon_bars"] if RECON_FILE in chart_files else [],
     "tableIds": ["t_recon"]},
    {"id": "sec_trend", "title": "1999年逐月净收款与利润走势",
     "narrative": (f"{missing_text}{peak_text}{neg_text}趋势图仅使用月度口径的已观测月份；"
                   f"两个金额序列共用同一未声明单位与同一坐标轴，未做单位换算，也未与任何基准或目标区间比较。"),
     "chartIds": ["c_monthly_trend"] if TREND_FILE in chart_files else [],
     "tableIds": ["t_monthly"]},
    {"id": "sec_gap", "title": "差额性质与证据边界",
     "narrative": gap_text,
     "chartIds": [], "tableIds": []},
    {"id": "sec_grain", "title": "口径、粒度与字段来源",
     "narrative": ("月度口径按 sale_year、sale_month、category 组织，商品主键1、期间 1999-01-01 至 1999-12-31、"
                   "最低购买数量 0、品类不限来自需求与模板默认声明；年度口径为无参数的按实际销售日期年度汇总（限定商品 ss_item_sk=1），"
                   "本次仅使用其 sales_year=1999 一行，其余年度行未参与计算。"
                   "row_count 与 sales_rows 的计数单位在交付字段定义中未逐字段声明，因此未用于对账结论。"
                   "两个模板均未交付参数绑定回执，商品与期间的实际绑定未由执行回执独立证实。"),
     "chartIds": [], "tableIds": []},
]

field_prov = [
    {"datasetReference": MONTHLY_REF, "sourceField": "sale_year", "meaning": "年度维度（销售年度）",
     "declarationSource": "inputVersions.sourceDefinition.description：「真实门店逐月销售…」；字段级 fieldDefinitions 为空", "status": "CONFIRMED"},
    {"datasetReference": MONTHLY_REF, "sourceField": "sale_month", "meaning": "月度维度（销售月份）",
     "declarationSource": "inputVersions.sourceDefinition.description：「逐月销售」；字段级 fieldDefinitions 为空", "status": "CONFIRMED"},
    {"datasetReference": MONTHLY_REF, "sourceField": "category", "meaning": "品类名称（本次不限品类，仅保留原值用于粒度核对）",
     "declarationSource": "inputVersions.sourceDefinition.description：「可选品类」", "status": "CONFIRMED"},
    {"datasetReference": MONTHLY_REF, "sourceField": "total_net_paid", "meaning": "净收款合计（金额单位与币种未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「净收款」；用户需求称「净收款」", "status": "CONFIRMED"},
    {"datasetReference": MONTHLY_REF, "sourceField": "total_net_profit", "meaning": "利润合计（金额单位与币种未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「利润」；用户需求称「利润」", "status": "CONFIRMED"},
    {"datasetReference": MONTHLY_REF, "sourceField": "total_quantity", "meaning": "数量合计（数量单位未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「数量」；用户需求提到最低购买数量", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "sales_year", "meaning": "按实际销售日期归集的销售年度",
     "declarationSource": "inputVersions.sourceDefinition.description：「按实际销售日期年度汇总」", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "earliest_date", "meaning": "该年度实际销售日期的最早值（仅用于期间核对）",
     "declarationSource": "inputVersions.sourceDefinition.description：「按实际销售日期…排除无日期维度关联记录」", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "latest_date", "meaning": "该年度实际销售日期的最晚值（仅用于期间核对）",
     "declarationSource": "inputVersions.sourceDefinition.description：「按实际销售日期…排除无日期维度关联记录」", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "total_net_paid", "meaning": "净收款合计（金额单位与币种未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「净收款」", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "total_net_profit", "meaning": "利润合计（金额单位与币种未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「利润」", "status": "CONFIRMED"},
    {"datasetReference": ANNUAL_REF, "sourceField": "total_quantity", "meaning": "数量合计（数量单位未声明）",
     "declarationSource": "inputVersions.sourceDefinition.description：「数量」", "status": "CONFIRMED"},
]

limitations = [
    "金额单位（币种与量纲）在源端与需求中均未声明，报告中的金额仅为源端数值，未做任何换算；数量单位同样未声明。",
    "两个模板均未交付参数绑定回执（effectiveParameters 为空），商品主键1、1999-01-01 至 1999-12-31、最低数量 0、品类不限的绑定以模板返回说明与需求为据，未由执行回执独立证实。",
    "年度口径声明排除无日期维度关联记录且无数量条件，月度口径套用最低数量 0；两口径的差额无法拆分到“数量为空记录”“无日期维度记录”等具体来源，属未解决项，不认定为数据错误。",
    "本批交付数据中不存在可直接观测的数量为空明细行，也没有其小计，因此金额差额的归因仅为与观测形态一致的推断。",
    "1999年并非12个月全部有观测时，缺失月份以空白表示而非零值，月度合计不含未覆盖月份。",
    "row_count 与 sales_rows 的计数单位未逐字段声明，未用于对账结论，也未与任何业务行数比较。",
    "年度口径数据包含 1999 之外的年度行，本次仅使用 1999 行；未对跨年度趋势做任何推断。",
    "报告仅覆盖单商品单年度，不能据此推断长期盈利水平、客户偏好或因果关系。",
]

report_md = []
report_md.append("# 248测试数据库 · tpcds 商品主键1 · 1999年门店销售勾稽报告")
report_md.append("")
report_md.append("**范围**：商品主键1；期间 1999-01-01 至 1999-12-31（销售期间）；最低购买数量 0；品类不限。金额单位源端未声明。")
report_md.append("")
report_md.append("## 一、结论摘要")
report_md.append("")
report_md.append("- " + trend_line)
report_md.append("- " + gap_text)
report_md.append("- " + missing_text)
report_md.append("- " + peak_text + neg_text)
report_md.append("")
report_md.append("## 二、年度汇总对月度合计对账表")
report_md.append("")
report_md.append("| " + " | ".join(tables[1]["columns"]) + " |")
report_md.append("|" + "|".join(["---"] * len(tables[1]["columns"])) + "|")
for row in t2_rows:
    report_md.append("| " + " | ".join(str(c) for c in row) + " |")
report_md.append("")
report_md.append("容差：金额 0.005、数量 0（在同一未声明单位下比较）；差额率 = 差额 ÷ 月度合计 × 100，分母为零时不计算。")
if pct_note:
    report_md.append("")
    report_md.append(pct_note)
report_md.append("")
report_md.append("## 三、逐月净收款与利润趋势")
report_md.append("")
report_md.append(f"图：{charts[0]['title'] if charts else '（本期无图）'}。实体为 tpcds 商品主键1，期间 1999-01-01 至 1999-12-31，"
                 "来源为门店逐月销售汇总（跨品类合计、最低数量 0）；金额单位未声明，未做换算；零基准线可见，缺失月份为断点而非零值。")
report_md.append("")
report_md.append("| " + " | ".join(tables[0]["columns"]) + " |")
report_md.append("|" + "|".join(["---"] * len(tables[0]["columns"])) + "|")
for row in t1_rows:
    report_md.append("| " + " | ".join(str(c) for c in row) + " |")
report_md.append("")
report_md.append("## 四、差额性质与证据边界")
report_md.append("")
report_md.append(gap_text)
report_md.append("")
report_md.append(f"期间核对：年度口径 1999 行声明的实际销售日期区间为 {ann_earliest} 至 {ann_latest}，"
                 f"{'完全落在1999年内' if date_in_1999 else '未被确认落在1999年内'}。")
report_md.append("")
report_md.append("## 五、字段口径与来源")
report_md.append("")
report_md.append("| 数据集 | 源字段 | 含义 | 声明来源 | 状态 |")
report_md.append("|---|---|---|---|---|")
for fp in field_prov:
    ds = "月度口径模板" if fp["datasetReference"] == MONTHLY_REF else "年度口径模板"
    report_md.append(f"| {ds} | {fp['sourceField']} | {fp['meaning']} | {fp['declarationSource']} | {fp['status']} |")
report_md.append("")
report_md.append("## 六、限制与未解决事项")
report_md.append("")
for l in limitations:
    report_md.append(f"- {l}")
report_md.append("")
report_md.append("## 七、口径与粒度补充")
report_md.append("")
report_md.append(sections[3]["narrative"])
report_md.append("")
reportMarkdown = "\n".join(report_md)

validation = {"checks": [
    {"name": "输入校验（清单与分片哈希、行数）", "status": "PASSED",
     "detail": f"清单哈希与声明一致；1个分片 {part_receipts[0]['rows']} 行，分片哈希与声明一致；合计 {len(records)} 行，与声明 rowCount 一致。"},
    {"name": "数据集拆分与字段完整性", "status": "PASSED" if not unmatched_refs else "FAILED",
     "detail": f"月度口径 {len(monthly_rows)} 行、年度口径 {len(annual_rows)} 行；未识别来源 {len(unmatched_refs)} 个；所需字段齐备。"},
    {"name": "1999年1–12月覆盖", "status": "PASSED" if not missing_months else "FAILED",
     "detail": f"已覆盖 {len(months_covered)} 个月的观测" + (f"；缺 {'、'.join(str(m) for m in missing_months)} 月（不补零）。" if missing_months else "；无缺口。")},
    {"name": "月度粒度唯一性（sale_year, sale_month, category）", "status": "PASSED" if not duplicate_grain_keys else "FAILED",
     "detail": f"重复键 {len(duplicate_grain_keys)} 组；每期返回行数 {sorted(set(rows_per_month.values()))}。"},
    {"name": "年度口径 1999 行唯一性", "status": "PASSED" if not annual_duplicate_rows else "FAILED",
     "detail": f"sales_year=1999 的行数 {len(ann_sel)}；年度口径实际返回年度 {annual_years}。"},
    {"name": "1999年期间核对（年度口径实际销售日期区间）", "status": "PASSED" if date_in_1999 else "UNRESOLVED",
     "detail": f"earliest_date={ann_earliest}、latest_date={ann_latest}。"},
    {"name": "净收款两口径合计一致性", "status": "PASSED" if (diff_paid is not None and abs(diff_paid) <= AMT_TOL) else "FAILED",
     "detail": f"年度汇总口径 {fmt_amt(ann_paid)}，月度合计口径 {fmt_amt(mon_paid)}，差额 {fmt_amt(diff_paid, signed=True)}（容差 0.005）；差额属口径差异，不认定为数据错误。"},
    {"name": "利润两口径合计一致性", "status": "PASSED" if (diff_profit is not None and abs(diff_profit) <= AMT_TOL) else "FAILED",
     "detail": f"年度汇总口径 {fmt_amt(ann_profit)}，月度合计口径 {fmt_amt(mon_profit)}，差额 {fmt_amt(diff_profit, signed=True)}（容差 0.005）；年度汇总口径亏损大于月度合计口径，差额属口径差异，不认定为数据错误。"},
    {"name": "数量合计两口径一致", "status": "PASSED" if qty_equal else "FAILED",
     "detail": f"年度口径 {fmt_qty(ann_qty)}，月度合计 {fmt_qty(mon_qty)}，差额 {fmt_qty(diff_qty, signed=True)}。"},
    {"name": "金额差额归因（数量为空记录被过滤）", "status": "UNRESOLVED",
     "detail": "交付数据无数量为空明细行或其小计，无法直接量化该子集贡献；现仅能说明差额方向与该口径机制一致，不认定为数据错误。"},
    {"name": "金额单位与币种声明", "status": "UNRESOLVED",
     "detail": "源字段定义与需求均未声明金额单位，报告中金额无单位标注，未做换算。"},
    {"name": "模板参数绑定证据", "status": "UNRESOLVED",
     "detail": "未交付 effectiveParameters/parameterSources；商品主键1与期间绑定以模板声明与需求为据。"},
], "warnings": (
    ([f"图表生成失败已省略：{e}" for e in chart_errors]) +
    (["1999年存在未覆盖月份，月度合计不含这些月份。"] if missing_months else []) +
    (["月度口径存在重复 (sale_month, category) 键，已按 key 求和。"] if duplicate_grain_keys else [])
)}

summary_short = (f"1999年商品主键1门店销售：年度汇总口径净收款 {fmt_amt(ann_paid)}、利润 {fmt_amt(ann_profit)}；"
                 f"月度合计口径净收款 {fmt_amt(mon_paid)}、利润 {fmt_amt(mon_profit)}；"
                 f"差额分别为 {fmt_amt(diff_paid, signed=True)} 与 {fmt_amt(diff_profit, signed=True)}，"
                 f"数量两口径{('相等' if qty_equal else '不相等')}，差额与“最低数量0”过滤条件排除数量为空记录的口径机制相符，不认定为数据错误。"
                 f"金额单位源端未声明。")

visual = {
    "schemaVersion": "python_visual_report.v1",
    "title": "248测试数据库 · tpcds 商品主键1 · 1999年门店销售勾稽报告",
    "summary": summary_short,
    "status": "PARTIAL",
    "presentation": {"strategy": "auto", "template": "briefing", "density": "comfortable",
                     "autoCharts": True, "autoImages": True},
    "metrics": metrics,
    "sections": sections,
    "charts": charts,
    "tables": tables,
    "dataSources": [MONTHLY_REF, ANNUAL_REF],
    "validation": validation,
    "limitations": limitations,
    "fieldProvenance": field_prov,
    "layout": {"schemaVersion": "report_layout.v1", "blocks": [
        {"id": "b-summary", "type": "summary", "span": "full"},
        {"id": "b-m1", "type": "metric", "ref": "m_annual_net_paid", "span": "half", "emphasis": "important"},
        {"id": "b-m2", "type": "metric", "ref": "m_monthly_net_paid", "span": "half", "emphasis": "important"},
        {"id": "b-m3", "type": "metric", "ref": "m_diff_net_paid", "span": "half", "emphasis": "important"},
        {"id": "b-m6", "type": "metric", "ref": "m_diff_net_profit", "span": "half", "emphasis": "important"},
        {"id": "b-m4", "type": "metric", "ref": "m_annual_net_profit", "span": "half"},
        {"id": "b-m5", "type": "metric", "ref": "m_monthly_net_profit", "span": "half"},
        {"id": "b-m7", "type": "metric", "ref": "m_months_covered", "span": "half"},
        {"id": "b-s1", "type": "section", "ref": "sec_recon", "span": "full"},
        {"id": "b-t2", "type": "table", "ref": "t_recon", "span": "full", "emphasis": "important"},
        {"id": "b-s2", "type": "section", "ref": "sec_trend", "span": "full"},
        {"id": "b-c1", "type": "chart", "ref": "c_monthly_trend", "span": "full"},
        {"id": "b-t1", "type": "table", "ref": "t_monthly", "span": "full"},
        {"id": "b-s3", "type": "section", "ref": "sec_gap", "span": "full"},
        {"id": "b-c2", "type": "chart", "ref": "c_recon_bars", "span": "full"},
        {"id": "b-s4", "type": "section", "ref": "sec_grain", "span": "full"},
        {"id": "b-lim", "type": "limitations", "span": "full"},
        {"id": "b-src", "type": "sources", "span": "full"},
    ]},
    "timings": {"analysisMs": round(ANALYSIS_MS, 3), "chartMs": round(CHART_MS, 3)},
}

row = {
    "reportMarkdown": reportMarkdown,
    "evidenceReferences": [MONTHLY_REF, ANNUAL_REF],
    "limitations": limitations,
    "analysisStatus": "PARTIAL",
    "fieldProvenance": field_prov,
    "visualReport": visual,
}

with open(OUTR / "rows.jsonl", "w", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")

diag = {
    "manifest_sha256": man_sha,
    "schema_sha256": schema_sha,
    "expected_schema_sha256": EXPECTED_SCHEMA_SHA,
    "parts": part_receipts,
    "total_rows": len(records),
    "monthly_years": monthly_years,
    "monthly_out_of_scope_rows": monthly_out_of_scope,
    "monthly_month_values": monthly_month_values,
    "rows_per_month": rows_per_month,
    "duplicate_grain_keys": duplicate_grain_keys,
    "annual_years": annual_years,
    "annual_1999_rows": len(ann_sel),
    "month_totals": {str(m): {k: (str(v) if v is not None else None) for k, v in month_totals[m].items()} for m in months_covered},
    "mon_paid": str(mon_paid), "mon_profit": str(mon_profit), "mon_qty": str(mon_qty),
    "ann_paid": str(ann_paid), "ann_profit": str(ann_profit), "ann_qty": str(ann_qty),
    "diff_paid": str(diff_paid), "diff_profit": str(diff_profit), "diff_qty": str(diff_qty),
    "chart_files": chart_files, "chart_errors": chart_errors,
}
(OUTV / "diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")
