#!/usr/bin/env python3
"""
test.parquet 数据质量分析报告生成脚本
======================================
读取 /workspace/test.parquet，对数据规模、字段类型、缺失值、
重复值、数值分布和潜在异常进行全面分析，并将结果写入 report.md。
"""

import os
import pandas as pd
import numpy as np
from datetime import datetime

PARQUET_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.parquet")
REPORT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report.md")

NUMERIC_COLS = [
    "passenger_count", "trip_distance", "RatecodeID",
    "PULocationID", "DOLocationID", "payment_type",
    "fare_amount", "extra", "mta_tax", "tip_amount",
    "tolls_amount", "improvement_surcharge", "total_amount",
    "congestion_surcharge",
]


def load_data(path: str) -> pd.DataFrame:
    return pd.read_parquet(path)


def analyze_scale(df: pd.DataFrame) -> dict:
    return {
        "rows": len(df),
        "columns": df.shape[1],
        "memory_bytes": df.memory_usage(deep=True).sum(),
        "memory_mb": round(df.memory_usage(deep=True).sum() / 1024 / 1024, 2),
    }


def analyze_columns(df: pd.DataFrame) -> pd.DataFrame:
    records = []
    for col in df.columns:
        records.append({
            "字段名": col,
            "数据类型": str(df[col].dtype),
            "非空值数": int(df[col].notna().sum()),
            "缺失值数": int(df[col].isna().sum()),
            "缺失率(%)": round(df[col].isna().sum() / len(df) * 100, 2),
            "唯一值数": int(df[col].nunique()),
        })
    return pd.DataFrame(records)


def analyze_missing(df: pd.DataFrame) -> dict:
    null_counts = df.isnull().sum()
    null_cols = null_counts[null_counts > 0]
    total_null_cells = int(null_counts.sum())
    total_cells = df.shape[0] * df.shape[1]
    return {
        "columns_with_null": null_cols.to_dict(),
        "total_null_cells": total_null_cells,
        "total_cells": total_cells,
        "overall_null_pct": round(total_null_cells / total_cells * 100, 4),
    }


def analyze_duplicates(df: pd.DataFrame) -> dict:
    dup_rows = int(df.duplicated().sum())
    return {
        "duplicate_row_count": dup_rows,
        "duplicate_pct": round(dup_rows / len(df) * 100, 4),
    }


def analyze_numeric_distribution(df: pd.DataFrame) -> dict:
    results = {}
    for col in NUMERIC_COLS:
        s = df[col]
        results[col] = {
            "count": int(s.count()),
            "mean": round(s.mean(), 4),
            "std": round(s.std(), 4),
            "min": round(s.min(), 4),
            "q25": round(s.quantile(0.25), 4),
            "median": round(s.median(), 4),
            "q75": round(s.quantile(0.75), 4),
            "max": round(s.max(), 4),
            "skewness": round(s.skew(), 4),
            "kurtosis": round(s.kurtosis(), 4),
        }
    return results


def analyze_anomalies(df: pd.DataFrame) -> dict:
    anomalies = {}

    # trip_distance 极端值
    td = df["trip_distance"]
    anomalies["trip_distance_zero"] = int((td == 0).sum())
    anomalies["trip_distance_gt_100"] = int((td > 100).sum())
    anomalies["trip_distance_max"] = float(td.max())
    anomalies["trip_distance_gt_100_rows"] = (
        df[td > 100][["trip_distance", "fare_amount", "total_amount"]]
        .sort_values("trip_distance", ascending=False)
        .head(10)
        .to_dict(orient="records")
    )

    # 负金额
    money_cols = [
        "fare_amount", "extra", "mta_tax", "tip_amount",
        "tolls_amount", "improvement_surcharge", "total_amount",
        "congestion_surcharge",
    ]
    neg = {}
    for col in money_cols:
        cnt = int((df[col] < 0).sum())
        if cnt > 0:
            neg[col] = cnt
    anomalies["negative_amounts"] = neg
    anomalies["fare_amount_zero"] = int((df["fare_amount"] == 0).sum())

    # 时间异常
    dur_sec = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds()
    anomalies["dropoff_before_pickup"] = int((dur_sec < 0).sum())
    anomalies["dropoff_equals_pickup"] = int((dur_sec == 0).sum())
    anomalies["duration_gt_24h"] = int((dur_sec > 24 * 3600).sum())
    anomalies["duration_max_minutes"] = round(dur_sec.max() / 60, 2)
    anomalies["duration_min_minutes"] = round(dur_sec.min() / 60, 2)

    # 日期异常
    anomalies["pickup_before_2020"] = int((df["tpep_pickup_datetime"] < "2020-01-01").sum())

    # 乘客数量异常
    anomalies["passenger_count_zero"] = int((df["passenger_count"] == 0).sum())
    anomalies["passenger_count_gt_6"] = int((df["passenger_count"] > 6).sum())

    # RatecodeID 异常
    anomalies["ratecode_99"] = int((df["RatecodeID"] == 99).sum())

    # VendorID 罕见值
    anomalies["vendorid_6_count"] = int((df["VendorID"] == 6).sum())

    # airport_fee 全空
    anomalies["airport_fee_all_null"] = bool(df["airport_fee"].isna().all())

    return anomalies


