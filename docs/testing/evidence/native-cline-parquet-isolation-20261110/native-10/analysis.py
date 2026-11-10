#!/usr/bin/env python3
"""
Comprehensive analysis of test.parquet file
Analyzes: data size, field types, missing values, duplicates,
numerical distributions, and potential anomalies
"""

import pandas as pd
import numpy as np
from datetime import datetime


def analyze_parquet(file_path: str) -> dict:
    """Perform comprehensive analysis on parquet file"""
    df = pd.read_parquet(file_path)

    results = {
        'file_path': file_path,
        'analysis_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
        'basic_info': {},
        'field_types': {},
        'missing_values': {},
        'duplicates': {},
        'numerical_stats': {},
        'categorical_stats': {},
        'anomalies': []
    }

    # 1. Basic Information
    results['basic_info'] = {
        'total_rows': len(df),
        'total_columns': len(df.columns),
        'memory_usage_mb': df.memory_usage(deep=True).sum() / 1024**2,
        'columns': list(df.columns)
    }

    # 2. Field Types Analysis
    for col in df.columns:
        results['field_types'][col] = {
            'dtype': str(df[col].dtype),
            'unique_values': int(df[col].nunique()),
            'sample_values': df[col].dropna().head(3).tolist()
        }

    # 3. Missing Values Analysis
    missing_counts = df.isnull().sum()
    missing_pct = (missing_counts / len(df)) * 100

    results['missing_values'] = {
        'columns_with_missing': int((missing_counts > 0).sum()),
        'total_missing_cells': int(missing_counts.sum()),
        'missing_percentage': float((missing_counts.sum() / (len(df) * len(df.columns))) * 100),
        'details': {}
    }

    for col in df.columns:
        if missing_counts[col] > 0:
            results['missing_values']['details'][col] = {
                'count': int(missing_counts[col]),
                'percentage': float(missing_pct[col])
            }

    # 4. Duplicates Analysis
    duplicate_rows = df.duplicated().sum()
    results['duplicates'] = {
        'duplicate_row_count': int(duplicate_rows),
        'duplicate_percentage': float((duplicate_rows / len(df)) * 100)
    }

    key_cols = ['VendorID', 'tpep_pickup_datetime', 'passenger_count',
                'trip_distance', 'fare_amount']
    existing_key_cols = [c for c in key_cols if c in df.columns]
    if existing_key_cols:
        key_dups = df.duplicated(subset=existing_key_cols).sum()
        results['duplicates']['key_column_duplicates'] = int(key_dups)
        results['duplicates']['key_columns_checked'] = existing_key_cols

    return results, df


def analyze_numerical(df, results):
    """Analyze numerical columns"""
    numeric_cols = df.select_dtypes(include=[np.number]).columns

    for col in numeric_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue

        Q1 = col_data.quantile(0.25)
        Q3 = col_data.quantile(0.75)
        IQR = Q3 - Q1
        lower_bound = Q1 - 1.5 * IQR
        upper_bound = Q3 + 1.5 * IQR
        outliers = int(((col_data < lower_bound) | (col_data > upper_bound)).sum())

        stats = {
            'count': int(len(col_data)),
            'mean': float(col_data.mean()),
            'std': float(col_data.std()),
            'min': float(col_data.min()),
            '25%': float(Q1),
            '50%': float(col_data.quantile(0.50)),
            '75%': float(Q3),
            'max': float(col_data.max()),
            'zeros': int((col_data == 0).sum()),
            'negatives': int((col_data < 0).sum()),
            'skewness': float(col_data.skew()),
            'kurtosis': float(col_data.kurtosis()),
            'outliers_iqr': outliers,
            'outlier_percentage': float((outliers / len(col_data)) * 100)
        }
        results['numerical_stats'][col] = stats

        if stats['negatives'] > 0:
            results['anomalies'].append({
                'type': 'negative_values',
                'column': col,
                'count': stats['negatives'],
                'percentage': float((stats['negatives'] / stats['count']) * 100)
            })

        if stats['outlier_percentage'] > 5:
            results['anomalies'].append({
                'type': 'high_outlier_count',
                'column': col,
                'count': stats['outliers_iqr'],
                'percentage': stats['outlier_percentage']
            })


