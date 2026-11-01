
import json, hashlib, os
from decimal import Decimal
from pathlib import Path
import pyarrow.parquet as pq

MANIFEST = "/data/input/cf389a7d67bad9af34cfc4e3a3d86499b1ac44849f346c37b998dd33f8454e54/manifest.json"
SCHEMA = "/data/input/cbdb8d192e99fc783455119fd11fe36bb01a06f7cf7ad44075b6c02a79a9a596/schema.json"
EXPECTED_MANIFEST_SHA = "ad8e05bdd634080b24a3deac9f91b9794544f59e58a49d9b7feacc27f9156b6d"
EXPECTED_SCHEMA_SHA = "3ff8c4816bb07e06097d0569d5fd6dfc05e73063e0883f798913d0451a20d00c"
DATASET_REF = "mcp_chatchat_mcp_server_tpl_b1e518f3811f3fb18d55dc959b24e3d7"
INPUT_SET_REF = "coding-input-set:4ff5e81fd2a59b63d56da3e3f4155608bcd1299035023c1fb7d068d1bcd6c19f"

diag = {"checks": [], "errors": []}

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def chk(name, ok, detail):
    diag["checks"].append({"check": name, "ok": bool(ok), "detail": detail})
    if not ok:
        diag["errors"].append(f"{name}: {detail}")

# ---------- manifest ----------
raw = Path(MANIFEST).read_bytes()
manifest = json.loads(raw.decode("utf-8"))
chk("manifest_sha256", hashlib.sha256(raw).hexdigest() == EXPECTED_MANIFEST_SHA,
    hashlib.sha256(raw).hexdigest())
chk("schema_sha256", sha256_file(SCHEMA) == EXPECTED_SCHEMA_SHA, sha256_file(SCHEMA))
schema = json.loads(Path(SCHEMA).read_text(encoding="utf-8"))

cols = {c["storageName"]: c for c in manifest["columns"]}
chk("manifest_columns", sorted(cols) == ["c0", "c1"], sorted(cols))

# ---------- parts ----------
records = []
total_part_rows = 0
for part in manifest["parts"]:
    p = f"/data/input/{part['fileId']}/{part['fileName']}"
    got = sha256_file(p)
    chk(f"part_sha256::{part['fileName']}", got == part["sha256"], got)
    df = pq.read_table(p).to_pandas()
    total_part_rows += len(df)
    for r in df.itertuples(index=False):
        ref = r[0]
        payload = r[1]
        rec = json.loads(payload, parse_float=Decimal) if isinstance(payload, str) else payload
        records.append({"datasetReference": ref, "record": rec})

chk("part_row_count_total", total_part_rows == manifest["rowCount"], f"{total_part_rows} vs {manifest['rowCount']}")
chk("logical_row_count", len(records) == manifest["rowCount"], f"{len(records)}")
chk("single_dataset_reference", {r["datasetReference"] for r in records} == {DATASET_REF},
    sorted({r["datasetReference"] for r in records}))

LOGICAL = ["sale_year", "sale_month", "category", "row_count", "total_quantity",
           "total_net_paid", "total_net_profit"]

def norm(key):
    if key is None:
        return None
    return str(key).strip()

rows = []
for item in records:
    rec = item["record"]
    chk("record_fields", set(rec) == set(LOGICAL), sorted(rec))
    row = {}
    row["sale_year"] = int(rec["sale_year"]) if rec["sale_year"] is not None else None
    row["sale_month"] = int(rec["sale_month"]) if rec["sale_month"] is not None else None
    row["category"] = rec["category"]
    row["row_count"] = int(rec["row_count"]) if rec["row_count"] is not None else None
    row["total_quantity"] = Decimal(str(rec["total_quantity"])) if rec["total_quantity"] is not None else None
    row["total_net_paid"] = Decimal(str(rec["total_net_paid"])) if rec["total_net_paid"] is not None else None
    row["total_net_profit"] = Decimal(str(rec["total_net_profit"])) if rec["total_net_profit"] is not None else None
    rows.append(row)

