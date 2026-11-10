"""
test.parquet 数据质量与分布分析脚本
====================================
数据源: /workspace/test.parquet (NYC 黄色出租车行程记录)
输出:   控制台打印分析报告 + 生成 report.md
"""

import pandas as pd
import numpy as np
import os
from datetime import datetime

# ──────────────────────────────────────────────
# 0. 加载数据
# ──────────────────────────────────────────────
FILE_PATH = "/workspace/test.parquet"
df = pd.read_parquet(FILE_PATH)
file_size_mb = os.path.getsize(FILE_PATH) / 1024 / 1024

print("=" * 70)
print("  NYC 黄色出租车行程数据分析报告")
print("=" * 70)

# ──────────────────────────────────────────────
# 1. 数据规模
# ──────────────────────────────────────────────
n_rows, n_cols = df.shape
print(f"\n[1] 数据规模")
print(f"    文件路径: {FILE_PATH}")
print(f"    文件大小: {file_size_mb:.2f} MB")
print(f"    行数:     {n_rows:,}")
print(f"    列数:     {n_cols}")

# ──────────────────────────────────────────────
# 2. 字段类型
# ──────────────────────────────────────────────
print(f"\n[2] 字段类型")
print(f"    {'列名':<30s} {'类型':<20s} {'非空数':>10s} {'唯一值':>8s}")
print(f"    {'-'*70}")
for col in df.columns:
    dtype = str(df[col].dtype)
    non_null = df[col].notna().sum()
    nunique = df[col].nunique()
    print(f"    {col:<30s} {dtype:<20s} {non_null:>10,} {nunique:>8,}")

# ──────────────────────────────────────────────
# 3. 缺失值分析
# ──────────────────────────────────────────────
print(f"\n[3] 缺失值分析")
missing = df.isnull().sum()
missing_pct = (missing / n_rows * 100).round(2)
missing_df = pd.DataFrame({"缺失数": missing, "缺失率(%)": missing_pct})
missing_df = missing_df[missing_df["缺失数"] > 0].sort_values("缺失数", ascending=False)
print(f"    存在缺失值的列数: {len(missing_df)} / {n_cols}")
for col_name, row in missing_df.iterrows():
    print(f"    {col_name:<30s} 缺失 {int(row['缺失数']):>6,} 条  ({row['缺失率(%)']:>6.2f}%)")

# 检查缺失值是否集中在相同行
cols_with_missing = [c for c in df.columns if 0 < df[c].isna().sum() < n_rows]
if len(cols_with_missing) >= 2:
    mask_ref = df[cols_with_missing[0]].isna()
    all_same = all((mask_ref == df[c].isna()).all() for c in cols_with_missing[1:])
    print(f"\n    ⚠ 缺失值是否集中在相同行: {'是' if all_same else '否'}")
    if all_same:
        print(f"    ⚠ 共 {int(mask_ref.sum()):,} 行存在同步缺失")

# ──────────────────────────────────────────────
# 4. 重复值分析
# ──────────────────────────────────────────────
n_dup = df.duplicated().sum()
print(f"\n[4] 重复值分析")
print(f"    完全重复行数: {n_dup:,}")

# ──────────────────────────────────────────────
# 5. 数值字段统计分布
# ──────────────────────────────────────────────
print(f"\n[5] 数值字段统计分布")
numeric_cols = df.select_dtypes(include=[np.number]).columns
desc = df[numeric_cols].describe().T
desc["缺失数"] = df[numeric_cols].isnull().sum()
print(desc[["count", "mean", "std", "min", "25%", "50%", "75%", "max", "缺失数"]].to_string())

# ──────────────────────────────────────────────
# 6. 行程距离分布
# ──────────────────────────────────────────────
print(f"\n[6] 行程距离分布 (trip_distance)")
dist_bins = [0, 1, 2, 3, 5, 10, 20, 50, 100, float("inf")]
dist_labels = ["0-1", "1-2", "2-3", "3-5", "5-10", "10-20", "20-50", "50-100", "100+"]
dist_cut = pd.cut(df["trip_distance"], bins=dist_bins, labels=dist_labels, right=True)
dist_dist = dist_cut.value_counts().sort_index()
for label, count in dist_dist.items():
    pct = count / n_rows * 100
    bar = "█" * int(pct)
    print(f"    {label:>8s} | {count:>7,} ({pct:>5.1f}%) {bar}")