def analyze_categorical(df, results):
    """Analyze categorical and timestamp columns"""
    cat_cols = df.select_dtypes(include=['object', 'category']).columns
    for col in cat_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        vc = col_data.value_counts()
        results['categorical_stats'][col] = {
            'unique_values': int(col_data.nunique()),
            'most_common': vc.head(5).to_dict(),
            'least_common': vc.tail(5).to_dict()
        }

    ts_cols = df.select_dtypes(include=['datetime64']).columns
    for col in ts_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        results['categorical_stats'][col] = {
            'min_date': str(col_data.min()),
            'max_date': str(col_data.max()),
            'date_range_days': (col_data.max() - col_data.min()).days
        }


def analyze_business_logic(df, results):
    """Check business logic anomalies"""
    if 'tpep_pickup_datetime' in df.columns and 'tpep_dropoff_datetime' in df.columns:
        dur = (df['tpep_dropoff_datetime'] - df['tpep_pickup_datetime']).dt.total_seconds() / 60
        neg = int((dur < 0).sum())
        short = int((dur < 1).sum())
        long_ = int((dur > 1440).sum())
        if neg > 0:
            results['anomalies'].append({
                'type': 'negative_trip_duration', 'count': neg,
                'description': 'Trips where dropoff is before pickup'
            })
        if short > 0:
            results['anomalies'].append({
                'type': 'very_short_trips', 'count': short,
                'description': 'Trips less than 1 minute'
            })
        if long_ > 0:
            results['anomalies'].append({
                'type': 'very_long_trips', 'count': long_,
                'description': 'Trips more than 24 hours'
            })

    if 'passenger_count' in df.columns:
        zero_pax = int((df['passenger_count'] == 0).sum())
        high_pax = int((df['passenger_count'] > 6).sum())
        if zero_pax > 0:
            results['anomalies'].append({
                'type': 'zero_passengers', 'count': zero_pax,
                'description': 'Trips with zero passengers'
            })
        if high_pax > 0:
            results['anomalies'].append({
                'type': 'unusually_high_passengers', 'count': high_pax,
                'description': 'Trips with more than 6 passengers'
            })


def generate_report(results, output_path):
    """Generate markdown report from analysis results"""
    r = []
    r.append("# Parquet Data Analysis Report\n")
    r.append(f"**Analysis Time:** {results['analysis_time']}  ")
    r.append(f"**File Path:** {results['file_path']}\n")

    # 1. Basic Info
    r.append("\n## 1. Basic Information\n")
    r.append(f"- **Total Rows:** {results['basic_info']['total_rows']:,}")
    r.append(f"- **Total Columns:** {results['basic_info']['total_columns']}")
    r.append(f"- **Memory Usage:** {results['basic_info']['memory_usage_mb']:.2f} MB\n")
    r.append("### Columns\n")
    for i, col in enumerate(results['basic_info']['columns'], 1):
        r.append(f"{i}. `{col}`")
    r.append("")

    # 2. Field Types
    r.append("\n## 2. Field Types Analysis\n")
    r.append("| Column | Data Type | Unique Values | Sample Values |")
    r.append("|--------|-----------|---------------|---------------|")
    for col, info in results['field_types'].items():
        samples = ', '.join([str(v) for v in info['sample_values'][:3]])
        r.append(f"| {col} | {info['dtype']} | {info['unique_values']:,} | {samples} |")
    r.append("")

    # 3. Missing Values
    r.append("\n## 3. Missing Values Analysis\n")
    mv = results['missing_values']
    r.append(f"- **Columns with Missing Values:** {mv['columns_with_missing']}")
    r.append(f"- **Total Missing Cells:** {mv['total_missing_cells']:,}")
    r.append(f"- **Overall Missing Percentage:** {mv['missing_percentage']:.4f}%\n")
    if mv['details']:
        r.append("### Missing Values by Column\n")
        r.append("| Column | Missing Count | Missing Percentage |")
        r.append("|--------|---------------|-------------------|")
        for col, d in mv['details'].items():
            r.append(f"| {col} | {d['count']:,} | {d['percentage']:.4f}% |")
        r.append("")

    # 4. Duplicates
    r.append("\n## 4. Duplicates Analysis\n")
    d = results['duplicates']
    r.append(f"- **Duplicate Rows:** {d['duplicate_row_count']:,}")
    r.append(f"- **Duplicate Percentage:** {d['duplicate_percentage']:.4f}%")
    if 'key_column_duplicates' in d:
        r.append(f"- **Key Column Duplicates:** {d['key_column_duplicates']:,}")
        r.append(f"- **Key Columns Checked:** {', '.join(d['key_columns_checked'])}")
    r.append("")

    return r


