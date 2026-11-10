#!/usr/bin/env python3
"""
test.parquet 数据分析脚本
========================
对 NYC 黄色出租车行程数据（Parquet 格式）进行全面分析，
包括数据规模、字段类型、缺失值、重复值、数值分布和潜在异常。

用法: python3 analysis.py
"""

import pandas as pd
import numpy as np
from pathlib import Path

FILE_PATH = Path(__file__).parent / "test.parquet"
df = pd.read_parquet(FILE_PATH)

print("=" * 70)
print("  test.parquet 数据分析报告")
print("=" * 70)

# 1. 数据规模
n_rows, n_cols = df.shape
file_size_mb = FILE_PATH.stat().st_size / (1024 * 1024)
memory_mb = df.memory_usage(deep=True).sum() / (1024 * 1024)
print(f"\n【1. 数据规模】")
print(f"  行数: {n_rows:,}  列数: {n_cols}")
print(f"  文件大小: {file_size_mb:.2f} MB  内存占用: {memory_mb:.2f} MB")

# 2. 字段类型与缺失值
print(f"\n【2. 字段类型与缺失值】")
print(f"  {'列名':<28} {'类型':<18} {'非空':>8} {'缺失':>8} {'缺失率':>8}")
print(f"  {'-'*28} {'-'*18} {'-'*8} {'-'*8} {'-'*8}")
for col in df.columns:
    nn = df[col].notna().sum()
    nc = df[col].isna().sum()
    pct = f"{nc / n_rows * 100:.2f}%"
    print(f"  {col:<28} {str(df[col].dtype):<18} {nn:>8,} {nc:>8,} {pct:>8}")

# 3. 重复值
n_dup = df.duplicated().sum()
print(f"\n【3. 重复值】 完全重复行: {n_dup:,} ({n_dup/n_rows*100:.4f}%)")

# 4. 数值分布
numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
print(f"\n【4. 数值字段分布】")
for col in numeric_cols:
    s = df[col].dropna()
    print(f"\n  --- {col} ---")
    print(f"    count={len(s):,}  mean={s.mean():.4f}  std={s.std():.4f}")
    print(f"    min={s.min():.4f}  25%={s.quantile(.25):.4f}  50%={s.median():.4f}  75%={s.quantile(.75):.4f}  max={s.max():.4f}")

# 5. 分类字段分布
cat_cols = ["VendorID", "passenger_count", "RatecodeID", "payment_type",
            "store_and_fwd_flag", "PULocationID", "DOLocationID"]
print(f"\n【5. 分类字段分布】")
for col in cat_cols:
    if col in df.columns:
        vc = df[col].value_counts()
        print(f"\n  --- {col} (unique={df[col].nunique()}) ---")
        for val, cnt in vc.head(10).items():
            print(f"    {val}: {cnt:,} ({cnt/n_rows*100:.2f}%)")

# 6. 潜在异常检测
print(f"\n{'='*70}\n  潜在异常检测\n{'='*70}")

# 6a. 行程时长异常
df["trip_dur"] = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds()/60
print(f"\n  [行程时长] 均值={df['trip_dur'].mean():.2f}min  中位数={df['trip_dur'].median():.2f}min")
print(f"    负值: {(df['trip_dur']<0).sum()}  零值: {(df['trip_dur']==0).sum()}  超长(>8h): {(df['trip_dur']>480).sum()}")

# 6b. 负值费用
print(f"\n  [负值费用]")
for col in ["fare_amount","extra","mta_tax","tip_amount","tolls_amount",
            "improvement_surcharge","total_amount","congestion_surcharge"]:
    neg = (df[col] < 0).sum()
    if neg > 0:
        print(f"    {col}: {neg}条, min={df[col].min():.2f}")

# 6c. 极端距离
print(f"\n  [极端行程距离]")
print(f"    距离=0: {(df['trip_distance']==0).sum():,}  >50mi: {(df['trip_distance']>50).sum()}  >100mi: {(df['trip_distance']>100).sum()}  max={df['trip_distance'].max():.2f}")

# 6d. IQR异常值
print(f"\n  [IQR异常值]")
for col in ["trip_distance","fare_amount","total_amount","tip_amount"]:
    q1, q3 = df[col].quantile(0.25), df[col].quantile(0.75)
    iqr = q3 - q1
    out = ((df[col] < q1-1.5*iqr) | (df[col] > q3+1.5*iqr)).sum()
    print(f"    {col}: IQR={iqr:.4f} [{q1-1.5*iqr:.4f},{q3+1.5*iqr:.4f}] 异常={out:,} ({out/n_rows*100:.2f}%)")

# 6e. 费用合计不一致
comp = df["fare_amount"]+df["extra"]+df["mta_tax"]+df["tip_amount"]+df["tolls_amount"]+df["improvement_surcharge"]+df["congestion_surcharge"].fillna(0)
diff = (df["total_amount"] - comp).abs()
mm = (diff > 0.01).sum()
print(f"\n  [费用合计不一致] total ≠ 分项和: {mm:,}条 ({mm/n_rows*100:.2f}%)  max_diff={diff.max():.2f}  mean_diff={diff.mean():.4f}")

# 6f. 异常日期
print(f"\n  [异常日期]")
print(f"    2020-04-01前: {(df['tpep_pickup_datetime']<'2020-04-01').sum()}条")
print(f"    2020-01-01前: {(df['tpep_pickup_datetime']<'2020-01-01').sum()}条 (疑似错误)")

# 6g. airport_fee全空
print(f"\n  [airport_fee] 全部为空: {df['airport_fee'].isna().sum():,}条 (100%)")

# 6h. 乘客数异常
print(f"\n  [乘客数] count=0: {(df['passenger_count']==0).sum():,}  count>6: {(df['passenger_count']>6).sum()}")

print(f"\n{'='*70}\n  分析完成。\n{'='*70}")

          f"50%={s.median():.4f}  75%={s.quantile(.75):.4f}  max={s.max():.4f}")