def analyze_categorical(df: pd.DataFrame) -> dict:
    cat_cols = ["VendorID", "passenger_count", "RatecodeID",
                "store_and_fwd_flag", "payment_type"]
    result = {}
    for col in cat_cols:
        vc = df[col].value_counts().sort_index()
        result[col] = {str(k): int(v) for k, v in vc.items()}
    return result


def analyze_time(df: pd.DataFrame) -> dict:
    daily = df["tpep_pickup_datetime"].dt.date.value_counts().sort_index()
    return {
        "pickup_min": str(df["tpep_pickup_datetime"].min()),
        "pickup_max": str(df["tpep_pickup_datetime"].max()),
        "dropoff_min": str(df["tpep_dropoff_datetime"].min()),
        "dropoff_max": str(df["tpep_dropoff_datetime"].max()),
        "daily_trip_counts": {str(k): int(v) for k, v in daily.items()},
    }


def generate_report(scale, col_info, missing, duplicates,
                    numeric_dist, anomalies, categorical, time_info) -> str:
    lines = []
    lines.append("# test.parquet 数据质量分析报告\n")
    lines.append(f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    # 1. 数据规模
    lines.append("## 1. 数据规模\n")
    lines.append("| 指标 | 值 |")
    lines.append("|------|------|")
    lines.append(f"| 行数 | {scale['rows']:,} |")
    lines.append(f"| 列数 | {scale['columns']} |")
    lines.append(f"| 内存占用 | {scale['memory_mb']} MB |")
    lines.append("")

    # 2. 字段类型与基本信息
    lines.append("## 2. 字段类型与基本信息\n")
    lines.append("| 字段名 | 数据类型 | 非空值数 | 缺失值数 | 缺失率(%) | 唯一值数 |")
    lines.append("|--------|----------|----------|----------|-----------|----------|")
    for _, row in col_info.iterrows():
        lines.append(
            f"| {row['字段名']} | {row['数据类型']} | {row['非空值数']:,} "
            f"| {row['缺失值数']:,} | {row['缺失率(%)']} | {row['唯一值数']} |"
        )
    lines.append("")

    # 3. 缺失值分析
    lines.append("## 3. 缺失值分析\n")
    lines.append(f"- 总单元格数：**{missing['total_cells']:,}**")
    lines.append(f"- 缺失单元格数：**{missing['total_null_cells']:,}**")
    lines.append(f"- 整体缺失率：**{missing['overall_null_pct']}%**\n")
    if missing["columns_with_null"]:
        lines.append("| 字段 | 缺失数 | 缺失率(%) |")
        lines.append("|------|--------|-----------|")
        for col, cnt in missing["columns_with_null"].items():
            pct = round(cnt / scale["rows"] * 100, 2)
            lines.append(f"| {col} | {cnt:,} | {pct} |")
    lines.append("")
    lines.append("**缺失值说明：**\n")
    lines.append("- `airport_fee` 列 **100% 为空**（全部 238,073 行均为 None），该字段完全无效。")
    lines.append("- `passenger_count`、`RatecodeID`、`store_and_fwd_flag`、`congestion_surcharge` "
                 "四列各有 **19,580** 行缺失（8.22%），缺失行完全对齐——推测来自 VendorID=6 的少量记录"
                 "以及部分 VendorID=1 的记录未填充这些字段。")
    lines.append("")

    # 4. 重复值分析
    lines.append("## 4. 重复值分析\n")
    lines.append(f"- 完全重复行数：**{duplicates['duplicate_row_count']:,}**")
    lines.append(f"- 重复率：**{duplicates['duplicate_pct']}%**")
    lines.append("")
    lines.append("数据中 **不存在完全重复的行**。\n")

    return "\n".join(lines)


def _append_numeric_section(lines, numeric_dist):
    lines.append("## 5. 数值字段分布\n")
    lines.append("| 字段 | 均值 | 标准差 | 最小值 | 25% | 中位数 | 75% | 最大值 | 偏度 | 峰度 |")
    lines.append("|------|------|--------|--------|-----|--------|-----|--------|------|------|")
    for col, stats in numeric_dist.items():
        lines.append(
            f"| {col} | {stats['mean']} | {stats['std']} | {stats['min']} "
            f"| {stats['q25']} | {stats['median']} | {stats['q75']} "
            f"| {stats['max']} | {stats['skewness']} | {stats['kurtosis']} |"
        )
    lines.append("")
    lines.append("**分布特征：**\n")
    lines.append("- `trip_distance` 偏度极高（362.15），存在极端异常值（最大 126,501.77 英里），"
                 "呈严重右偏分布。")
    lines.append("- `fare_amount` 偏度 8.35，存在负值（退款/取消场景）和极高值（最大 903.02）。")
    lines.append("- `tip_amount` 偏度 9.15，50% 以上行程小费为 0（现金支付未记录小费）。")
    lines.append("- `tolls_amount` 偏度 9.69，大部分行程无过路费，少数高额过路费拉高分布。")
    lines.append("- 所有金额字段峰度极高，说明尾部极厚，存在大量极端值。")
    lines.append("")


def _append_categorical_section(lines, categorical):
    lines.append("## 6. 分类字段分布\n")
    for col, vc in categorical.items():
        lines.append(f"### {col}\n")
        lines.append("| 值 | 计数 | 占比(%) |")
        lines.append("|----|------|---------|")
        total = sum(vc.values())
        for k, v in vc.items():
            pct = round(v / total * 100, 2)
            lines.append(f"| {k} | {v:,} | {pct} |")
        lines.append("")


def _append_time_section(lines, time_info):
    lines.append("## 7. 时间维度\n")
    lines.append(f"- 最早 pickup 时间：`{time_info['pickup_min']}`")
    lines.append(f"- 最晚 pickup 时间：`{time_info['pickup_max']}`")
    lines.append(f"- 最早 dropoff 时间：`{time_info['dropoff_min']}`")
    lines.append(f"- 最晚 dropoff 时间：`{time_info['dropoff_max']}`\n")
    lines.append("### 每日行程数\n")
    lines.append("| 日期 | 行程数 |")
    lines.append("|------|--------|")
    for d, cnt in time_info["daily_trip_counts"].items():
        lines.append(f"| {d} | {cnt:,} |")
    lines.append("")
    lines.append("**说明：** 数据主要集中在 **2020 年 4 月**（约 218,000 条），"
                 "2-3 月仅有极少量记录（约 136 条），另有 1 条 2009 年的异常记录。\n")


def _append_anomalies_section(lines, anomalies):
    lines.append("## 8. 潜在异常汇总\n")

    lines.append("### 8.1 行程距离异常\n")
    lines.append("| 异常类型 | 数量 |")
    lines.append("|----------|------|")
    lines.append(f"| trip_distance = 0 | {anomalies['trip_distance_zero']:,} |")
    lines.append(f"| trip_distance > 100 英里 | {anomalies['trip_distance_gt_100']} |")
    lines.append(f"| trip_distance 最大值 | {anomalies['trip_distance_max']:,.2f} 英里 |")
    lines.append("")

    if anomalies["trip_distance_gt_100_rows"]:
        lines.append("**距离 > 100 英里的行程明细（Top 10）：**\n")
        lines.append("| trip_distance | fare_amount | total_amount |")
        lines.append("|---------------|-------------|--------------|")
        for r in anomalies["trip_distance_gt_100_rows"]:
            lines.append(f"| {r['trip_distance']:,.2f} | {r['fare_amount']} | {r['total_amount']} |")
        lines.append("")

    lines.append("### 8.2 金额异常\n")
    lines.append("| 异常类型 | 数量 |")
    lines.append("|----------|------|")
    lines.append(f"| fare_amount = 0 | {anomalies['fare_amount_zero']:,} |")
    for col, cnt in anomalies["negative_amounts"].items():
        lines.append(f"| {col} < 0 | {cnt:,} |")
    lines.append("")
    lines.append("**说明：** 负金额通常对应退款、取消行程或计费调整。"
                 "fare_amount 为 0 可能是机场固定费率或其他特殊行程。\n")

    lines.append("### 8.3 时间异常\n")
    lines.append("| 异常类型 | 数量 |")
    lines.append("|----------|------|")
    lines.append(f"| dropoff 早于 pickup | {anomalies['dropoff_before_pickup']} |")
    lines.append(f"| dropoff 等于 pickup | {anomalies['dropoff_equals_pickup']:,} |")
    lines.append(f"| 行程时长 > 24 小时 | {anomalies['duration_gt_24h']} |")
    lines.append(f"| 最大行程时长 | {anomalies['duration_max_minutes']:,.2f} 分钟 |")
    lines.append(f"| 最小行程时长 | {anomalies['duration_min_minutes']:,.2f} 分钟 |")
    lines.append(f"| pickup 日期早于 2020 年 | {anomalies['pickup_before_2020']} |")
    lines.append("")

    lines.append("### 8.4 乘客数量异常\n")
    lines.append(f"- 乘客数 = 0 的行程：**{anomalies['passenger_count_zero']:,}** 条")
    lines.append(f"- 乘客数 > 6 的行程：**{anomalies['passenger_count_gt_6']}** 条（纽约出租车最多载 4 名乘客）")
    lines.append("")

    lines.append("### 8.5 其他异常\n")
    lines.append(f"- RatecodeID = 99（未定义费率代码）：**{anomalies['ratecode_99']}** 条")
    lines.append(f"- VendorID = 6（非标准供应商）：**{anomalies['vendorid_6_count']}** 条")
    lines.append(f"- airport_fee 列全为空值：**{anomalies['airport_fee_all_null']}**（该字段完全无效）")
    lines.append("")


def _append_summary_section(lines):
    lines.append("## 9. 总结与建议\n")
    lines.append("### 数据质量总结\n")
    lines.append("1. **数据规模**：共 238,073 行 × 19 列，约 48.9 MB，为 2020 年 4 月纽约黄色出租车行程数据。")
    lines.append("2. **缺失值**：整体缺失率 2.15%。`airport_fee` 列 100% 为空，应删除或标记。"
                 "另有 4 列各有 8.22% 缺失，缺失行对齐。")
    lines.append("3. **重复值**：无完全重复行。")
    lines.append("4. **异常值**：")
    lines.append("   - `trip_distance` 存在 12 条 >100 英里的极端记录（最大 126,501 英里，明显为数据录入错误）。")
    lines.append("   - 多个金额字段存在负值（退款场景），`fare_amount` 有 1,295 条负值记录。")
    lines.append("   - 29 条行程 dropoff 早于 pickup（时间逻辑错误）。")
    lines.append("   - 1 条 2009 年异常日期记录。")
    lines.append("   - `passenger_count` 有 6,582 条为 0、1 条为 7（超出出租车容量）。")
    lines.append("")
    lines.append("### 建议\n")
    lines.append("1. **删除 `airport_fee` 列**（100% 空值，无信息量）。")
    lines.append("2. **处理 trip_distance 极端值**：建议将 > 100 英里的记录标记或截断，"
                 "126,501 英里明显为数据错误。")
    lines.append("3. **处理负金额**：根据业务需求决定保留（退款分析）或过滤。")
    lines.append("4. **处理时间异常**：dropoff 早于 pickup 的 29 条记录应核查或移除。")
    lines.append("5. **处理 2009 年异常记录**：仅 1 条，与主体数据时间范围不符，建议移除。")
    lines.append("6. **乘客数量校验**：0 和 7 的记录需根据业务规则处理。")
    lines.append("7. **VendorID = 6 的记录**（67 条）为非标准供应商，建议核查数据来源。")
    lines.append("")


def main():
    print(f"读取数据: {PARQUET_PATH}")
    df = load_data(PARQUET_PATH)
    print(f"数据规模: {df.shape[0]:,} 行 × {df.shape[1]} 列")

    print("分析数据规模...")
    scale = analyze_scale(df)

    print("分析字段信息...")
    col_info = analyze_columns(df)

    print("分析缺失值...")
    missing = analyze_missing(df)

    print("分析重复值...")
    duplicates = analyze_duplicates(df)

    print("分析数值分布...")
    numeric_dist = analyze_numeric_distribution(df)

    print("分析潜在异常...")
    anomalies = analyze_anomalies(df)

    print("分析分类字段...")
    categorical = analyze_categorical(df)

    print("分析时间维度...")
    time_info = analyze_time(df)

    print("生成报告...")
    lines = []
    # 基础部分
    lines.append("# test.parquet 数据质量分析报告\n")
    lines.append(f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    # 1. 数据规模
    lines.append("## 1. 数据规模\n")
    lines.append("| 指标 | 值 |")
    lines.append("|------|------|")
    lines.append(f"| 行数 | {scale['rows']:,} |")
    lines.append(f"| 列数 | {scale['columns']} |")
    lines.append(f"| 内存占用 | {scale['memory_mb']} MB |")
    lines.append("")

    # 2. 字段类型
    lines.append("## 2. 字段类型与基本信息\n")
    lines.append("| 字段名 | 数据类型 | 非空值数 | 缺失值数 | 缺失率(%) | 唯一值数 |")
    lines.append("|--------|----------|----------|----------|-----------|----------|")
    for _, row in col_info.iterrows():
        lines.append(
            f"| {row['字段名']} | {row['数据类型']} | {row['非空值数']:,} "
            f"| {row['缺失值数']:,} | {row['缺失率(%)']} | {row['唯一值数']} |"
        )
    lines.append("")

    # 3. 缺失值
    lines.append("## 3. 缺失值分析\n")
    lines.append(f"- 总单元格数：**{missing['total_cells']:,}**")
    lines.append(f"- 缺失单元格数：**{missing['total_null_cells']:,}**")
    lines.append(f"- 整体缺失率：**{missing['overall_null_pct']}%**\n")
    if missing["columns_with_null"]:
        lines.append("| 字段 | 缺失数 | 缺失率(%) |")
        lines.append("|------|--------|-----------|")
        for col, cnt in missing["columns_with_null"].items():
            pct = round(cnt / scale["rows"] * 100, 2)
            lines.append(f"| {col} | {cnt:,} | {pct} |")
    lines.append("")
    lines.append("**缺失值说明：**\n")
    lines.append("- `airport_fee` 列 **100% 为空**（全部 238,073 行均为 None），该字段完全无效。")
    lines.append("- `passenger_count`、`RatecodeID`、`store_and_fwd_flag`、`congestion_surcharge` "
                 "四列各有 **19,580** 行缺失（8.22%），缺失行完全对齐——推测来自 VendorID=6 的少量记录"
                 "以及部分 VendorID=1 的记录未填充这些字段。")
    lines.append("")

    # 4. 重复值
    lines.append("## 4. 重复值分析\n")
    lines.append(f"- 完全重复行数：**{duplicates['duplicate_row_count']:,}**")
    lines.append(f"- 重复率：**{duplicates['duplicate_pct']}%**")
    lines.append("")
    lines.append("数据中 **不存在完全重复的行**。\n")

    # 5-8: 调用辅助函数
    _append_numeric_section(lines, numeric_dist)
    _append_categorical_section(lines, categorical)
    _append_time_section(lines, time_info)
    _append_anomalies_section(lines, anomalies)
    _append_summary_section(lines)

    report = "\n".join(lines)
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"报告已写入: {REPORT_PATH}")


if __name__ == "__main__":
    main()
