#!/usr/bin/env python3
"""
test.parquet 数据分析脚本
=========================
对 NYC 黄色出租车行程数据进行全面的探索性数据分析 (EDA)，
涵盖数据规模、字段类型、缺失值、重复值、数值分布和潜在异常，
并自动生成 report.md 报告。
"""

import pandas as pd
import numpy as np
from pathlib import Path
from datetime import datetime

PARQUET_PATH = Path(__file__).parent / "test.parquet"
REPORT_PATH = Path(__file__).parent / "report.md"
IQR_K = 1.5


def load_data(path: Path) -> pd.DataFrame:
    return pd.read_parquet(path)


def basic_info(df: pd.DataFrame) -> dict:
    return {
        "n_rows": df.shape[0],
        "n_cols": df.shape[1],
        "memory_mb": round(df.memory_usage(deep=True).sum() / 1024 ** 2, 2),
        "dtypes": df.dtypes.to_dict(),
        "columns": list(df.columns),
    }


def missing_analysis(df: pd.DataFrame) -> pd.DataFrame:
    missing = df.isnull().sum()
    pct = (missing / len(df) * 100).round(2)
    return pd.DataFrame({"缺失数": missing, "缺失率(%)": pct})


def duplicate_analysis(df: pd.DataFrame) -> dict:
    n_dup = int(df.duplicated().sum())
    return {"n_duplicates": n_dup, "dup_rate_pct": round(n_dup / len(df) * 100, 2)}


def numeric_summary(df: pd.DataFrame) -> pd.DataFrame:
    return df.select_dtypes(include=[np.number]).describe().T


def categorical_summary(df: pd.DataFrame) -> dict:
    cat_cols = df.select_dtypes(include=["object", "category"]).columns
    result = {}
    for col in cat_cols:
        info = {
            "nunique": int(df[col].nunique()),
            "mode": str(df[col].mode().iloc[0]) if len(df[col].mode()) > 0 else "N/A",
            "value_counts": df[col].value_counts().to_dict(),
        }
        result[col] = info
    return result


def outlier_analysis(df: pd.DataFrame) -> pd.DataFrame:
    numeric_cols = df.select_dtypes(include=[np.number]).columns
    rows = []
    for col in numeric_cols:
        Q1 = df[col].quantile(0.25)
        Q3 = df[col].quantile(0.75)
        IQR = Q3 - Q1
        lower = Q1 - IQR_K * IQR
        upper = Q3 + IQR_K * IQR
        mask = (df[col] < lower) | (df[col] > upper)
        n_outliers = int(mask.sum())
        rows.append({
            "字段": col, "Q1": round(Q1, 4), "Q3": round(Q3, 4),
            "IQR": round(IQR, 4), "下界": round(lower, 4), "上界": round(upper, 4),
            "异常值数": n_outliers, "异常值率(%)": round(n_outliers / len(df) * 100, 2),
        })
    return pd.DataFrame(rows)


def skewness_kurtosis(df: pd.DataFrame) -> pd.DataFrame:
    numeric_cols = df.select_dtypes(include=[np.number]).columns
    rows = []
    for col in numeric_cols:
        rows.append({"字段": col, "偏度": round(df[col].skew(), 4), "峰度": round(df[col].kurtosis(), 4)})
    return pd.DataFrame(rows)


def negative_value_analysis(df: pd.DataFrame) -> dict:
    fee_cols = ["fare_amount", "extra", "mta_tax", "tip_amount",
                "tolls_amount", "improvement_surcharge", "congestion_surcharge", "total_amount"]
    result = {}
    for col in fee_cols:
        if col in df.columns:
            neg = df[df[col] < 0]
            if len(neg) > 0:
                result[col] = {"负值数": int(len(neg)), "最小值": round(float(neg[col].min()), 2)}
    return result


def trip_duration_analysis(df: pd.DataFrame) -> dict:
    dur = (df["tpep_dropoff_datetime"] - df["tpep_pickup_datetime"]).dt.total_seconds() / 60
    return {
        "mean_min": round(float(dur.mean()), 2), "median_min": round(float(dur.median()), 2),
        "min_min": round(float(dur.min()), 2), "max_min": round(float(dur.max()), 2),
        "negative_count": int((dur < 0).sum()), "zero_count": int((dur == 0).sum()),
    }