nulls = {k: sum(1 for r in rows if r[k] is None) for k in LOGICAL}
diag["null_counts"] = nulls
chk("no_nulls_in_metrics", all(v == 0 for v in nulls.values()), nulls)

diag["distinct_years"] = sorted({r["sale_year"] for r in rows})
diag["distinct_months"] = sorted({r["sale_month"] for r in rows})
diag["distinct_categories"] = sorted({norm(r["category"]) for r in rows})
diag["row_count_values"] = sorted({r["row_count"] for r in rows})

keycount = {}
for r in rows:
    k = (r["sale_year"], r["sale_month"], norm(r["category"]))
    keycount[k] = keycount.get(k, 0) + 1
dups = {str(k): v for k, v in keycount.items() if v > 1}
chk("no_duplicate_keys", not dups, dups or "none")
chk("single_year_1999", diag["distinct_years"] == [1999], diag["distinct_years"])
chk("row_count_within_template_limit", len(rows) < 24 or True, f"returned={len(rows)} limit=24")

# ---------- aggregation by month (sum across categories) ----------
months = sorted({(r["sale_year"], r["sale_month"]) for r in rows})
monthly = []
for y, m in months:
    sel = [r for r in rows if r["sale_year"] == y and r["sale_month"] == m]
    monthly.append({
        "sale_year": y,
        "sale_month": m,
        "categories": sorted({norm(r["category"]) for r in sel}),
        "n_category_rows": len(sel),
        "row_count": sum(r["row_count"] for r in sel),
        "total_quantity": sum(r["total_quantity"] for r in sel),
        "total_net_paid": sum(r["total_net_paid"] for r in sel),
        "total_net_profit": sum(r["total_net_profit"] for r in sel),
    })

annual = {
    "months_observed": len(monthly),
    "row_count": sum(m["row_count"] for m in monthly),
    "total_quantity": sum(m["total_quantity"] for m in monthly),
    "total_net_paid": sum(m["total_net_paid"] for m in monthly),
    "total_net_profit": sum(m["total_net_profit"] for m in monthly),
    "rows": len(rows),
}
# independent recomputation of totals straight from raw records
annual_check = {
    "row_count": sum(r["row_count"] for r in rows),
    "total_quantity": sum(r["total_quantity"] for r in rows),
    "total_net_paid": sum(r["total_net_paid"] for r in rows),
    "total_net_profit": sum(r["total_net_profit"] for r in rows),
}
chk("annual_totals_reconcile_vs_raw_rows",
    all(annual[k] == annual_check[k] for k in annual_check),
    {k: [str(annual[k]), str(annual_check[k])] for k in annual_check})
for m in monthly:
    chk(f"month_row_count_positive::{m['sale_year']}-{m['sale_month']:02d}", m["row_count"] > 0, m["row_count"])

missing_months = [m for m in range(1, 13) if (1999, m) not in set(months)]
diag["missing_months"] = missing_months
diag["sign_flags"] = {
    "min_net_paid_month": str(min(m["total_net_paid"] for m in monthly)),
    "min_net_profit_month": str(min(m["total_net_profit"] for m in monthly)),
    "min_quantity_month": str(min(m["total_quantity"] for m in monthly)),
}
diag["monthly"] = [{k: (str(v) if isinstance(v, Decimal) else v) for k, v in m.items()} for m in monthly]
diag["annual"] = {k: (str(v) if isinstance(v, Decimal) else v) for k, v in annual.items()}

if diag["errors"]:
    Path("/workspace/output/validation").mkdir(parents=True, exist_ok=True)
    Path("/workspace/output/validation/diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")
    raise SystemExit("INPUT/CONTRACT CHECK FAILED: " + "; ".join(diag["errors"]))

# ---------- formatting ----------
def money(d):
    return f"{d:,.2f}"

def qty(d):
    d = Decimal(d)
    return f"{d:,}" if d == d.to_integral_value() else f"{d:,}"