# ──────────────────────────────────────────────
# 7. 车费分布
# ──────────────────────────────────────────────
print(f"\n[7] 车费分布 (fare_amount)")
fare_bins = [-200, 0, 5, 10, 15, 20, 30, 50, 100, float("inf")]
fare_labels = ["<0", "0-5", "5-10", "10-15", "15-20", "20-30", "30-50", "50-100", "100+"]
fare_cut = pd.cut(df["fare_amount"], bins=fare_bins, labels=fare_labels, right=True)
fare_dist = fare_cut.value_counts().sort_index()
for label, count in fare_dist.items():
    pct = count / n_rows * 100
    bar = "█" * int(pct / 2)
    print(f"    {label:>8s} | {count:>7,} ({pct:>5.1f}%) {bar}")

# ──────────────────────────────────────────────
# 8. 时间范围分析
# ──────────────────────────────────────────────
print(f"\n[8] 时间范围")
pickup_min = df["tpep_pickup_datetime"].min()
pickup_max = df["tpep_pickup_datetime"].max()
dropoff_min = df["tpep_dropoff_datetime"].min()
dropoff_max = df["tpep_dropoff_datetime"].max()
print(f"    上车时间: {pickup_min} ~ {pickup_max}")
print(f"    下车时间: {dropoff_min} ~ {dropoff_max}")

# ──────────────────────────────────────────────
# 9. 行程时长分析
# ──────────────────────────────────────────────
df["trip_duration_min"] = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds() / 60
neg_dur = (df["trip_duration_min"] < 0).sum()
zero_dur = (df["trip_duration_min"] == 0).sum()
long_dur = (df["trip_duration_min"] > 1440).sum()
print(f"\n[9] 行程时长异常")
print(f"    平均时长: {df['trip_duration_min'].mean():.2f} 分钟")
print(f"    中位数:   {df['trip_duration_min'].median():.2f} 分钟")
print(f"    负值时长: {neg_dur} 条")
print(f"    零值时长: {zero_dur} 条")
print(f"    超过24h:  {long_dur} 条")

# ──────────────────────────────────────────────
# 10. 潜在异常汇总
# ──────────────────────────────────────────────
print(f"\n[10] 潜在异常汇总")

anomalies = []
anomalies.append(("fare_amount < 0", int((df["fare_amount"] < 0).sum())))
anomalies.append(("total_amount < 0", int((df["total_amount"] < 0).sum())))
anomalies.append(("tip_amount < 0", int((df["tip_amount"] < 0).sum())))
anomalies.append(("fare_amount == 0", int((df["fare_amount"] == 0).sum())))
anomalies.append(("trip_distance == 0", int((df["trip_distance"] == 0).sum())))
anomalies.append(("trip_distance > 50 miles", int((df["trip_distance"] > 50).sum())))
anomalies.append(("trip_distance > 100 miles", int((df["trip_distance"] > 100).sum())))

Q1 = df["trip_distance"].quantile(0.25)
Q3 = df["trip_distance"].quantile(0.75)
IQR = Q3 - Q1
upper_bound = Q3 + 1.5 * IQR
anomalies.append((f"trip_distance IQR 异常 (>{upper_bound:.2f}mi)", int((df["trip_distance"] > upper_bound).sum())))
anomalies.append(("total_amount < fare_amount", int((df["total_amount"] < df["fare_amount"]).sum())))
anomalies.append(("VendorID == 6 (罕见)", int((df["VendorID"] == 6).sum())))
anomalies.append(("payment_type == 0 (未定义)", int((df["payment_type"] == 0).sum())))
anomalies.append(("上车日期早于 2020 年", int((df["tpep_pickup_datetime"] < '2020-01-01').sum())))
anomalies.append(("passenger_count == 0", int((df["passenger_count"] == 0).sum())))
anomalies.append(("airport_fee 全为 None", int(df["airport_fee"].isna().sum())))

print(f"    {'异常类型':<45s} {'数量':>8s} {'占比':>8s}")
print(f"    {'-'*65}")
for name, count in anomalies:
    pct = count / n_rows * 100
    flag = " ⚠" if pct > 1 else ""
    print(f"    {name:<45s} {count:>8,} {pct:>7.2f}%{flag}")

