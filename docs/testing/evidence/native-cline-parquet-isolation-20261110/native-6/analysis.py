#!/usr/bin/env python3
"""
Parquet 文件分析脚本
分析 test.parquet 文件的数据规模、字段类型、缺失值、重复值、数值分布和潜在异常
"""

import pandas as pd
import numpy as np
import json
from pathlib import Path
from datetime import datetime


def analyze_parquet(file_path: str) -> dict:
    """分析 parquet 文件并返回详细报告"""
    print(f"正在加载文件: {file_path}")
    df = pd.read_parquet(file_path)

    report = {
        "file_info": {
            "file_path": file_path,
            "file_size_mb": Path(file_path).stat().st_size / (1024 * 1024),
            "analysis_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        },
        "data_overview": {},
        "field_types": {},
        "missing_values": {},
        "duplicates": {},
        "numerical_stats": {},
        "categorical_stats": {},
        "anomalies": []
    }

    # 1. 数据规模
    report["data_overview"] = {
        "rows": len(df),
        "columns": len(df.columns),
        "memory_usage_mb": df.memory_usage(deep=True).sum() / (1024 * 1024),
        "column_names": df.columns.tolist()
    }

    # 2. 字段类型
    for col in df.columns:
        dtype = str(df[col].dtype)
        null_count = df[col].isnull().sum()
        null_pct = (null_count / len(df)) * 100 if len(df) > 0 else 0
        report["field_types"][col] = {
            "dtype": dtype,
            "null_count": int(null_count),
            "null_percentage": round(null_pct, 2)
        }

    # 3. 缺失值分析
    missing_cols = df.columns[df.isnull().any()].tolist()
    report["missing_values"] = {
        "columns_with_missing": len(missing_cols),
        "total_missing_cells": int(df.isnull().sum().sum()),
        "missing_percentage": round((df.isnull().sum().sum() / (len(df) * len(df.columns))) * 100, 2),
        "details": {}
    }
    for col in missing_cols:
        null_count = df[col].isnull().sum()
        null_pct = (null_count / len(df)) * 100
        report["missing_values"]["details"][col] = {
            "missing_count": int(null_count),
            "missing_percentage": round(null_pct, 2)
        }

    # 4. 重复值分析
    duplicate_rows = df.duplicated().sum()
    report["duplicates"] = {
        "duplicate_row_count": int(duplicate_rows),
        "duplicate_percentage": round((duplicate_rows / len(df)) * 100, 2) if len(df) > 0 else 0
    }
    for col in df.columns:
        unique_count = df[col].nunique()
        report["field_types"][col]["unique_values"] = int(unique_count)
        report["field_types"][col]["unique_percentage"] = round((unique_count / len(df)) * 100, 2) if len(df) > 0 else 0

    # 5. 数值型字段分析
    numeric_cols = df.select_dtypes(include=[np.number]).columns
    for col in numeric_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        stats = {
            "count": int(len(col_data)),
            "mean": float(col_data.mean()),
            "std": float(col_data.std()),
            "min": float(col_data.min()),
            "max": float(col_data.max()),
            "median": float(col_data.median()),
            "q1": float(col_data.quantile(0.25)),
            "q3": float(col_data.quantile(0.75)),
            "skewness": float(col_data.skew()),
            "kurtosis": float(col_data.kurtosis())
        }
        q1, q3 = stats["q1"], stats["q3"]
        iqr = q3 - q1
        lower_bound = q1 - 1.5 * iqr
        upper_bound = q3 + 1.5 * iqr
        outliers = col_data[(col_data < lower_bound) | (col_data > upper_bound)]
        outlier_pct = (len(outliers) / len(col_data)) * 100 if len(col_data) > 0 else 0
        stats["outlier_count"] = int(len(outliers))
        stats["outlier_percentage"] = round(outlier_pct, 2)
        stats["lower_bound"] = float(lower_bound)
        stats["upper_bound"] = float(upper_bound)
        report["numerical_stats"][col] = stats
        if outlier_pct > 5:
            report["anomalies"].append({
                "type": "high_outlier_ratio",
                "column": col,
                "outlier_percentage": round(outlier_pct, 2),
                "outlier_count": int(len(outliers)),
                "description": f"列 '{col}' 有 {len(outliers)} 个异常值 ({round(outlier_pct, 2)}%)"
            })

    # 6. 分类型字段分析
    categorical_cols = df.select_dtypes(include=['object', 'category']).columns
    for col in categorical_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        value_counts = col_data.value_counts()
        top_values = value_counts.head(10)
        stats = {
            "count": int(len(col_data)),
            "unique_values": int(col_data.nunique()),
            "most_common": {
                "value": str(top_values.index[0]) if len(top_values) > 0 else None,
                "count": int(top_values.iloc[0]) if len(top_values) > 0 else 0
            },
            "top_10_values": [
                {"value": str(idx), "count": int(cnt)}
                for idx, cnt in top_values.items()
            ]
        }
        if len(top_values) > 0:
            dominant_pct = (top_values.iloc[0] / len(col_data)) * 100
            if dominant_pct > 90:
                report["anomalies"].append({
                    "type": "dominant_category",
                    "column": col,
                    "dominant_value": str(top_values.index[0]),
                    "dominant_percentage": round(dominant_pct, 2),
                    "description": f"列 '{col}' 中 '{top_values.index[0]}' 占比过高 ({round(dominant_pct, 2)}%)"
                })
        report["categorical_stats"][col] = stats

    # 7. 检查常量列
    for col in df.columns:
        if df[col].nunique() == 1:
            report["anomalies"].append({
                "type": "constant_column",
                "column": col,
                "value": str(df[col].iloc[0]),
                "description": f"列 '{col}' 只有一个唯一值: {df[col].iloc[0]}"
            })

    # 8. 检查完全相同的列
    for i, col1 in enumerate(df.columns):
        for col2 in df.columns[i+1:]:
            if df[col1].equals(df[col2]):
                report["anomalies"].append({
                    "type": "duplicate_columns",
                    "columns": [col1, col2],
                    "description": f"列 '{col1}' 和 '{col2}' 完全相同"
                })

    return report


def generate_markdown_report(report: dict, output_path: str):
    """生成 Markdown 格式的分析报告"""
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write("# Parquet 文件数据分析报告\n\n")
        f.write(f"**分析时间**: {report['file_info']['analysis_time']}\n\n")

        # 文件信息
        f.write("## 1. 文件基本信息\n\n")
        f.write(f"- **文件路径**: `{report['file_info']['file_path']}`\n")
        f.write(f"- **文件大小**: {report['file_info']['file_size_mb']:.2f} MB\n\n")

        # 数据规模
        f.write("## 2. 数据规模\n\n")
        overview = report['data_overview']
        f.write(f"- **行数**: {overview['rows']:,}\n")
        f.write(f"- **列数**: {overview['columns']}\n")
        f.write(f"- **内存占用**: {overview['memory_usage_mb']:.2f} MB\n")
        f.write(f"- **列名**: {', '.join(overview['column_names'])}\n\n")

        # 字段类型
        f.write("## 3. 字段类型与缺失值\n\n")
        f.write("| 列名 | 数据类型 | 缺失值数量 | 缺失率 | 唯一值数量 | 唯一值率 |\n")
        f.write("|------|----------|------------|--------|------------|----------|\n")
        for col, info in report['field_types'].items():
            f.write(f"| {col} | {info['dtype']} | {info['null_count']:,} | {info['null_percentage']}% | {info['unique_values']:,} | {info['unique_percentage']}% |\n")
        f.write("\n")

        # 缺失值汇总
        f.write("## 4. 缺失值分析\n\n")
        missing = report['missing_values']
        f.write(f"- **有缺失值的列数**: {missing['columns_with_missing']}\n")
        f.write(f"- **总缺失单元格数**: {missing['total_missing_cells']:,}\n")
        f.write(f"- **整体缺失率**: {missing['missing_percentage']}%\n\n")
        if missing['details']:
            f.write("### 缺失值详情\n\n")
            f.write("| 列名 | 缺失数量 | 缺失率 |\n")
            f.write("|------|----------|--------|\n")
            for col, detail in missing['details'].items():
                f.write(f"| {col} | {detail['missing_count']:,} | {detail['missing_percentage']}% |\n")
            f.write("\n")

        # 重复值
        f.write("## 5. 重复值分析\n\n")
        dup = report['duplicates']
        f.write(f"- **重复行数**: {dup['duplicate_row_count']:,}\n")
        f.write(f"- **重复率**: {dup['duplicate_percentage']}%\n\n")

        # 数值型字段统计
        if report['numerical_stats']:
            f.write("## 6. 数值型字段统计\n\n")
            f.write("| 列名 | 计数 | 均值 | 标准差 | 最小值 | Q1 | 中位数 | Q3 | 最大值 | 偏度 | 峰度 | 异常值数 | 异常值率 |\n")
            f.write("|------|------|------|--------|--------|----|----|----|----|----|----|----|----|\n")
            for col, stats in report['numerical_stats'].items():
                f.write(f"| {col} | {stats['count']:,} | {stats['mean']:.2f} | {stats['std']:.2f} | {stats['min']:.2f} | {stats['q1']:.2f} | {stats['median']:.2f} | {stats['q3']:.2f} | {stats['max']:.2f} | {stats['skewness']:.2f} | {stats['kurtosis']:.2f} | {stats['outlier_count']:,} | {stats['outlier_percentage']}% |\n")
            f.write("\n")

        # 分类型字段统计
        if report['categorical_stats']:
            f.write("## 7. 分类型字段统计\n\n")
            for col, stats in report['categorical_stats'].items():
                f.write(f"### {col}\n\n")
                f.write(f"- **非空计数**: {stats['count']:,}\n")
                f.write(f"- **唯一值数量**: {stats['unique_values']:,}\n")
                f.write(f"- **最常见值**: {stats['most_common']['value']} (出现 {stats['most_common']['count']:,} 次)\n\n")
                f.write("**Top 10 值分布**:\n\n")
                f.write("| 值 | 计数 |\n")
                f.write("|----|----|\n")
                for item in stats['top_10_values']:
                    f.write(f"| {item['value']} | {item['count']:,} |\n")
                f.write("\n")

        # 潜在异常
        f.write("## 8. 潜在异常与问题\n\n")
        if report['anomalies']:
            for i, anomaly in enumerate(report['anomalies'], 1):
                f.write(f"{i}. **{anomaly['type']}**: {anomaly['description']}\n")
            f.write("\n")
        else:
            f.write("未发现明显的异常问题。\n\n")

        # 建议
        f.write("## 9. 数据质量建议\n\n")
        suggestions = []
        if missing['missing_percentage'] > 0:
            suggestions.append(f"- 存在 {missing['missing_percentage']}% 的缺失值，建议根据业务场景选择填充策略（均值/中位数/众数填充）或删除缺失记录")
        if dup['duplicate_percentage'] > 0:
            suggestions.append(f"- 存在 {dup['duplicate_percentage']}% 的重复行，建议检查数据来源并去重")
        high_outlier_cols = [a['column'] for a in report['anomalies'] if a['type'] == 'high_outlier_ratio']
        if high_outlier_cols:
            suggestions.append(f"- 以下列存在较高比例的异常值，建议进一步调查: {', '.join(high_outlier_cols)}")
        constant_cols = [a['column'] for a in report['anomalies'] if a['type'] == 'constant_column']
        if constant_cols:
            suggestions.append(f"- 以下列为常量列，可考虑删除: {', '.join(constant_cols)}")
        if suggestions:
            f.write('\n'.join(suggestions) + '\n\n')
        else:
            f.write("数据质量良好，无需特别处理。\n\n")

        f.write("---\n")
        f.write("*本报告由 analysis.py 自动生成*\n")
    print(f"Markdown 报告已生成: {output_path}")


def main():
    file_path = "/workspace/test.parquet"
    report_path = "/workspace/report.md"
    report = analyze_parquet(file_path)
    generate_markdown_report(report, report_path)
    json_path = "/workspace/analysis_report.json"
    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"JSON 报告已生成: {json_path}")
    print("\n分析完成！")


if __name__ == "__main__":
    main()