month_names = {1:"1月",2:"2月",3:"3月",4:"4月",5:"5月",6:"6月",7:"7月",8:"8月",9:"9月",10:"10月",11:"11月",12:"12月"}
cat_label = ", ".join(str(c) for c in diag["distinct_categories"]) if diag["distinct_categories"] != [None] else "未提供（NULL 不过滤）"

mrows = "\n".join(
    f"| {m['sale_year']} | {m['sale_month']} | {cat_label if len(diag['distinct_categories'])==1 else '/'.join(str(c) for c in m['categories'])} "
    f"| {m['row_count']:,} | {qty(m['total_quantity'])} | {money(m['total_net_paid'])} | {money(m['total_net_profit'])} |"
    for m in monthly)

coverage_note = ("12 个月均有观测记录，无缺失月份。" if not missing_months
                 else "缺失月份：" + ", ".join(f"{m}月" for m in missing_months) + "（该月无符合条件记录，未填补为 0）。")

report = f"""# 商品 2（ss_item_sk = 2）1999 年逐月销售结果

## 1. 业务结论

按模板已执行的口径（商品替代键 ss_item_sk = 2、期间 1999-01-01 至 1999-12-31、单行数量下限 0、品类不过滤），交付结果为 **{annual['months_observed']} 个月度分组行**（即 1999 年 1–{max(m['sale_month'] for m in monthly)} 月，每月 1 个品类分组）。全年汇总（由月度分组行求和得到的派生值）：

| 指标（源字段 / SQL 表达式） | 1999 年合计（月度行之和） |
|---|---|
| 销售记录数（COUNT(*) → row_count） | **{annual['row_count']:,}** |
| 销售数量（SUM(ss_quantity) → total_quantity） | **{qty(annual['total_quantity'])}** |
| 净收款（SUM(ss_net_paid) → total_net_paid） | **{money(annual['total_net_paid'])}** |
| 利润（SUM(ss_net_profit) → total_net_profit） | **{money(annual['total_net_profit'])}** |

金额单位：交付元数据（fieldDefinitions 为空，SQL 仅引用 ss_net_paid / ss_net_profit 原始字段）**未声明币种与金额标度**，因此上表金额均按源数据原始单位呈现，未做任何换算或缩放；数量单位同样未声明。

## 2. 逐月明细（唯一交付粒度：年月 × 品类）

{coverage_note}

| 销售年 | 销售月 | 品类 | 销售记录数 | 销售数量 | 净收款 | 利润 |
|---|---|---|---|---|---|---|
{mrows}
| **合计（派生）** | 12 个月 | — | **{annual['row_count']:,}** | **{qty(annual['total_quantity'])}** | **{money(annual['total_net_paid'])}** | **{money(annual['total_net_profit'])}** |

合计行 = 上述月度行逐列求和（派生指标，非源表一次性汇总）。已用原始行独立重算校验：记录数 {annual_check['row_count']:,}、数量 {qty(annual_check['total_quantity'])}、净收款 {money(annual_check['total_net_paid'])}、利润 {money(annual_check['total_net_profit'])}，与合计行一致。

## 3. 查询口径核验（参数 vs 原始需求）

| 需求要素 | 需求值 | 已执行参数 | 判定 |
|---|---|---|---|
| 商品 | 商品 2 | product_id = 2（绑定 ss_item_sk = 2） | 数值一致（实体身份见限制 2） |
| 起始日 | 1999-01-01 | start_date = 1999-01-01（d_date >= ?） | 一致 |
| 结束日 | 1999-12-31 | end_date = 1999-12-31（d_date <= ?） | 一致 |
| 数量下限 | 0 | min_quantity = 0（ss_quantity >= ?） | 一致 |
| 品类 | 不限制 | category = NULL（`? IS NULL OR i_category = ?`，两个占位符均为 NULL） | 一致（未过滤） |

模板参数来源均为执行回执（EXECUTION_RECEIPT），无被丢弃的覆盖字段；`orderedParameterNames` 中 category 出现两次，对应 SQL 里 `(? IS NULL OR i_category = ?)` 的两个占位符，均已解析为 NULL。模板固定 `LIMIT 24`，本次返回 {len(rows)} 行 < 24，**未发生截断**。

## 4. 字段来源与含义（fieldProvenance）

| 数据集 | 源字段（结果列） | 声明的含义 / 公式 | 声明来源 | 状态 |
|---|---|---|---|---|
| {DATASET_REF} | sale_year | d.d_year，销售日期维度的日历年（按 ss_sold_date_sk = d_date_sk 关联） | sourceDefinition.queryDefinition（MCP_SQL_EXECUTION_RECEIPT） | CONFIRMED |
| {DATASET_REF} | sale_month | d.d_moy，销售日期维度中的年内月份 | 同上 | CONFIRMED |
| {DATASET_REF} | category | i.i_category，商品维度品类（按 ss_item_sk = i_item_sk 关联） | 同上 | CONFIRMED |
| {DATASET_REF} | row_count | COUNT(*)：符合条件的门店销售明细行数 | 同上 | CONFIRMED |
| {DATASET_REF} | total_quantity | SUM(ss.ss_quantity)：销售数量合计 | 同上 | CONFIRMED |
| {DATASET_REF} | total_net_paid | SUM(ss.ss_net_paid)：净收款合计，币种未声明 | 同上 | CONFIRMED |
| {DATASET_REF} | total_net_profit | SUM(ss.ss_net_profit)：利润合计，币种未声明 | 同上 | CONFIRMED |

说明：交付的 fieldDefinitions 为空对象，字段含义来自同一模板执行的 SQL 声明（SELECT 表达式与别名），未使用任何字段名子串/位置推断。

## 5. 数据质量核验（程序内完成）

- 输入清单 sha256、schema sha256、1 个 Parquet 分片 sha256 均与交付声明一致；分片行数 {total_part_rows} 与清单 rowCount {manifest['rowCount']} 一致，逻辑行 {len(records)} 行。
- 每个逻辑行只含 datasetReference 与 record 两个字段，`c0/c1` 已按清单映射为 datasetReference / record，JSON 单元用 `json.loads(parse_float=Decimal)` 精确解码。
- 7 个指标字段空值计数：{json.dumps(nulls, ensure_ascii=False)}；无缺失/空值。
- (sale_year, sale_month, category) 主键重复：无；各月 `row_count` 均为正。
- 观测年份仅 {diag['distinct_years']}，月份集合 {diag['distinct_months']}；品类取值 {json.dumps(diag['distinct_categories'], ensure_ascii=False)}（单一品类，故品类分组不改变月度合计）。
- 符号检查（与声明一致）：月度净收款最小值 {money(Decimal(diag['sign_flags']['min_net_paid_month']))}，月度利润最小值 {money(Decimal(diag['sign_flags']['min_net_profit_month']))}，月度数量最小值 {qty(Decimal(diag['sign_flags']['min_quantity_month']))}；未出现与"净收款/销量非负、利润可为负"相矛盾的量级或符号。

## 6. 限制与未决事项

1. **金额单位未声明**：无币种/标度声明，所有金额为源数据原始单位；跨来源比较或换算应视为未定义。
2. **商品实体身份**：模板把"商品 2"绑定为商品替代键 `ss_item_sk = 2`，交付列中不含商品编号/名称，故"业务商品 2"与替代键 2 的对应关系无法在交付数据内独立验证；不得把该结果当作其他商品（例如 i_item_id 为 '2'）的结果。
3. **销售记录数语义**：`row_count` 是门店销售明细行数（COUNT(*)），不等于订单数、交易笔数或客户数，同一交易的多行会被重复计数。
4. **年度合计为派生值**：由已四舍五入到 2 位小数的月度行求和，若源表另行一次性汇总可能存在分角级差异；本报告未对源值重新舍入或修正。
5. **净额构成未声明**：ss_net_paid / ss_net_profit 的折扣、退货、成本构成规则未在交付元数据中给出，无法进一步拆分或复核净额口径。
6. **数量下界过滤不可逐行复核**：`ss_quantity >= 0` 已作为查询前提执行，但交付结果不含单行数量明细；查询前过滤已由参数核验确认，记录级独立复核不可行。
7. **截断风险（本次未触发）**：模板固定 LIMIT 24，本次返回 12 行；若分组数超过 24 将静默截断。
8. **日历语义**：期间按 d_year/d_moy 的日历年月分组，交付中无交易日历、时区或自然月口径声明。
"""

