"""
analysis.py — Comprehensive analysis of test.parquet (NYC Yellow Taxi Trip Data)
Generates: report.md with real computed statistics.
"""

import os
import pandas as pd
import numpy as np

# ──────────────────────────────────────────────
# 0. Load data
# ──────────────────────────────────────────────
FILE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.parquet")
df = pd.read_parquet(FILE_PATH)
file_size_mb = os.path.getsize(FILE_PATH) / (1024 * 1024)

# ──────────────────────────────────────────────
# 1. Data Scale
# ──────────────────────────────────────────────
n_rows, n_cols = df.shape
memory_usage_mb = df.memory_usage(deep=True).sum() / (1024 * 1024)
date_min = df["tpep_pickup_datetime"].min()
date_max = df["tpep_pickup_datetime"].max()

# ──────────────────────────────────────────────
# 2. Field Types
# ──────────────────────────────────────────────
dtype_info = []
for col in df.columns:
    dtype_info.append({
        "column": col,
        "pandas_dtype": str(df[col].dtype),
        "non_null": int(df[col].notna().sum()),
        "null": int(df[col].isna().sum()),
        "unique": int(df[col].nunique()),
    })
dtype_df = pd.DataFrame(dtype_info)

# ──────────────────────────────────────────────
# 3. Missing Values
# ──────────────────────────────────────────────
missing = df.isnull().sum()
missing_pct = (missing / n_rows * 100).round(4)
missing_summary = pd.DataFrame({
    "column": missing.index,
    "missing_count": missing.values,
    "missing_pct": missing_pct.values,
})
missing_summary = missing_summary[missing_summary["missing_count"] > 0].sort_values(
    "missing_count", ascending=False
)

# ──────────────────────────────────────────────
# 4. Duplicate Rows
# ──────────────────────────────────────────────
n_duplicates = int(df.duplicated().sum())
dup_pct = round(n_duplicates / n_rows * 100, 4)
key_cols = ["tpep_pickup_datetime", "tpep_dropoff_datetime", "trip_distance", "total_amount"]
n_key_duplicates = int(df.duplicated(subset=key_cols).sum())

# ──────────────────────────────────────────────
# 5. Numeric Distribution
# ──────────────────────────────────────────────
numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
numeric_stats = df[numeric_cols].describe().T
numeric_stats["skew"] = df[numeric_cols].skew()
numeric_stats["kurtosis"] = df[numeric_cols].kurtosis()
numeric_stats["missing"] = df[numeric_cols].isnull().sum()

# ──────────────────────────────────────────────
# 6. Potential Anomalies
# ──────────────────────────────────────────────
anomalies = []
df["trip_duration_min"] = (
    df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]
).dt.total_seconds() / 60.0

neg_duration = int((df["trip_duration_min"] < 0).sum())
zero_duration = int((df["trip_duration_min"] == 0).sum())
extreme_duration = int((df["trip_duration_min"] > 1440).sum())
anomalies.append(("Negative trip duration", neg_duration))
anomalies.append(("Zero trip duration", zero_duration))
anomalies.append(("Trip duration > 24 hours", extreme_duration))

for col in ["fare_amount", "total_amount", "tip_amount", "trip_distance"]:
    neg_count = int((df[col] < 0).sum())
    anomalies.append((f"Negative {col}", neg_count))

zero_distance = int((df["trip_distance"] == 0).sum())
anomalies.append(("Zero trip distance", zero_distance))

passenger_counts = df["passenger_count"].dropna()
invalid_passengers = int(((passenger_counts <= 0) | (passenger_counts > 8)).sum())
anomalies.append(("Passenger count <= 0 or > 8", invalid_passengers))

for col in ["fare_amount", "total_amount", "trip_distance"]:
    q1 = df[col].quantile(0.25)
    q3 = df[col].quantile(0.75)
    iqr = q3 - q1
    upper = q3 + 3 * iqr
    n_outliers = int((df[col] > upper).sum())
    anomalies.append((f"{col} extreme outlier (>{upper:.2f})", n_outliers))

before_pickup = int((df["tpep_dropoff_datetime"] < df["tpep_pickup_datetime"]).sum())
anomalies.append(("Dropoff before pickup", before_pickup))

# ──────────────────────────────────────────────
# 8. Generate report.md
# ──────────────────────────────────────────────
report_lines = []
def w(line=""):
    report_lines.append(line)

w("# test.parquet 数据分析报告")
w()
w("> 自动生成于 analysis.py，所有数值均基于真实计算。")
w()

w("## 1. 数据规模")
w()
w("| 指标 | 值 |")
w("|------|------|")
w(f"| 文件路径 | `{FILE_PATH}` |")
w(f"| 文件大小 | {file_size_mb:.2f} MB |")
w(f"| 行数 | {n_rows:,} |")
w(f"| 列数 | {n_cols} |")
w(f"| 内存占用 (pandas) | {memory_usage_mb:.2f} MB |")
w(f"| 时间范围 | {date_min} ~ {date_max} |")
w()

w("## 2. 字段类型与基本信息")
w()
w("| 列名 | 数据类型 | 非空值数 | 缺失值数 | 唯一值数 |")
w("|------|---------|---------|---------|---------|")
for _, row in dtype_df.iterrows():
    w(f"| {row['column']} | {row['pandas_dtype']} | {int(row['non_null']):,} | {int(row['null']):,} | {int(row['unique']):,} |")
w()

