#!/usr/bin/env python3
"""
analysis.py - Comprehensive analysis of test.parquet (NYC Yellow Taxi Trip Data, April 2020)
"""

import pandas as pd
import numpy as np
import os

FILE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.parquet")
df = pd.read_parquet(FILE_PATH)

print(f"File size: {os.path.getsize(FILE_PATH) / 1024 / 1024:.2f} MB")
print(f"Shape: {df.shape[0]} rows x {df.shape[1]} columns")

# === Field Types ===
print("\n" + "=" * 60)
print("FIELD TYPES")
print("=" * 60)
for col in df.columns:
    print(f"  {col:30s}  {str(df[col].dtype):20s}  non-null: {df[col].notna().sum():>8d}  null: {df[col].isna().sum():>6d}")

# === Missing Values ===
print("\n" + "=" * 60)
print("MISSING VALUES")
print("=" * 60)
missing = df.isnull().sum()
missing_pct = (missing / len(df) * 100).round(2)
missing_df = pd.DataFrame({"missing_count": missing, "missing_pct": missing_pct})
print(missing_df[missing_df["missing_count"] > 0].to_string())

# === Duplicate Rows ===
print("\n" + "=" * 60)
print("DUPLICATE ROWS")
print("=" * 60)
print(f"Exact duplicate rows: {df.duplicated().sum()}")

# === Basic Statistics ===
print("\n" + "=" * 60)
print("NUMERIC COLUMN STATISTICS")
print("=" * 60)
print(df.describe().to_string())

# === Categorical Distributions ===
print("\n" + "=" * 60)
print("CATEGORICAL / ID COLUMN DISTRIBUTIONS")
print("=" * 60)
for col in ["VendorID", "passenger_count", "RatecodeID", "store_and_fwd_flag", "payment_type"]:
    print(f"\n--- {col} ---")
    print(df[col].value_counts(dropna=False).to_string())

# === Datetime Analysis ===
print("\n" + "=" * 60)
print("DATETIME ANALYSIS")
print("=" * 60)
print(f"Pickup range:  {df['tpep_pickup_datetime'].min()}  to  {df['tpep_pickup_datetime'].max()}")
print(f"Dropoff range: {df['tpep_dropoff_datetime'].min()}  to  {df['tpep_dropoff_datetime'].max()}")
before_april = df[df["tpep_pickup_datetime"] < "2020-04-01"]
print(f"Records with pickup before 2020-04-01: {len(before_april)}")
if len(before_april) > 0:
    for _, row in before_april.nsmallest(5, "tpep_pickup_datetime").iterrows():
        print(f"    {row['tpep_pickup_datetime']}  VendorID={row['VendorID']}")
after_april = df[df["tpep_pickup_datetime"] > "2020-04-30 23:59:59"]
print(f"Records with pickup after 2020-04-30: {len(after_april)}")

# === Trip Duration ===
print("\n" + "=" * 60)
print("TRIP DURATION ANALYSIS")
print("=" * 60)
df["trip_duration_min"] = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds() / 60
dur = df["trip_duration_min"]
print(f"Mean: {dur.mean():.2f} min, Median: {dur.median():.2f} min, Std: {dur.std():.2f} min")
print(f"Min: {dur.min():.2f} min, Max: {dur.max():.2f} min")
print(f"Negative: {(dur < 0).sum()}, Zero: {(dur == 0).sum()}, >24h: {(dur > 1440).sum()}")
buckets = [("< 0 min", (dur < 0).sum()), ("0-1 min", ((dur >= 0) & (dur < 1)).sum()),
    ("1-5 min", ((dur >= 1) & (dur < 5)).sum()), ("5-15 min", ((dur >= 5) & (dur < 15)).sum()),
    ("15-30 min", ((dur >= 15) & (dur < 30)).sum()), ("30-60 min", ((dur >= 30) & (dur < 60)).sum()),
    ("60-120 min", ((dur >= 60) & (dur < 120)).sum()), ("> 120 min", (dur >= 120).sum())]
