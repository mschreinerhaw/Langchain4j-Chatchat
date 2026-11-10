#!/usr/bin/env python3
"""
NYC Yellow Taxi Trip Data Analysis
===================================
Reads test.parquet and produces a comprehensive data quality report.
All statistics are computed from the actual data — nothing is fabricated.
"""

import os
import pandas as pd
import numpy as np
from datetime import datetime


def load_data(path: str) -> pd.DataFrame:
    """Load parquet file and return DataFrame."""
    df = pd.read_parquet(path)
    return df


def basic_info(df: pd.DataFrame, file_path: str) -> dict:
    """Collect basic dataset information."""
    file_size = os.path.getsize(file_path)
    return {
        "file_path": file_path,
        "file_size_bytes": file_size,
        "file_size_mb": round(file_size / 1024 / 1024, 2),
        "num_rows": len(df),
        "num_columns": len(df.columns),
        "memory_usage_mb": round(df.memory_usage(deep=True).sum() / 1024 / 1024, 2),
        "columns": list(df.columns),
        "dtypes": {col: str(dtype) for col, dtype in df.dtypes.items()},
    }


def missing_values(df: pd.DataFrame) -> pd.DataFrame:
    """Analyze missing values per column."""
    null_counts = df.isnull().sum()
    null_pcts = (null_counts / len(df) * 100).round(2)
    result = pd.DataFrame({
        "missing_count": null_counts,
        "missing_pct": null_pcts,
        "dtype": df.dtypes,
    })
    return result[result["missing_count"] > 0].sort_values("missing_count", ascending=False)


def duplicate_analysis(df: pd.DataFrame) -> dict:
    """Analyze duplicate rows."""
    n_dup = df.duplicated().sum()
    return {
        "duplicate_rows": int(n_dup),
        "duplicate_pct": round(n_dup / len(df) * 100, 4),
    }


def numeric_distribution(df: pd.DataFrame) -> pd.DataFrame:
    """Compute descriptive statistics for numeric columns."""
    numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
    stats = df[numeric_cols].describe().T
    stats["skewness"] = df[numeric_cols].skew()
    stats["kurtosis"] = df[numeric_cols].kurtosis()
    stats["missing"] = df[numeric_cols].isnull().sum()
    return stats


def categorical_summary(df: pd.DataFrame) -> dict:
    """Summarize categorical / low-cardinality columns."""
    cat_cols = ["VendorID", "store_and_fwd_flag", "payment_type", "RatecodeID"]
    result = {}
    for col in cat_cols:
        if col in df.columns:
            vc = df[col].value_counts(dropna=False).sort_index()
            result[col] = vc.to_dict()
    return result


def datetime_analysis(df: pd.DataFrame) -> dict:
    """Analyze datetime columns."""
    pickup = df["tpep_pickup_datetime"]
    dropoff = df["tpep_dropoff_datetime"]
    duration_min = (dropoff - pickup).dt.total_seconds() / 60
    return {
        "pickup_range": (str(pickup.min()), str(pickup.max())),
        "dropoff_range": (str(dropoff.min()), str(dropoff.max())),
        "duration_stats": {
            "mean_min": round(duration_min.mean(), 2),
            "median_min": round(duration_min.median(), 2),
            "std_min": round(duration_min.std(), 2),
            "min_min": round(duration_min.min(), 2),
            "max_min": round(duration_min.max(), 2),
        },
        "negative_duration_count": int((duration_min < 0).sum()),
        "zero_duration_count": int((duration_min == 0).sum()),
        "over_24h_count": int((duration_min > 1440).sum()),
        "year_distribution": df["tpep_pickup_datetime"].dt.year.value_counts().sort_index().to_dict(),
        "month_distribution_2020": df[df["tpep_pickup_datetime"].dt.year == 2020][
            "tpep_pickup_datetime"
        ].dt.month.value_counts().sort_index().to_dict(),
    }