def generate_numerical_section(results, r):
    """Append numerical statistics section"""
    r.append("\n## 5. Numerical Columns Statistics\n")
    for col, s in results['numerical_stats'].items():
        r.append(f"\n### {col}\n")
        r.append("| Statistic | Value |")
        r.append("|-----------|-------|")
        r.append(f"| Count | {s['count']:,} |")
        r.append(f"| Mean | {s['mean']:.4f} |")
        r.append(f"| Std Dev | {s['std']:.4f} |")
        r.append(f"| Min | {s['min']:.4f} |")
        r.append(f"| 25th Percentile | {s['25%']:.4f} |")
        r.append(f"| Median (50%) | {s['50%']:.4f} |")
        r.append(f"| 75th Percentile | {s['75%']:.4f} |")
        r.append(f"| Max | {s['max']:.4f} |")
        r.append(f"| Zeros | {s['zeros']:,} |")
        r.append(f"| Negatives | {s['negatives']:,} |")
        r.append(f"| Skewness | {s['skewness']:.4f} |")
        r.append(f"| Kurtosis | {s['kurtosis']:.4f} |")
        r.append(f"| Outliers (IQR) | {s['outliers_iqr']:,} ({s['outlier_percentage']:.2f}%) |")
        r.append("")


def generate_categorical_section(results, r):
    """Append categorical and timestamp section"""
    if results['categorical_stats']:
        r.append("\n## 6. Categorical & Timestamp Columns\n")
        for col, s in results['categorical_stats'].items():
            r.append(f"\n### {col}\n")
            if 'unique_values' in s:
                r.append(f"**Unique Values:** {s['unique_values']}\n")
                r.append("**Most Common Values:**\n")
                for val, cnt in s['most_common'].items():
                    r.append(f"- `{val}`: {cnt:,}")
                r.append("")
            elif 'min_date' in s:
                r.append(f"- **Min Date:** {s['min_date']}")
                r.append(f"- **Max Date:** {s['max_date']}")
                r.append(f"- **Date Range:** {s['date_range_days']} days\n")


def generate_anomalies_section(results, r):
    """Append anomalies and summary section"""
    r.append("\n## 7. Potential Anomalies\n")
    anomalies = results['anomalies']
    if anomalies:
        r.append(f"**Total Anomalies Detected:** {len(anomalies)}\n")
        for i, a in enumerate(anomalies, 1):
            r.append(f"### Anomaly {i}: {a['type']}\n")
            r.append(f"- **Column:** {a.get('column', 'N/A')}")
            r.append(f"- **Count:** {a['count']:,}")
            if 'percentage' in a:
                r.append(f"- **Percentage:** {a['percentage']:.4f}%")
            if 'description' in a:
                r.append(f"- **Description:** {a['description']}")
            r.append("")
    else:
        r.append("No significant anomalies detected.\n")

    r.append("\n## 8. Summary & Recommendations\n")
    r.append("### Key Findings\n")
    mv = results['missing_values']
    dup = results['duplicates']
    if mv['missing_percentage'] > 0:
        r.append(f"- Data has {mv['missing_percentage']:.4f}% missing values across {mv['columns_with_missing']} column(s)")
    if dup['duplicate_percentage'] > 0:
        r.append(f"- Found {dup['duplicate_row_count']:,} duplicate rows ({dup['duplicate_percentage']:.4f}%)")
    if anomalies:
        r.append(f"- Detected {len(anomalies)} potential anomalies requiring investigation")
    high_skew = [c for c, s in results['numerical_stats'].items() if abs(s['skewness']) > 2]
    if high_skew:
        r.append(f"- {len(high_skew)} column(s) show high skewness: {', '.join(high_skew)}")

    r.append("\n### Recommendations\n")
    if mv['missing_percentage'] > 0:
        r.append("- Consider imputation or removal of missing values based on business requirements")
    if dup['duplicate_percentage'] > 0:
        r.append("- Remove or investigate duplicate rows to ensure data integrity")
    if anomalies:
        r.append("- Investigate detected anomalies to determine if they are data errors or valid edge cases")
        r.append("- Consider data validation rules to prevent future anomalies")
    if high_skew:
        r.append("- Apply appropriate transformations (log, sqrt) for highly skewed numerical features")
        r.append("- Consider robust statistical methods that are less sensitive to outliers")