for label, count in buckets:
    print(f"  {label:>12s}: {count:>7d}  ({count / len(df) * 100:>5.1f}%)")

# === Anomaly Detection ===
print("\n" + "=" * 60)
print("ANOMALY DETECTION")
print("=" * 60)
anomalies = {
    "fare_amount < 0": (df["fare_amount"] < 0).sum(),
    "fare_amount == 0": (df["fare_amount"] == 0).sum(),
    "total_amount < 0": (df["total_amount"] < 0).sum(),
    "trip_distance == 0": (df["trip_distance"] == 0).sum(),
    "passenger_count == 0": (df["passenger_count"] == 0).sum(),
    "passenger_count > 6": (df["passenger_count"] > 6).sum(),
    "fare=0 but distance>0": ((df["fare_amount"] == 0) & (df["trip_distance"] > 0)).sum(),
    "distance=0 but fare>0": ((df["trip_distance"] == 0) & (df["fare_amount"] > 0)).sum(),
}
for label, count in anomalies.items():
    print(f"  {label:35s}: {count:>6d}")

print("\nTop 10 highest fare_amount:")
print(df.nlargest(10, "fare_amount")[["fare_amount", "total_amount", "trip_distance"]].to_string())
print("\nTop 10 highest trip_distance:")
print(df.nlargest(10, "trip_distance")[["fare_amount", "total_amount", "trip_distance"]].to_string())

# === Skewness, Kurtosis, IQR Outliers ===
print("\n" + "=" * 60)
print("SKEWNESS, KURTOSIS, IQR OUTLIERS")
print("=" * 60)
for col in ["trip_distance", "fare_amount", "total_amount", "tip_amount", "passenger_count"]:
    skew = df[col].skew()
    kurt = df[col].kurtosis()
    q1, q3 = df[col].quantile(0.25), df[col].quantile(0.75)
    iqr = q3 - q1
    outliers = ((df[col] < q1 - 1.5 * iqr) | (df[col] > q3 + 1.5 * iqr)).sum()
    print(f"  {col:20s}  skew={skew:>10.3f}  kurt={kurt:>12.3f}  IQR_outliers={outliers:>6d} ({outliers/len(df)*100:.1f}%)")

# === Percentiles ===
print("\n" + "=" * 60)
print("PERCENTILES")
print("=" * 60)
for col in ["trip_distance", "fare_amount", "total_amount", "tip_amount"]:
    print(f"\n  {col}:")
    for p in [50, 75, 90, 95, 99, 99.5, 99.9]:
        print(f"    P{p}: {df[col].quantile(p / 100):.2f}")

# === Correlations ===
print("\n" + "=" * 60)
print("KEY CORRELATIONS (|r| > 0.3)")
print("=" * 60)
numeric_df = df.select_dtypes(include=[np.number])
corr = numeric_df.corr()
pairs = []
for i in range(len(corr.columns)):
    for j in range(i + 1, len(corr.columns)):
        r = corr.iloc[i, j]
        if abs(r) > 0.3:
            pairs.append((corr.columns[i], corr.columns[j], r))
pairs.sort(key=lambda x: abs(x[2]), reverse=True)
for c1, c2, r in pairs:
    print(f"  {c1:25s} <-> {c2:25s}  r={r:.3f}")

# === Special Notes ===
print("\n" + "=" * 60)
print("SPECIAL NOTES")
print("=" * 60)
print(f"  airport_fee: 100% null ({len(df)} values)")
print(f"  VendorID=6: {len(df[df['VendorID']==6])} rows, all missing passenger_count/RatecodeID/store_and_fwd_flag/congestion_surcharge")
print(f"  payment_type=0: {(df['payment_type']==0).sum()} rows")
print(f"  RatecodeID=99: {(df['RatecodeID']==99.0).sum()} rows")
print("\nANALYSIS COMPLETE")