# ──────────────────────────────────────────────
# 11. 分类字段分布
# ──────────────────────────────────────────────
print(f"\n[11] 分类字段分布")
cat_cols = ["VendorID", "payment_type", "RatecodeID", "store_and_fwd_flag", "passenger_count"]
for col in cat_cols:
    print(f"\n    --- {col} ---")
    vc = df[col].value_counts(dropna=False).sort_index()
    for val, cnt in vc.items():
        pct = cnt / n_rows * 100
        print(f"    {str(val):>10s} : {cnt:>7,} ({pct:>5.1f}%)")

# ──────────────────────────────────────────────
# 12. 相关性分析
# ──────────────────────────────────────────────
print(f"\n[12] 与 total_amount 的相关系数 (Top 5)")
corr = df[numeric_cols].corr()["total_amount"].drop("total_amount").sort_values(ascending=False)
for col_name, val in corr.head(5).items():
    print(f"    {col_name:<30s} {val:>8.4f}")

print(f"\n{'=' * 70}")
print(f"  分析完成，正在生成 report.md ...")
print(f"{'=' * 70}")

# ──────────────────────────────────────────────
# 13. 生成 report.md
# ──────────────────────────────────────────────
report_lines = []
report_lines.append("# test.parquet 数据分析报告\n")
report_lines.append(f"> 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
report_lines.append(f"> 数据文件: `{FILE_PATH}` ({file_size_mb:.2f} MB)\n")

report_lines.append("\n## 1. 数据规模\n")
report_lines.append("| 指标 | 值 |")
report_lines.append("|------|-----|")
report_lines.append(f"| 文件路径 | `{FILE_PATH}` |")
report_lines.append(f"| 文件大小 | {file_size_mb:.2f} MB |")
report_lines.append(f"| 总行数 | {n_rows:,} |")
report_lines.append(f"| 总列数 | {n_cols} |")
report_lines.append(f"| 完全重复行 | {n_dup:,} |")

report_lines.append("\n## 2. 字段类型与缺失值\n")
report_lines.append("| 列名 | 数据类型 | 非空数 | 缺失数 | 缺失率 | 唯一值数 |")
report_lines.append("|------|----------|--------|--------|--------|----------|")
for col in df.columns:
    dtype = str(df[col].dtype)
    non_null = int(df[col].notna().sum())
    null_cnt = int(df[col].isna().sum())
    null_pct = f"{null_cnt / n_rows * 100:.2f}%"
    nunique = df[col].nunique()
    report_lines.append(f"| `{col}` | {dtype} | {non_null:,} | {null_cnt:,} | {null_pct} | {nunique:,} |")

report_lines.append(f"\n> **注意**: `passenger_count`、`RatecodeID`、`store_and_fwd_flag`、`congestion_surcharge` 四列的缺失行完全一致（共 {int(mask_ref.sum()):,} 行），表明这些字段在同一批记录中同步缺失。\n")
report_lines.append(f"> **注意**: `airport_fee` 列全部 {n_rows:,} 条记录均为 `None`，该列无任何有效数据。\n")

report_lines.append("\n## 3. 数值字段统计描述\n")
report_lines.append("| 字段 | 计数 | 均值 | 标准差 | 最小值 | 25% | 中位数 | 75% | 最大值 |")
report_lines.append("|------|------|------|--------|--------|-----|--------|-----|--------|")
for col in numeric_cols:
    s = df[col]
    report_lines.append(
        f"| `{col}` | {int(s.count()):,} | {s.mean():.4f} | {s.std():.4f} | {s.min():.4f} | {s.quantile(0.25):.4f} | {s.median():.4f} | {s.quantile(0.75):.4f} | {s.max():.4f} |"
    )

report_lines.append("\n## 4. 行程距离分布\n")
report_lines.append("| 区间 (英里) | 数量 | 占比 |")
report_lines.append("|-------------|------|------|")
for label, count in dist_dist.items():
    pct = count / n_rows * 100
    report_lines.append(f"| {label} | {count:,} | {pct:.2f}% |")

report_lines.append("\n## 5. 车费分布\n")
report_lines.append("| 区间 (美元) | 数量 | 占比 |")
report_lines.append("|-------------|------|------|")
for label, count in fare_dist.items():
    pct = count / n_rows * 100
    report_lines.append(f"| {label} | {count:,} | {pct:.2f}% |")

report_lines.append("\n## 6. 时间范围\n")
report_lines.append("| 指标 | 值 |")
report_lines.append("|------|-----|")
report_lines.append(f"| 最早上车时间 | {pickup_min} |")
report_lines.append(f"| 最晚上车时间 | {pickup_max} |")
report_lines.append(f"| 最早下车时间 | {dropoff_min} |")
report_lines.append(f"| 最晚下车时间 | {dropoff_max} |")

report_lines.append("\n## 7. 行程时长分析\n")
report_lines.append("| 指标 | 值 |")
report_lines.append("|------|-----|")
report_lines.append(f"| 平均时长 | {df['trip_duration_min'].mean():.2f} 分钟 |")
report_lines.append(f"| 中位数时长 | {df['trip_duration_min'].median():.2f} 分钟 |")
report_lines.append(f"| 最短时长 | {df['trip_duration_min'].min():.2f} 分钟 |")
report_lines.append(f"| 最长时长 | {df['trip_duration_min'].max():.2f} 分钟 |")
report_lines.append(f"| 负值时长记录 | {neg_dur} 条 |")
report_lines.append(f"| 零值时长记录 | {zero_dur} 条 |")
report_lines.append(f"| 超过 24 小时 | {long_dur} 条 |")

report_lines.append("\n## 8. 分类字段分布\n")
for col in cat_cols:
    report_lines.append(f"\n### {col}\n")
    report_lines.append("| 值 | 数量 | 占比 |")
    report_lines.append("|----|------|------|")
    vc = df[col].value_counts(dropna=False).sort_index()
    for val, cnt in vc.items():
        pct = cnt / n_rows * 100
        report_lines.append(f"| {val} | {cnt:,} | {pct:.2f}% |")

report_lines.append("\n## 9. 潜在异常汇总\n")
report_lines.append("| 异常类型 | 数量 | 占比 | 严重程度 |")
report_lines.append("|----------|------|------|----------|")
for name, count in anomalies:
    pct = count / n_rows * 100
    if pct > 5:
        severity = "🔴 高"
    elif pct > 1:
        severity = "🟡 中"
    elif count > 0:
        severity = "🟢 低"
    else:
        severity = "—"
    report_lines.append(f"| {name} | {count:,} | {pct:.2f}% | {severity} |")

report_lines.append("\n## 10. 相关性分析 (与 total_amount)\n")
report_lines.append("| 字段 | 相关系数 |")
report_lines.append("|------|----------|")
for col_name, val in corr.items():
    report_lines.append(f"| `{col_name}` | {val:.4f} |")

report_lines.append("\n## 11. 结论与建议\n")
report_lines.append("""### 数据质量总结

1. **数据规模**: 共 238,073 条 NYC 黄色出租车行程记录，文件大小 4.24 MB，无完全重复行。
2. **时间跨度**: 数据主要覆盖 2020 年 4 月，但存在 1 条 2009 年的异常记录。
3. **缺失值**: 4 个字段存在同步缺失（19,580 条，8.22%），`airport_fee` 列全为空值。
4. **无重复行**: 数据中不存在完全重复的记录。

### 主要异常

1. **负值车费**: 1,295 条 `fare_amount` 为负值，可能为退款/冲正记录。
2. **零值距离**: 6,225 条行程距离为 0，可能为短途接驳或数据录入问题。
3. **超长距离**: 44 条行程超过 50 英里，12 条超过 100 英里，需核实。
4. **时长异常**: 29 条负值时长（下车早于上车），3 条超过 24 小时。
5. **金额不一致**: 1,292 条 `total_amount < fare_amount`，逻辑矛盾。
6. **异常日期**: 1 条记录日期为 2009 年，与主体数据（2020 年）不符。
7. **无效编码**: 67 条 `VendorID=6`、19,580 条 `payment_type=0` 不在标准编码范围内。
8. **零乘客**: 6,582 条 `passenger_count=0`，可能为无效记录。

### 处理建议

1. 删除或修正 2009 年异常日期记录。
2. 对负值车费记录标注为退款类型或剔除。
3. 零值距离记录需结合时长判断是否有效。
4. 超长距离/时长记录建议人工复核。
5. `airport_fee` 列无有效数据，建议删除或补充。
6. 同步缺失的 19,580 条记录建议统一处理（填充或剔除）。
7. `VendorID=6` 和 `payment_type=0` 建议映射为标准编码或标记为未知。
""")

report_text = "\n".join(report_lines)
with open("/workspace/report.md", "w", encoding="utf-8") as f:
    f.write(report_text)

print(f"\n{'=' * 70}")
print(f"  报告已生成: /workspace/report.md")
print(f"  分析脚本:   /workspace/analysis.py")
print(f"{'=' * 70}")