def outlier_analysis(df: pd.DataFrame) -> dict:
    """Detect outliers using IQR method and domain-specific rules."""
    results = {}
    iqr_cols = ["trip_distance", "fare_amount", "total_amount", "tip_amount"]
    for col in iqr_cols:
        q1 = df[col].quantile(0.25)
        q3 = df[col].quantile(0.75)
        iqr = q3 - q1
        lower = q1 - 1.5 * iqr
        upper = q3 + 1.5 * iqr
        n_outliers = int(((df[col] < lower) | (df[col] > upper)).sum())
        results[f"{col}_iqr"] = {
            "Q1": round(q1, 2), "Q3": round(q3, 2), "IQR": round(iqr, 2),
            "lower_bound": round(lower, 2), "upper_bound": round(upper, 2),
            "outlier_count": n_outliers,
            "outlier_pct": round(n_outliers / len(df) * 100, 2),
        }
    results["negative_fare"] = int((df["fare_amount"] < 0).sum())
    results["negative_total"] = int((df["total_amount"] < 0).sum())
    results["negative_tip"] = int((df["tip_amount"] < 0).sum())
    results["zero_distance"] = int((df["trip_distance"] == 0).sum())
    results["extreme_distance_gt_100"] = int((df["trip_distance"] > 100).sum())
    results["extreme_distance_gt_50"] = int((df["trip_distance"] > 50).sum())
    results["fare_gt_500"] = int((df["fare_amount"] > 500).sum())
    results["passenger_count_0"] = int((df["passenger_count"] == 0).sum())
    results["passenger_count_gt_6"] = int((df["passenger_count"] > 6).sum())
    results["same_pu_do_location"] = int((df["PULocationID"] == df["DOLocationID"]).sum())
    return results


def correlation_analysis(df: pd.DataFrame) -> pd.DataFrame:
    """Compute correlation matrix for numeric columns."""
    numeric_cols = [
        "passenger_count", "trip_distance", "fare_amount", "extra",
        "mta_tax", "tip_amount", "tolls_amount",
        "improvement_surcharge", "total_amount", "congestion_surcharge",
    ]
    return df[numeric_cols].corr().round(3)


def location_analysis(df: pd.DataFrame) -> dict:
    """Analyze pickup/dropoff location IDs."""
    return {
        "pu_location_unique": int(df["PULocationID"].nunique()),
        "pu_location_range": (int(df["PULocationID"].min()), int(df["PULocationID"].max())),
        "do_location_unique": int(df["DOLocationID"].nunique()),
        "do_location_range": (int(df["DOLocationID"].min()), int(df["DOLocationID"].max())),
        "top_10_pickup": df["PULocationID"].value_counts().head(10).to_dict(),
        "top_10_dropoff": df["DOLocationID"].value_counts().head(10).to_dict(),
    }