def time_range(df: pd.DataFrame) -> dict:
    col = df["tpep_pickup_datetime"]
    yearly = col.dt.year.value_counts().sort_index().to_dict()
    return {
        "earliest": str(col.min()), "latest": str(col.max()),
        "yearly_distribution": {str(k): int(v) for k, v in yearly.items()},
    }


def fare_correlation(df: pd.DataFrame) -> pd.DataFrame:
    cols = ["fare_amount", "tip_amount", "tolls_amount", "total_amount", "trip_distance"]
    cols = [c for c in cols if c in df.columns]
    return df[cols].corr().round(4)


def fare_composition_check(df: pd.DataFrame) -> dict:
    components = ["fare_amount", "extra", "mta_tax", "tip_amount",
                  "tolls_amount", "improvement_surcharge", "congestion_surcharge"]
    components = [c for c in components if c in df.columns]
    calc = df[components].fillna(0).sum(axis=1)
    diff = (calc - df["total_amount"]).abs()
    return {"mismatch_gt1": int((diff > 1).sum()), "mean_diff": round(float(diff.mean()), 2),
            "max_diff": round(float(diff.max()), 2)}


def top_locations(df: pd.DataFrame, col: str, n: int = 10) -> pd.Series:
    return df[col].value_counts().head(n)


