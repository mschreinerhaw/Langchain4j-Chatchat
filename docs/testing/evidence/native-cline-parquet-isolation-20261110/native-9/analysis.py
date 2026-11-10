"""
test.parquet 数据质量分析脚本
==============================
数据源: /workspace/test.parquet
描述: 纽约黄色出租车行程记录 (NYC Yellow Taxi Trip Records)
"""

import pandas as pd
import numpy as np
import os
import sys

FILE_PATH = "/workspace/test.parquet"

if not os.path.exists(FILE_PATH):
    print(f"错误: 文件 {FILE_PATH} 不存在")
    sys.exit(1)

df = pd.read_parquet(FILE_PATH)

print("=" * 70)
print("  test.parquet 数据质量分析报告")
print("=" * 70)

# 1. 数据规模
n_rows, n_cols = df.shape
file_size_bytes = os.path.getsize(FILE_PATH)
memory_bytes = df.memory_usage(deep=True).sum()

print(f"\n{'='*70}")
print("1. 数据规模")
print(f"{'='*70}")
print(f"  行数:           {n_rows:,}")
print(f"  列数:           {n_cols}")
print(f"  文件大小:       {file_size_bytes:,} bytes ({file_size_bytes/1024/1024:.2f} MB)")
print(f"  内存占用:       {memory_bytes:,} bytes ({memory_bytes/1024/1024:.2f} MB)")

# 2. 字段类型
print(f"\n{'='*70}")
print("2. 字段类型")
print(f"{'='*70}")
dtype_summary = df.dtypes.value_counts()
for dtype, count in dtype_summary.items():
    print(f"  {str(dtype):20s} : {count} 列")

print(f"\n  {'列名':30s} {'类型':20s} {'非空数':>10s} {'缺失数':>10s}")
print(f"  {'-'*30} {'-'*20} {'-'*10} {'-'*10}")
for col in df.columns:
    non_null = df[col].notna().sum()
    null_count = df[col].isna().sum()
    print(f"  {col:30s} {str(df[col].dtype):20s} {non_null:>10,} {null_count:>10,}")

# 3. 缺失值分析
print(f"\n{'='*70}")
print("3. 缺失值分析")
print(f"{'='*70}")
missing = df.isnull().sum()
missing_pct = (missing / n_rows * 100).round(4)
missing_info = pd.DataFrame({"缺失数": missing, "缺失率(%)": missing_pct})
missing_info = missing_info[missing_info["缺失数"] > 0].sort_values("缺失数", ascending=False)
if len(missing_info) > 0:
    print("  存在缺失值的列:")
    for col, row in missing_info.iterrows():
        print(f"    {col:30s} : {int(row['缺失数']):>8,} 条 ({row['缺失率(%)']:.4f}%)")
else:
    print("  无缺失值")