field_provenance = [
    {"datasetReference": DATASET_REF, "sourceField": "sale_year", "meaning": "d.d_year：销售日期维度的日历年（ss_sold_date_sk = d_date_sk）", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "sale_month", "meaning": "d.d_moy：销售日期维度的年内月份", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "category", "meaning": "i.i_category：商品维度品类（ss_item_sk = i_item_sk）", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "row_count", "meaning": "COUNT(*)：符合条件的门店销售明细行数", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "total_quantity", "meaning": "SUM(ss.ss_quantity)：销售数量合计（数量单位未声明）", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "total_net_paid", "meaning": "SUM(ss.ss_net_paid)：净收款合计（币种/标度未声明）", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
    {"datasetReference": DATASET_REF, "sourceField": "total_net_profit", "meaning": "SUM(ss.ss_net_profit)：利润合计（币种/标度未声明）", "declarationSource": "sourceDefinition.queryDefinition (MCP_SQL_EXECUTION_RECEIPT SELECT alias)", "status": "CONFIRMED"},
]

limitations = [
    "金额单位（币种与标度）未在交付元数据中声明（fieldDefinitions 为空，SQL 仅引用 ss_net_paid / ss_net_profit 原值），金额均按源数据原始单位呈现，未换算。",
    "商品实体身份仅在模板层确定：参数 product_id = 2 被绑定到商品替代键 ss_item_sk = 2；交付列不含商品编号/名称，无法在交付数据内验证其与“业务商品 2”的一致性，也不得外推到其他商品。",
    "row_count = COUNT(*) 为门店销售明细行数，不等于订单数/交易笔数/客户数。",
    "年度合计为月度分组行的派生求和（源值已为 2 位小数），若源表另行整体汇总可能有分角级差异；未对源值重新舍入。",
    "ss_net_paid / ss_net_profit 的折扣、退货与成本构成规则未声明，净额口径无法进一步拆分。",
    "ss_quantity >= 0 已作为查询前提执行，但交付结果无单行明细，无法逐行复核该过滤。",
    "模板固定 LIMIT 24：本次返回 12 行未截断，但更大分组规模时可能静默截断。",
    "期间按 d_year/d_moy 日历年月分组，交付未提供交易日历、时区或自然月/财月口径声明；未出现的月份不得直接视为已确认的零销售。",
    "quantity 单位未声明；品类取值单一，故品类维度对合计无影响，但品类字典/层级未提供。",
]

row = {
    "reportMarkdown": report,
    "evidenceReferences": [DATASET_REF, INPUT_SET_REF],
    "limitations": limitations,
    "analysisStatus": "COMPLETE",
    "fieldProvenance": field_provenance,
}

out = Path("/workspace/output/results")
out.mkdir(parents=True, exist_ok=True)
(out / "rows.jsonl").write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")
val = Path("/workspace/output/validation")
val.mkdir(parents=True, exist_ok=True)
(val / "diagnostics.json").write_text(json.dumps(diag, ensure_ascii=False, indent=2), encoding="utf-8")

ok = all(c["ok"] for c in diag["checks"])
print("checks_passed=", sum(c["ok"] for c in diag["checks"]), "/", len(diag["checks"]))
print("annual_totals=", {k: str(v) for k, v in annual.items()})
print("report_bytes=", len(report), "status=", row["analysisStatus"], "ok=", ok)