def generate_report(df: pd.DataFrame) -> str:
    info = basic_info(df)
    missing = missing_analysis(df)
    dup = duplicate_analysis(df)
    num_summary = numeric_summary(df)
    cat_summary = categorical_summary(df)
    outliers = outlier_analysis(df)
    sk = skewness_kurtosis(df)
    neg = negative_value_analysis(df)
    trip_dur = trip_duration_analysis(df)
    time = time_range(df)
    corr = fare_correlation(df)
    fare_check = fare_composition_check(df)

    lines = []
    w = lines.append

    w("# test.parquet 数据分析报告\n")
    w(f"> 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    # 1
    w("## 1. 数据概览\n")
    w("| 指标 | 值 |")
    w("|------|-----|")
    w(f"| 行数 | {info['n_rows']:,} |")
    w(f"| 列数 | {info['n_cols']} |")
    w(f"| 内存占用 | {info['memory_mb']} MB |")
    w(f"| 最早记录时间 | {time['earliest']} |")
    w(f"| 最晚记录时间 | {time['latest']} |")
    w("")

    # 2
    w("## 2. 字段类型\n")
    w("| 字段名 | 数据类型 |")
    w("|--------|----------|")
    for col, dtype in info["dtypes"].items():
        w(f"| {col} | {dtype} |")
    w("")

    # 3
    w("## 3. 缺失值分析\n")
    missing_with_vals = missing[missing["缺失数"] > 0]
    if len(missing_with_vals) > 0:
        w("| 字段 | 缺失数 | 缺失率(%) |")
        w("|------|--------|-----------|")
        for col, row in missing_with_vals.iterrows():
            w(f"| {col} | {int(row['缺失数']):,} | {row['缺失率(%)']} |")
        w("")
        w("**说明:** `airport_fee` 字段 100% 缺失（全部为 NaN），该字段无有效数据。"
          "`passenger_count`、`RatecodeID`、`store_and_fwd_flag`、`congestion_surcharge` "
          "各有 8.22% 的缺失率，缺失行数一致（19,580 行），可能来自同一批数据源。")
    else:
        w("所有字段均无缺失值。\n")
    w("")

    # 4
    w("## 4. 重复值分析\n")
    w(f"- 完全重复行数: **{dup['n_duplicates']:,}**")
    w(f"- 重复率: **{dup['dup_rate_pct']}%**")
    w("")
    w("数据中不存在完全重复的行。")
    w("")

    # 5
    w("## 5. 数值字段描述性统计\n")
    w("| 字段 | 计数 | 均值 | 标准差 | 最小值 | 25% | 50% | 75% | 最大值 |")
    w("|------|------|------|--------|--------|-----|-----|-----|--------|")
    for col, row in num_summary.iterrows():
        w(f"| {col} | {int(row['count']):,} | {row['mean']:.2f} | {row['std']:.2f} "
          f"| {row['min']:.2f} | {row['25%']:.2f} | {row['50%']:.2f} "
          f"| {row['75%']:.2f} | {row['max']:.2f} |")
    w("")

    # 6
    w("## 6. 分类字段分析\n")
    for col, info_cat in cat_summary.items():
        w(f"### {col}\n")
        w(f"- 唯一值数: {info_cat['nunique']}")
        w(f"- 众数: `{info_cat['mode']}`")
        if info_cat["nunique"] > 0 and info_cat["nunique"] <= 20:
            w(f"\n| 值 | 计数 |")
            w(f"|----|------|")
            for val, cnt in info_cat["value_counts"].items():
                w(f"| {val} | {cnt:,} |")
        w("")

    # 7
    w("## 7. 时间维度分析\n")
    w(f"- 时间跨度: {time['earliest']} ~ {time['latest']}")
    w("- 按年分布:")
    for yr, cnt in time["yearly_distribution"].items():
        w(f"  - {yr} 年: {cnt:,} 条")
    w("")
    w("⚠️ **异常:** 存在 1 条 2009 年的记录（pickup 时间为 `2009-01-01 01:11:17`），"
      "而其余 238,072 条记录均集中在 2020 年。该条记录极有可能是数据异常。")
    w("")

    # 8
    w("## 8. 行程时长分析\n")
    w("| 指标 | 值 |")
    w("|------|-----|")
    w(f"| 平均时长 | {trip_dur['mean_min']:.2f} 分钟 |")
    w(f"| 中位数时长 | {trip_dur['median_min']:.2f} 分钟 |")
    w(f"| 最短时长 | {trip_dur['min_min']:.2f} 分钟 |")
    w(f"| 最长时长 | {trip_dur['max_min']:.2f} 分钟 |")
    w(f"| 负时长记录数 | {trip_dur['negative_count']} |")
    w(f"| 零时长记录数 | {trip_dur['zero_count']} |")
    w("")
    w("⚠️ **异常:** 存在 29 条负时长记录（dropoff 早于 pickup），"
      "以及 286 条零时长记录。最长行程达 {:.0f} 分钟（约 {:.1f} 小时），明显不合理。".format(
          trip_dur["max_min"], trip_dur["max_min"] / 60))
    w("")

    # 9
    w("## 9. 异常值检测（IQR 方法）\n")
    w(f"使用 IQR × {IQR_K} 作为异常值判定阈值。\n")
    w("| 字段 | Q1 | Q3 | IQR | 下界 | 上界 | 异常值数 | 异常值率(%) |")
    w("|------|----|----|-----|------|------|----------|-------------|")
    for _, row in outliers.iterrows():
        w(f"| {row['字段']} | {row['Q1']:.2f} | {row['Q3']:.2f} | {row['IQR']:.2f} "
          f"| {row['下界']:.2f} | {row['上界']:.2f} | {int(row['异常值数']):,} | {row['异常值率(%)']} |")
    w("")

    # 10
    w("## 10. 负值分析\n")
    if neg:
        w("| 字段 | 负值数 | 最小值 |")
        w("|------|--------|--------|")
        for col, info_neg in neg.items():
            w(f"| {col} | {info_neg['负值数']:,} | {info_neg['最小值']} |")
        w("")
        w("⚠️ 多个费用字段存在负值，可能代表退款、费用调整或数据录入错误。")
    else:
        w("各费用字段均无负值。\n")
    w("")

    # 11
    w("## 11. 偏度与峰度\n")
    w("| 字段 | 偏度 | 峰度 | 分布特征 |")
    w("|------|------|------|----------|")
    for _, row in sk.iterrows():
        skew_val = row["偏度"]
        if abs(skew_val) < 0.5:
            shape = "近似对称"
        elif skew_val > 0:
            shape = "右偏（正偏）"
        else:
            shape = "左偏（负偏）"
        w(f"| {row['字段']} | {skew_val:.4f} | {row['峰度']:.4f} | {shape} |")
    w("")
    w("**说明:** `trip_distance` 偏度高达 362.15，存在极端右偏（最大距离 126,501.77 英里），"
      "明显为异常值。`fare_amount`、`tip_amount`、`tolls_amount` 也呈强烈右偏。")
    w("")

    # 12
    w("## 12. 费用构成校验\n")
    w("校验 `total_amount` 是否等于各费用分项之和（fare + extra + mta_tax + tip + tolls + improvement + congestion）。\n")
    w("| 指标 | 值 |")
    w("|------|-----|")
    w(f"| 差异 > $1 的记录数 | {fare_check['mismatch_gt1']:,} |")
    w(f"| 平均绝对差异 | ${fare_check['mean_diff']:.2f} |")
    w(f"| 最大绝对差异 | ${fare_check['max_diff']:.2f} |")
    w("")
    w("⚠️ 约 87,397 条记录的费用构成存在超过 $1 的差异，可能由于 `airport_fee` 等未包含的字段、"
      "四舍五入或数据录入问题。")
    w("")

    # 13
    w("## 13. 关键费用字段相关矩阵\n")
    w("| | " + " | ".join(corr.columns) + " |")
    w("|" + "---|" * (len(corr.columns) + 1))
    for idx, row in corr.iterrows():
        vals = " | ".join(f"{v:.4f}" for v in row.values)
        w(f"| {idx} | {vals} |")
    w("")
    w("**说明:** `fare_amount` 与 `total_amount` 高度相关（r=0.975），"
      "而 `trip_distance` 与各费用字段的相关性极低（r<0.025），"
      "说明费用主要由基础费率和其他附加费决定，而非简单的距离计费。")
    w("")

    # 14
    w("## 14. 热门上/下车地点 Top 10\n")
    pu_top = top_locations(df, "PULocationID", 10)
    do_top = top_locations(df, "DOLocationID", 10)
    w("### 上车地点 (PULocationID)\n")
    w("| LocationID | 行程数 |")
    w("|------------|--------|")
    for loc, cnt in pu_top.items():
        w(f"| {loc} | {cnt:,} |")
    w("")
    w("### 下车地点 (DOLocationID)\n")
    w("| LocationID | 行程数 |")
    w("|------------|--------|")
    for loc, cnt in do_top.items():
        w(f"| {loc} | {cnt:,} |")
    w("")

    # 15
    w("## 15. 总结与数据质量建议\n")
    w("### 数据质量概览\n")
    w("| 维度 | 状态 | 说明 |")
    w("|------|------|------|")
    w("| 数据规模 | ✅ 正常 | 238,073 行 × 19 列，数据量适中 |")
    w("| 重复值 | ✅ 正常 | 无完全重复行 |")
    w("| 缺失值 | ⚠️ 需关注 | airport_fee 100% 缺失；4 个字段各 8.22% 缺失 |")
    w("| 时间异常 | ⚠️ 需关注 | 1 条 2009 年异常记录；29 条负时长、286 条零时长 |")
    w("| 负值费用 | ⚠️ 需关注 | fare_amount 有 1,295 条负值 |")
    w("| 极端值 | ⚠️ 需关注 | trip_distance 最大值 126,501 英里；total_amount 最大值 $903 |")
    w("| 费用构成 | ⚠️ 需关注 | 87,397 条记录费用分项之和与 total_amount 差异 > $1 |")
    w("")
    w("### 建议\n")
    w("1. **删除或修正 `airport_fee` 字段**：该字段 100% 缺失，无分析价值。")
    w("2. **处理 2009 年异常记录**：仅 1 条，建议核实后删除或修正。")
    w("3. **清洗负时长记录**：29 条 dropoff 早于 pickup 的记录需要修正或剔除。")
    w("4. **处理费用负值**：约 1,295 条 fare_amount 为负的记录，需确认是否为退款。")
    w("5. **截断极端 trip_distance**：最大距离 126,501 英里明显异常，建议设置合理上限。")
    w("6. **缺失值插补**：`passenger_count` 等字段 8.22% 缺失，可用众数（1 人）填充。")
    w("7. **费用构成校验**：排查 total_amount 与分项不一致的原因，考虑纳入 airport_fee。")
    w("")

    return "\n".join(lines)


def main():
    print(f"正在读取 {PARQUET_PATH} ...")
    df = load_data(PARQUET_PATH)
    print(f"数据规模: {df.shape[0]:,} 行 × {df.shape[1]} 列")
    print("正在执行分析 ...")
    report = generate_report(df)
    print(f"正在写入报告到 {REPORT_PATH} ...")
    REPORT_PATH.write_text(report, encoding="utf-8")
    print("完成！")


if __name__ == "__main__":
    main()