def write_report(r, output_path):
    """Write the report to disk"""
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(r))
    print(f"Report generated: {output_path}")


if __name__ == '__main__':
    print("Starting parquet analysis...")
    results, df = analyze_parquet('/workspace/test.parquet')
    analyze_numerical(df, results)
    analyze_categorical(df, results)
    analyze_business_logic(df, results)

    r = generate_report(results, '/workspace/report.md')
    generate_numerical_section(results, r)
    generate_categorical_section(results, r)
    generate_anomalies_section(results, r)
    write_report(r, '/workspace/report.md')

    print("Analysis complete!")
    print(f"\nQuick Summary:")
    print(f"- Rows: {results['basic_info']['total_rows']:,}")
    print(f"- Columns: {results['basic_info']['total_columns']}")
    print(f"- Missing values: {results['missing_values']['total_missing_cells']:,}")
    print(f"- Duplicates: {results['duplicates']['duplicate_row_count']:,}")
    print(f"- Anomalies detected: {len(results['anomalies'])}")

def analyze_categorical(df, results):
    """Analyze categorical and timestamp columns"""
    cat_cols = df.select_dtypes(include=['object', 'category']).columns
    for col in cat_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        vc = col_data.value_counts()
        results['categorical_stats'][col] = {
            'unique_values': int(col_data.nunique()),
            'most_common': vc.head(5).to_dict(),
            'least_common': vc.tail(5).to_dict()
        }

    ts_cols = df.select_dtypes(include=['datetime64']).columns
    for col in ts_cols:
        col_data = df[col].dropna()
        if len(col_data) == 0:
            continue
        results['categorical_stats'][col] = {
            'min_date': str(col_data.min()),
            'max_date': str(col_data.max()),
            'date_range_days': (col_data.max() - col_data.min()).days
        }


def analyze_business_logic(df, results):
    """Check business logic anomalies"""
    if 'tpep_pickup_datetime' in df.columns and 'tpep_dropoff_datetime' in df.columns:
        duration_min = (df['tpep_dropoff_datetime'] - df['tpep_pickup_datetime']).dt.total_seconds() / 60
        neg = int((duration_min < 0).sum())
        short = int((duration_min < 1).sum())
        long_ = int((duration_min > 1440).sum())

        if neg > 0:
            results['anomalies'].append({
                'type': 'negative_trip_duration',
                'count': neg,
                'description': 'Trips where dropoff is before pickup'
            })
        if short > 0:
            results['anomalies'].append({
                'type': 'very_short_trips',
                'count': short,
                'description': 'Trips less than 1 minute'
            })
        if long_ > 0:
            results['anomalies'].append({
                'type': 'very_long_trips',
                'count': long_,
                'description': 'Trips more than 24 hours'
            })

    if 'passenger_count' in df.columns:
        zero_pax = int((df['passenger_count'] == 0).sum())
        high_pax = int((df['passenger_count'] > 6).sum())
        if zero_pax > 0:
            results['anomalies'].append({
                'type': 'zero_passengers',
                'count': zero_pax,
                'description': 'Trips with zero passengers'
            })
        if high_pax > 0:
            results['anomalies'].append({
                'type': 'unusually_high_passengers',
                'count': high_pax,
                'description': 'Trips with more than 6 passengers'
            })