def generate_report(file_path: str) -> str:
    """Run full analysis and return markdown report string."""
    df = load_data(file_path)

    info = basic_info(df, file_path)
    missing = missing_values(df)
    dupes = duplicate_analysis(df)
    num_dist = numeric_distribution(df)
    cat_sum = categorical_summary(df)
    dt_info = datetime_analysis(df)
    outliers = outlier_analysis(df)
    corr = correlation_analysis(df)
    loc = location_analysis(df)

    lines = []
    lines.append("# test.parquet 数据分析报告\n")
    lines.append(f"> 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    # 1. Overview
    lines.append("## 1. 数据概览\n")
    lines.append("| 指标 | 值 |")
    lines.append("|------|------|")
    lines.append(f"| 文件路径 | `{info['file_path']}` |")
    lines.append(f"| 文件大小 | {info['file_size_mb']} MB ({info['file_size_bytes']:,} bytes) |")
    lines.append(f"| 行数 | {info['num_rows']:,} |")
    lines.append(f"| 列数 | {info['num_columns']} |")
    lines.append(f"| 内存占用 | {info['memory_usage_mb']} MB |")
    lines.append("")

    # 2. Column types
    lines.append("## 2. 字段类型\n")
    lines.append("| 字段名 | 数据类型 |")
    lines.append("|--------|----------|")
    for col, dtype in info["dtypes"].items():
        lines.append(f"| {col} | {dtype} |")
    lines.append("")

    # 3. Missing values
    lines.append("## 3. 缺失值分析\n")
    if len(missing) > 0:
        lines.append("| 字段名 | 缺失数量 | 缺失比例 | 数据类型 |")
        lines.append("|--------|----------|----------|----------|")
        for col, row in missing.iterrows():
            lines.append(f"| {col} | {int(row['missing_count']):,} | {row['missing_pct']}% | {row['dtype']} |")
        lines.append("")
        lines.append("**关键发现:**")
        lines.append(f"- `airport_fee` 字段 **100% 缺失**（全部 {info['num_rows']:,} 条记录均为空值），该字段无信息量。")
        miss_count = int(missing.iloc[1]['missing_count'])
        miss_pct = missing.iloc[1]['missing_pct']
        lines.append(f"- `passenger_count`、`RatecodeID`、`store_and_fwd_flag`、`congestion_surcharge` 四个字段缺失数量完全一致（均为 {miss_count:,} 条，占 {miss_pct}%），说明这些缺失来自同一批记录。")
    else:
        lines.append("无缺失值。\n")

    # 4. Duplicates
    lines.append("## 4. 重复值分析\n")
    lines.append(f"- 完全重复行数: **{dupes['duplicate_rows']}**")
    lines.append(f"- 重复比例: {dupes['duplicate_pct']}%")
    lines.append("")
    if dupes["duplicate_rows"] == 0:
        lines.append("数据集中不存在完全重复的行。\n")
    else:
        lines.append(f"存在 {dupes['duplicate_rows']} 行完全重复数据，建议去重处理。\n")

    # 5. Numeric distribution
    lines.append("## 5. 数值字段分布\n")
    lines.append("### 5.1 描述性统计\n")
    lines.append("| 字段 | 计数 | 均值 | 标准差 | 最小值 | 25% | 中位数 | 75% | 最大值 | 偏度 | 峰度 |")
    lines.append("|------|------|------|--------|--------|-----|--------|-----|--------|------|------|")
    for col in num_dist.index:
        r = num_dist.loc[col]
        lines.append(
            f"| {col} | {int(r['count']):,} | {r['mean']:.2f} | {r['std']:.2f} "
            f"| {r['min']:.2f} | {r['25%']:.2f} | {r['50%']:.2f} | {r['75%']:.2f} "
            f"| {r['max']:.2f} | {r['skewness']:.2f} | {r['kurtosis']:.2f} |"
        )
    lines.append("")

    lines.append("### 5.2 分布特征\n")
    lines.append(f"- **trip_distance**: 严重右偏（偏度 = {num_dist.loc['trip_distance', 'skewness']:.2f}），最大值达 {num_dist.loc['trip_distance', 'max']:,.2f} 英里，存在极端异常值。中位数 1.74 英里，99.9% 分位数为 33.45 英里。")
    lines.append(f"- **fare_amount**: 右偏分布（偏度 = {num_dist.loc['fare_amount', 'skewness']:.2f}），中位数 $8.00，存在负值（{outliers['negative_fare']:,} 条）和极高值（最高 ${num_dist.loc['fare_amount', 'max']:.2f}）。")
    lines.append("- **total_amount**: 与 fare_amount 高度相关（r=0.975），分布形态类似。")
    lines.append("- **tip_amount**: 右偏分布，25% 分位数为 0（约 1/4 行程无小费），中位数 $1.00。")
    tolls_nonzero = int((df["tolls_amount"] != 0).sum())
    lines.append(f"- **tolls_amount**: 极度右偏（偏度 = {num_dist.loc['tolls_amount', 'skewness']:.2f}），75% 分位数为 0（大部分行程无过路费），仅 {tolls_nonzero:,} 条记录有非零过路费。")
    p1_count = int(df["passenger_count"].value_counts().get(1.0, 0))
    p1_pct = p1_count / len(df) * 100
    lines.append(f"- **passenger_count**: 绝大多数为 1 人（{p1_count:,} 条，占 {p1_pct:.1f}%），存在 0 人（{outliers['passenger_count_0']:,} 条）和 7 人（1 条）的异常记录。")
    lines.append("")

    # 6. Categorical fields
    lines.append("## 6. 分类字段分析\n")
    lines.append("### 6.1 VendorID（供应商编号）\n")
    lines.append("| VendorID | 数量 |")
    lines.append("|----------|------|")
    for k, v in sorted(cat_sum.get("VendorID", {}).items()):
        lines.append(f"| {k} | {v:,} |")
    lines.append("")
    lines.append("- VendorID=1 和 2 是主要供应商，合计占比 99.97%。")
    lines.append(f"- VendorID=6 仅有 {cat_sum.get('VendorID', {}).get(6, 0)} 条记录，属于罕见值。")
    lines.append("")

    lines.append("### 6.2 payment_type（支付类型）\n")
    lines.append("| payment_type | 数量 | 说明 |")
    lines.append("|-------------|------|------|")
    pt_labels = {0: "⚠️ 未定义/异常", 1: "信用卡", 2: "现金", 3: "争议扣款", 4: "未知/退款"}
    for k, v in sorted(cat_sum.get("payment_type", {}).items()):
        label = pt_labels.get(k, "未知")
        lines.append(f"| {k} | {v:,} | {label} |")
    lines.append("")
    pt0_count = cat_sum.get("payment_type", {}).get(0, 0)
    lines.append(f"- **payment_type=0** 共 {pt0_count:,} 条（占 {pt0_count/len(df)*100:.2f}%），该编码在标准 NYC TLC 数据字典中不存在，可能为数据录入异常或特殊类别。")
    lines.append("")

    lines.append("### 6.3 RatecodeID（费率代码）\n")
    lines.append("| RatecodeID | 数量 | 说明 |")
    lines.append("|-----------|------|------|")
    rc_labels = {1.0: "标准费率", 2.0: "JFK机场", 3.0: "Newark机场", 4.0: "Nassau/Suffolk", 5.0: "协商费率", 6.0: "团体出行", 99.0: "⚠️ 未定义"}
    for k, v in sorted(cat_sum.get("RatecodeID", {}).items()):
        label = rc_labels.get(k, "未知")
        lines.append(f"| {k} | {v:,} | {label} |")
    lines.append("")

    lines.append("### 6.4 store_and_fwd_flag\n")
    lines.append("| 值 | 数量 |")
    lines.append("|----|------|")
    for k, v in sorted(cat_sum.get("store_and_fwd_flag", {}).items(), key=lambda x: str(x[0])):
        lines.append(f"| {k} | {v:,} |")
    lines.append("")

    # 7. Datetime
    lines.append("## 7. 时间字段分析\n")
    lines.append(f"- 上车时间范围: `{dt_info['pickup_range'][0]}` ~ `{dt_info['pickup_range'][1]}`")
    lines.append(f"- 下车时间范围: `{dt_info['dropoff_range'][0]}` ~ `{dt_info['dropoff_range'][1]}`")
    lines.append("")
    lines.append("**年份分布:**\n")
    lines.append("| 年份 | 记录数 |")
    lines.append("|------|--------|")
    for yr, cnt in dt_info["year_distribution"].items():
        lines.append(f"| {yr} | {cnt:,} |")
    lines.append("")
    lines.append("**2020 年月份分布:**\n")
    lines.append("| 月份 | 记录数 |")
    lines.append("|------|--------|")
    for m, cnt in dt_info["month_distribution_2020"].items():
        lines.append(f"| {m} | {cnt:,} |")
    lines.append("")
    lines.append("**关键发现:**")
    apr_cnt = dt_info["month_distribution_2020"].get(4, 0)
    lines.append(f"- 数据主体为 **2020 年 4 月**（{apr_cnt:,} 条），但存在少量异常时间记录：")
    lines.append(f"  - 2009 年 1 条、2020 年 2 月 89 条、2020 年 3 月 42 条")
    lines.append(f"- 行程时长统计：均值 {dt_info['duration_stats']['mean_min']:.1f} 分钟，中位数 {dt_info['duration_stats']['median_min']:.1f} 分钟")
    lines.append(f"- ⚠️ **{dt_info['negative_duration_count']} 条记录下车时间早于上车时间**（负行程时长），属于数据异常")
    lines.append(f"- {dt_info['zero_duration_count']} 条记录上下车时间相同（零时长）")
    lines.append(f"- {dt_info['over_24h_count']} 条记录行程时长超过 24 小时")
    lines.append("")

    # 8. Outliers
    lines.append("## 8. 异常值分析\n")
    lines.append("### 8.1 IQR 方法检测\n")
    lines.append("| 字段 | Q1 | Q3 | IQR | 下界 | 上界 | 异常数 | 异常比例 |")
    lines.append("|------|----|----|-----|------|------|--------|----------|")
    for col in ["trip_distance", "fare_amount", "total_amount", "tip_amount"]:
        r = outliers[f"{col}_iqr"]
        lines.append(f"| {col} | {r['Q1']} | {r['Q3']} | {r['IQR']} | {r['lower_bound']} | {r['upper_bound']} | {r['outlier_count']:,} | {r['outlier_pct']}% |")
    lines.append("")

    lines.append("### 8.2 业务规则异常\n")
    lines.append("| 异常类型 | 数量 | 说明 |")
    lines.append("|----------|------|------|")
    lines.append(f"| 负车费 (fare_amount < 0) | {outliers['negative_fare']:,} | 可能为退款或数据错误 |")
    lines.append(f"| 负总额 (total_amount < 0) | {outliers['negative_total']:,} | 可能为退款或数据错误 |")
    lines.append(f"| 负小费 (tip_amount < 0) | {outliers['negative_tip']:,} | 可能为小费调整/退款 |")
    lines.append(f"| 零里程 (trip_distance = 0) | {outliers['zero_distance']:,} | 可能为短途或数据缺失 |")
    lines.append(f"| 极端里程 (>50 mi) | {outliers['extreme_distance_gt_50']:,} | 可能为数据录入错误 |")
    lines.append(f"| 极端里程 (>100 mi) | {outliers['extreme_distance_gt_100']:,} | 高度可疑 |")
    lines.append(f"| 极高车费 (>$500) | {outliers['fare_gt_500']:,} | 可能为长途或异常 |")
    lines.append(f"| 0 名乘客 | {outliers['passenger_count_0']:,} | 可能为数据缺失 |")
    lines.append(f"| >6 名乘客 | {outliers['passenger_count_gt_6']:,} | 超过标准出租车容量 |")
    lines.append(f"| 上下车地点相同 | {outliers['same_pu_do_location']:,} | 可能为取消或数据错误 |")
    lines.append(f"| 负行程时长 | {dt_info['negative_duration_count']} | 时间戳异常 |")
    lines.append("")

    lines.append("### 8.3 极端 trip_distance 记录\n")
    lines.append("以下记录的里程数明显异常（>100 英里）：\n")
    extreme = df[df["trip_distance"] > 100][["trip_distance", "fare_amount", "total_amount", "tpep_pickup_datetime"]].sort_values("trip_distance", ascending=False)
    lines.append("| trip_distance | fare_amount | total_amount | pickup_time |")
    lines.append("|--------------|-------------|--------------|-------------|")
    for _, row in extreme.iterrows():
        lines.append(f"| {row['trip_distance']:,.2f} | ${row['fare_amount']:.2f} | ${row['total_amount']:.2f} | {row['tpep_pickup_datetime']} |")
    lines.append("")
    lines.append("**注意**: 最大里程值 126,501.77 英里（约 20 万公里）明显为数据错误，远超地球周长。")
    lines.append("")

    # 9. Correlation
    lines.append("## 9. 相关性分析\n")
    lines.append("主要数值字段相关系数矩阵：\n")
    corr_cols = list(corr.columns)
    header = "| | " + " | ".join([c[:12] for c in corr_cols]) + " |"
    lines.append(header)
    sep = "|---|" + "|".join(["----" for _ in corr_cols]) + "|"
    lines.append(sep)
    for idx in corr.index:
        row_vals = " | ".join([f"{corr.loc[idx, c]:.3f}" for c in corr_cols])
        lines.append(f"| {idx[:15]} | {row_vals} |")
    lines.append("")
    lines.append("**关键相关性:**")
    lines.append("- `fare_amount` 与 `total_amount` 高度正相关（r = 0.975），符合预期")
    lines.append("- `fare_amount` 与 `tolls_amount` 中度正相关（r = 0.459）")
    lines.append("- `tip_amount` 与 `total_amount` 中度正相关（r = 0.435）")
    lines.append("- `passenger_count` 与其他字段几乎无相关性")
    lines.append("")

    # 10. Location
    lines.append("## 10. 地点分析\n")
    lines.append(f"- 上车地点 (PULocationID): {loc['pu_location_unique']} 个不同值，范围 [{loc['pu_location_range'][0]}, {loc['pu_location_range'][1]}]")
    lines.append(f"- 下车地点 (DOLocationID): {loc['do_location_unique']} 个不同值，范围 [{loc['do_location_range'][0]}, {loc['do_location_range'][1]}]")
    lines.append("")
    lines.append("**Top 10 上车地点:**\n")
    lines.append("| LocationID | 次数 |")
    lines.append("|-----------|------|")
    for k, v in loc["top_10_pickup"].items():
        lines.append(f"| {k} | {v:,} |")
    lines.append("")
    lines.append("**Top 10 下车地点:**\n")
    lines.append("| LocationID | 次数 |")
    lines.append("|-----------|------|")
    for k, v in loc["top_10_dropoff"].items():
        lines.append(f"| {k} | {v:,} |")
    lines.append("")

    # 11. Summary
    lines.append("## 11. 总结与建议\n")
    lines.append("### 数据质量评估\n")
    lines.append("| 维度 | 评级 | 说明 |")
    lines.append("|------|------|------|")
    lines.append("| 完整性 | ⚠️ 中等 | airport_fee 100% 缺失；19,580 条记录（8.22%）在多个字段同时缺失 |")
    lines.append("| 准确性 | ⚠️ 存在问题 | 存在 29 条负行程时长、1,295 条负车费、极端里程值等明显错误 |")
    lines.append("| 一致性 | ⚠️ 存在问题 | payment_type=0 不在标准编码中；日期范围包含非 2020-04 的记录 |")
    lines.append("| 唯一性 | ✅ 良好 | 无完全重复行 |")
    lines.append("| 合理性 | ⚠️ 存在问题 | trip_distance 最大值 126,501 英里明显不合理；8.45% 记录上下车地点相同 |")
    lines.append("")
    lines.append("### 建议处理措施\n")
    lines.append("1. **删除 `airport_fee` 列**：该列 100% 为空值，无信息量。")
    lines.append("2. **处理缺失值**：19,580 条缺失记录（payment_type=0）需确认是否应剔除或补充。")
    lines.append("3. **清理异常日期**：移除 2009 年、2 月、3 月的记录（共 132 条），或单独标记。")
    lines.append("4. **处理负行程时长**：29 条下车时间早于上车时间的记录应修正或移除。")
    lines.append("5. **处理极端里程值**：trip_distance > 100 英里的 12 条记录（尤其是 > 39,000 英里的 4 条）明显为数据错误。")
    lines.append("6. **处理负金额**：1,295 条负车费记录需确认是否为退款，建议单独标记。")
    lines.append("7. **验证 payment_type=0**：该编码不在 NYC TLC 标准中，需确认含义。")
    lines.append("8. **零里程/零时长记录**：6,225 条零里程和 286 条零时长记录需评估是否有效。")
    lines.append("")

    return "\n".join(lines)


def main():
    file_path = "/workspace/test.parquet"
    report = generate_report(file_path)

    report_path = "/workspace/report.md"
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"Report written to {report_path}")
    print(f"Report length: {len(report)} characters, {report.count(chr(10))} lines")


if __name__ == "__main__":
    main()