w("## 3. 缺失值分析")
w()
if len(missing_summary) == 0:
    w("数据集中**没有缺失值**。")
else:
    w("以下列存在缺失值：")
    w()
    w("| 列名 | 缺失数量 | 缺失比例 (%) |")
    w("|------|---------|-------------|")
    for _, row in missing_summary.iterrows():
        w(f"| {row['column']} | {int(row['missing_count']):,} | {row['missing_pct']:.4f} |")
w()

w("## 4. 重复值分析")
w()
w("| 指标 | 值 |")

w("## 5. 数值字段分布统计")
w()
w("### 5.1 描述性统计")
w()
w("| 字段 | count | mean | std | min | 25% | 50% | 75% | max | skew | kurtosis |")
w("|------|-------|------|-----|-----|-----|-----|-----|-----|------|----------|")
for col in numeric_cols:
    s = numeric_stats.loc[col]
    w(f"| {col} | {int(s['count']):,} | {s['mean']:.4f} | {s['std']:.4f} | {s['min']:.4f} | {s['25%']:.4f} | {s['50%']:.4f} | {s['75%']:.4f} | {s['max']:.4f} | {s['skew']:.4f} | {s['kurtosis']:.4f} |")
w()

w("### 5.2 分位数详情 (关键金额字段)")
w()
for col in ["fare_amount", "tip_amount", "total_amount", "trip_distance"]:
    quantiles = df[col].quantile([0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99, 0.999])
    w(f"**{col}**")
    w()
    w("| 分位 | 1% | 5% | 10% | 25% | 50% | 75% | 90% | 95% | 99% | 99.9% |")
    w("|------|----|----|-----|-----|-----|-----|-----|-----|-----|-------|")
    vals = " | ".join([f"{v:.2f}" for v in quantiles.values])
    w(f"| 值 | {vals} |")
    w()

w("## 6. 分类 / ID 字段分布")
w()
w("**VendorID** (出租车公司):")
w()
for k, v in sorted(vendor_counts.items()):
    w(f"- VendorID={int(k)}: {v:,} 条 ({v/n_rows*100:.2f}%)")
w()

w("**payment_type** (支付方式):")
w()
payment_labels = {1: "信用卡", 2: "现金", 3: "免费", 4: "争议", 5: "未知", 6: "无效"}
for k, v in sorted(payment_counts.items()):
    label = payment_labels.get(int(k), "其他")
    w(f"- {int(k)} ({label}): {v:,} 条 ({v/n_rows*100:.2f}%)")
w()

w("**RatecodeID** (费率代码):")
w()
for k, v in sorted(ratecode_counts.items(), key=lambda x: -x[1]):
    w(f"- RatecodeID={int(k) if pd.notna(k) else 'NaN'}: {v:,} 条 ({v/n_rows*100:.2f}%)")
w()

w("**store_and_fwd_flag** (是否离线存储):")
w()
for k, v in store_fwd_counts.items():
    w(f"- {k}: {v:,} 条 ({v/n_rows*100:.2f}%)")
w()

w("## 7. 潜在异常与数据质量问题")
w()
w("| 异常类型 | 数量 | 说明 |")
w("|---------|------|------|")
for name, count in anomalies:
    w(f"| {name} | {count:,} | 需关注 |")
w()

w("### 7.1 行程时长分布")
w()
dur = df["trip_duration_min"]
w("| 统计量 | 值 |")
w("|--------|------|")
w(f"| 均值 | {dur.mean():.2f} 分钟 |")
w(f"| 中位数 | {dur.median():.2f} 分钟 |")
w(f"| 最小值 | {dur.min():.2f} 分钟 |")
w(f"| 最大值 | {dur.max():.2f} 分钟 |")
w(f"| 标准差 | {dur.std():.2f} 分钟 |")
w()

# Section 8 — Summary
airport_fee_missing = int(missing_summary.loc[missing_summary['column']=='airport_fee','missing_count'].iloc[0]) if 'airport_fee' in missing_summary['column'].values else 0

w("## 8. 总结")
w()
w(f"1. **数据规模**: 共 {n_rows:,} 行 × {n_cols} 列，文件大小 {file_size_mb:.2f} MB，"
  f"涵盖 {date_min.strftime('%Y-%m-%d')} 至 {date_max.strftime('%Y-%m-%d')} 的纽约黄色出租车行程记录。")
w()
w(f"2. **缺失值**: 共 {len(missing_summary)} 个字段存在缺失，"
  f"其中 `airport_fee` 缺失最多 ({airport_fee_missing:,} 条)。")
w()
w(f"3. **重复值**: 完全重复行 {n_duplicates:,} 条 ({dup_pct:.4f}%)，"
  f"关键字段重复 {n_key_duplicates:,} 条。")
w()
w(f"4. **异常记录**: 发现 {neg_duration} 条负时长、{extreme_duration} 条超过 24 小时的行程、"
  f"{zero_distance} 条零里程、以及多组金额/距离极端异常值。")
w()
w("5. **建议**: 在建模前建议清洗负值/零值异常、处理缺失的 `airport_fee` 与 `passenger_count`，"
  "并对极端金额做截断或分箱处理。")
w()

report_text = "\n".join(report_lines)
report_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report.md")
with open(report_path, "w", encoding="utf-8") as f:
    f.write(report_text)

print(f"✅ report.md generated at {report_path}")
print(f"   Rows: {n_rows:,}  Cols: {n_cols}  Duplicates: {n_duplicates:,}  Anomalies: {len(anomalies)}")