# 4. 重复值分析
print(f"\n{'='*70}")
print("4. 重复值分析")
print(f"{'='*70}")
dup_full = df.duplicated().sum()
print(f"  完全重复行数:   {dup_full:,} ({dup_full/n_rows*100:.4f}%)")
key_cols = ["VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "PULocationID", "DOLocationID"]
dup_key = df.duplicated(subset=key_cols).sum()
print(f"  关键字段重复行: {dup_key:,} ({dup_key/n_rows*100:.4f}%)")

# 5. 数值型字段统计描述
print(f"\n{'='*70}")
print("5. 数值型字段统计描述")
print(f"{'='*70}")
numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
desc = df[numeric_cols].describe().T
desc["缺失数"] = df[numeric_cols].isnull().sum()
desc["偏度"] = df[numeric_cols].skew()
desc["峰度"] = df[numeric_cols].kurtosis()
print(desc.to_string())

# 6. 分类/离散字段分析
print(f"\n{'='*70}")
print("6. 分类/离散字段分析")
print(f"{'='*70}")
cat_cols = ["VendorID", "payment_type", "RatecodeID", "store_and_fwd_flag"]
for col in cat_cols:
    print(f"\n  --- {col} ---")
    vc = df[col].value_counts()
    print(f"  唯一值数量: {df[col].nunique()}")
    for val, cnt in vc.items():
        print(f"    {str(val):>10s} : {cnt:>8,} ({cnt/n_rows*100:.2f}%)")

# 7. 时间字段分析
print(f"\n{'='*70}")
print("7. 时间字段分析")
print(f"{'='*70}")
print(f"  上车时间范围: {df['tpep_pickup_datetime'].min()} ~ {df['tpep_pickup_datetime'].max()}")
print(f"  下车时间范围: {df['tpep_dropoff_datetime'].min()} ~ {df['tpep_dropoff_datetime'].max()}")
df["trip_duration_min"] = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds() / 60
print(f"\n  行程时长(分钟)统计:")
print(f"    最小值: {df['trip_duration_min'].min():.2f}")
print(f"    25%:    {df['trip_duration_min'].quantile(0.25):.2f}")
print(f"    中位数: {df['trip_duration_min'].median():.2f}")
print(f"    75%:    {df['trip_duration_min'].quantile(0.75):.2f}")
print(f"    最大值: {df['trip_duration_min'].max():.2f}")
print(f"    均值:   {df['trip_duration_min'].mean():.2f}")

# 8. 异常值检测
print(f"\n{'='*70}")
print("8. 异常值检测")
print(f"{'='*70}")

print("\n  [负值检测]")
check_neg_cols = ["fare_amount", "trip_distance", "passenger_count", "total_amount", "tip_amount", "tolls_amount", "extra", "mta_tax"]
for col in check_neg_cols:
    neg_count = (df[col] < 0).sum()
    if neg_count > 0:
        print(f"    {col}: {neg_count:,} 条负值记录 (最小值={df[col].min():.2f})")

print("\n  [IQR 异常值检测 (1.5倍)]")
iqr_cols = ["fare_amount", "trip_distance", "total_amount", "tip_amount", "passenger_count"]
for col in iqr_cols:
    Q1 = df[col].quantile(0.25)
    Q3 = df[col].quantile(0.75)
    IQR = Q3 - Q1
    lower = Q1 - 1.5 * IQR
    upper = Q3 + 1.5 * IQR
    outlier_count = ((df[col] < lower) | (df[col] > upper)).sum()
    print(f"    {col:20s}: IQR={IQR:.2f}, 范围=[{lower:.2f}, {upper:.2f}], 异常值={outlier_count:,} ({outlier_count/n_rows*100:.2f}%)")

print("\n  [极端值 - Top 5 最大值]")
extreme_cols = ["fare_amount", "trip_distance", "total_amount", "tip_amount", "tolls_amount"]
for col in extreme_cols:
    top5 = df[col].nlargest(5).values
    print(f"    {col:20s}: {', '.join(f'{v:.2f}' for v in top5)}")

print("\n  [行程时长异常]")
neg_dur = (df["trip_duration_min"] < 0).sum()
zero_dur = (df["trip_duration_min"] == 0).sum()
long_dur = (df["trip_duration_min"] > 180).sum()
print(f"    负时长: {neg_dur} 条")
print(f"    零时长: {zero_dur} 条")
print(f"    超3小时: {long_dur} 条")

print("\n  [日期异常]")
pre_2020 = (df["tpep_pickup_datetime"] < "2020-01-01").sum()
print(f"    2020年之前的记录: {pre_2020} 条")
if pre_2020 > 0:
    old = df[df["tpep_pickup_datetime"] < "2020-01-01"]
    print(f"    示例: {old[['tpep_pickup_datetime', 'tpep_dropoff_datetime', 'trip_distance', 'fare_amount']].to_string()}")

print("\n  [距离异常]")
extreme_dist = (df["trip_distance"] > 100).sum()
print(f"    行程距离 > 100 英里: {extreme_dist} 条")
if extreme_dist > 0:
    print(f"    最大距离: {df['trip_distance'].max():.2f} 英里")

same_loc = (df["PULocationID"] == df["DOLocationID"]).sum()
print(f"\n  [上下车地点相同]: {same_loc:,} 条 ({same_loc/n_rows*100:.2f}%)")

# 9. 相关性分析
print(f"\n{'='*70}")
print("9. 相关性分析 (Pearson)")
print(f"{'='*70}")
corr = df[numeric_cols].corr()
print(corr.round(3).to_string())

print("\n  高相关对 (|r| > 0.4):")
for i in range(len(corr.columns)):
    for j in range(i+1, len(corr.columns)):
        r = corr.iloc[i, j]
        if abs(r) > 0.4:
            print(f"    {corr.columns[i]:25s} <-> {corr.columns[j]:25s} : r = {r:.4f}")

# 10. 数据质量总结
print(f"\n{'='*70}")
print("10. 数据质量总结")
print(f"{'='*70}")

issues = []
if missing.sum() > 0:
    cols_with_missing = missing[missing > 0].index.tolist()
    issues.append(f"缺失值: {len(cols_with_missing)} 列存在缺失, 其中 airport_fee 列 100% 缺失")
if dup_full > 0:
    issues.append(f"完全重复行: {dup_full} 行")
total_neg = sum((df[c] < 0).sum() for c in check_neg_cols)
if total_neg > 0:
    issues.append(f"负值: 共 {total_neg} 条记录存在负值")
if pre_2020 > 0:
    issues.append(f"日期异常: {pre_2020} 条记录时间戳在 2020 年之前")
if extreme_dist > 0:
    issues.append(f"距离异常: {extreme_dist} 条记录距离 > 100 英里")
if neg_dur > 0:
    issues.append(f"时长异常: {neg_dur} 条行程时长为负值")
high_skew = [(c, df[c].skew()) for c in iqr_cols if df[c].skew() > 5]
if high_skew:
    issues.append(f"分布极度偏斜: {', '.join(f'{c}(skew={s:.1f})' for c, s in high_skew)}")

for i, issue in enumerate(issues, 1):
    print(f"  {i}. {issue}")

print(f"\n{'='*70}")
print("分析完成!")
print(f"{'='*70}")
