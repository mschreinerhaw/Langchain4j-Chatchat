"""Post-run numerical audit. Never place this evaluator in the agent workspace.

Usage: python verify-native-cline-parquet-report.py BASELINE SUPPLEMENT PROGRAM REPORT OUTPUT
The baseline and supplement are independently computed from the frozen original.
Unrecognized prose is explicitly left for review; no empty audit can pass.
"""
import hashlib
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

baseline_path, supplement_path, program_path, report_path, output_path = map(Path, sys.argv[1:])
base = json.loads(baseline_path.read_text(encoding='utf-8'))
extra = json.loads(supplement_path.read_text(encoding='utf-8'))
checks, errors, variants = [], [], []
fields = set(extra['sourceColumns'])
aliases = {'count': 'count', '计数': 'count', 'mean': 'mean', '均值': 'mean', '平均值': 'mean',
           'std': 'std', '标准差': 'std', 'min': 'min', '最小值': 'min', 'max': 'max', '最大值': 'max',
           'q1': '25%', 'q25': '25%', '25%分位': '25%', '25%': '25%', 'median': '50%', '中位数': '50%',
           '50%': '50%', '50% (中位数)': '50%', 'q3': '75%', 'q75': '75%', '75%': '75%', '75%分位': '75%',
           '偏度': 'skewness', 'skewness': 'skewness', '峰度': 'kurtosis', 'kurtosis': 'kurtosis',
           'iqr 异常数': 'iqrOutliers', 'iqr异常数': 'iqrOutliers', '异常数': 'iqrOutliers',
           'iqr outliers': 'iqrOutliers', 'outliers (iqr)': 'iqrOutliers', '负值数量': 'negatives'}


def clean(value):
    return value.strip().replace('**', '').replace('`', '').strip()


def number(value):
    value = clean(value).replace(',', '').replace('，', '').replace('$', '')
    value = re.sub(r'\s*\([-+]?\d+(?:\.\d+)?%\)$', '', value)
    value = re.sub(r'\s*(?:条|行|个)$', '', value).rstrip('%').strip()
    try:
        return Decimal(value) if re.fullmatch(r'[-+]?\d+(?:\.\d+)?', value) else None
    except InvalidOperation:
        return None


def check(label, printed, expected, integer=False):
    value = number(printed)
    if value is None:
        return
    tolerance = Decimal(0) if integer else Decimal(1).scaleb(value.as_tuple().exponent) / 2 + Decimal('0.000000001')
    passed = abs(value - Decimal(str(expected))) <= tolerance
    item = {'metric': label, 'reported': clean(printed), 'expected': expected}
    (checks if passed else errors).append(item)


def metric(field, name):
    if name in base['numericDescribe'].get(field, {}):
        return base['numericDescribe'][field][name]
    source = {'skewness': 'numericSkewness', 'kurtosis': 'numericKurtosis',
              'iqrOutliers': 'numericIqrOutlierCounts', 'negatives': 'numericNegativeCounts'}.get(name)
    return base.get(source, {}).get(field) if source else None


text = report_path.read_text(encoding='utf-8')
headers, section_field, categorical = [], None, False
for line in text.splitlines():
    heading = clean(line.lstrip('#')) if line.startswith('#') else clean(line).rstrip('：:')
    heading = re.sub(r'^\d+(?:\.\d+)*\s+', '', heading)
    if line.startswith('#'):
        headers, categorical = [], False
        section_field = heading if heading in fields else None
    elif heading in fields and not line.startswith('|'):
        section_field, categorical, headers = heading, False, []
    if not line.lstrip().startswith('|'):
        continue
    row = [clean(v) for v in line.strip().strip('|').split('|')]
    if not row or all(re.fullmatch(r'[-: ]+', v) for v in row):
        continue
    first = row[0]
    if first in ['值', 'Value'] and any(v in row for v in ['计数', '数量', 'Count']):
        headers, categorical = row, True
        continue
    if categorical and section_field in extra['categoricalCounts'] and len(row) >= 2:
        counts = extra['categoricalCounts'][section_field]
        if first.lower() in ['nan', 'none', 'nan/none', 'null']:
            expected = base['nulls'][section_field]
        else:
            expected = counts.get(first)
            if expected is None and number(first) is not None:
                expected = next((v for k, v in counts.items() if number(k) == number(first)), None)
        if expected is not None:
            check(section_field + '.category:' + first, row[1], expected, True)
            if len(row) > 2:
                check(section_field + '.categoryPercent:' + first, row[2], expected / base['rows'] * 100)
            continue
    if len(row) >= 3 and first in fields and row[1] in fields and any('相关' in h or 'correlation' in h.lower() for h in headers):
        check(first + '.correlation:' + row[1], row[2], extra['correlations'][first][row[1]])
        continue
    if first in fields:
        for i, h in enumerate(headers[1:], 1):
            if i >= len(row):
                continue
            key = h.lower()
            if key in ['数据类型', '类型', 'dtype', 'data type']:
                expected = base['pandasDtypes'][first]
                if row[i] == expected:
                    checks.append({'metric': first + '.dtype', 'reported': row[i], 'expected': expected})
                elif first == 'store_and_fwd_flag' and {row[i], expected} <= {'str', 'object', 'string'}:
                    variants.append({'metric': first + '.dtype', 'reported': row[i], 'baselineRendering': expected,
                                     'reason': 'Pandas string rendering differs; original Arrow string field is unchanged'})
                else:
                    errors.append({'metric': first + '.dtype', 'reported': row[i], 'expected': expected})
            if key in ['缺失数', '缺失数量', '缺失值', '缺失值数', '缺失值数量', 'missing', 'missing count']:
                check(first + '.nulls', row[i], base['nulls'][first], True)
            if key in ['非空数', '非空值数量', 'non-null']:
                check(first + '.nonNull', row[i], extra['nonNullCounts'][first], True)
            if key in ['唯一值数', 'unique']:
                check(first + '.unique', row[i], extra['uniqueCounts'][first], True)
            name = aliases.get(key)
            if name == 'count' and any(h.lower() in ['description', 'condition', 'rule', '描述', '条件', '规则'] for h in headers):
                # A predicate count is not the full column's non-null count.
                # Unknown predicates remain for independent semantic review.
                continue
            expected = metric(first, name)
            if expected is not None:
                check(first + '.' + name, row[i], expected, name in ['count', 'iqrOutliers', 'negatives'])
        continue
    if section_field in fields and len(row) == 2 and first.lower() in aliases:
        name = aliases[first.lower()]
        expected = metric(section_field, name)
        if expected is not None:
            check(section_field + '.' + name, row[1], expected, name in ['count', 'iqrOutliers', 'negatives'])
            continue
    quantile = re.match(r'^(\d+(?:\.\d+)?)%', first)
    if quantile and headers:
        q = str(float(quantile[1]) / 100)
        for i, h in enumerate(headers[1:], 1):
            field = re.split(r'[（(]', h)[0].strip()
            if i < len(row) and field in extra['numericQuantiles'] and q in extra['numericQuantiles'][field]:
                check(field + '.quantile:' + q, row[i], extra['numericQuantiles'][field][q])
        continue
    general = {'总行数': ('rows', base['rows']), '行数': ('rows', base['rows']), '记录数': ('rows', base['rows']),
               '记录数（行数）': ('rows', base['rows']), 'Rows': ('rows', base['rows']),
               '总列数': ('columns', base['columns']), '列数': ('columns', base['columns']),
               '字段数': ('columns', base['columns']), '字段数（列数）': ('columns', base['columns']),
               'Columns': ('columns', base['columns']), '完全重复行数': ('duplicateRows', base['duplicateRowsExcludingFirst']),
               '全字段完全重复': ('duplicateRows', base['duplicateRowsExcludingFirst'])}
    if first in general and len(row) > 1:
        label, expected = general[first]
        check(label, row[1], expected, True)
        continue
    headers, categorical = row, False

# Supplementary prose claims with a clear denominator, independently calculated.
for m in re.finditer(r'为\s*1\s*人[（(]([\d.]+)%', clean(text)):
    value = number(m[1])
    tolerance = Decimal(1).scaleb(value.as_tuple().exponent) / 2 + Decimal('0.000000001')
    expected = [extra['passengerOnePercentAll'], extra['passengerOnePercentNonNull']]
    if not any(abs(value - Decimal(str(v))) <= tolerance for v in expected):
        errors.append({'metric': 'passenger_count=1 narrative percent', 'reported': m[1],
                       'expectedForAllRows': expected[0], 'expectedForNonNullRows': expected[1]})

result = {'program': str(program_path), 'report': str(report_path), 'programExists': program_path.exists(),
          'programSha256': hashlib.sha256(program_path.read_bytes()).hexdigest() if program_path.exists() else None,
          'reportSha256': hashlib.sha256(report_path.read_bytes()).hexdigest(),
          'baselineSha256': hashlib.sha256(baseline_path.read_bytes()).hexdigest(),
          'supplementSha256': hashlib.sha256(supplement_path.read_bytes()).hexdigest(),
          'passedChecks': len(checks), 'checks': checks, 'mismatches': errors, 'environmentRenderings': variants,
          'status': 'FAILED' if errors or not program_path.exists() else 'REVIEW_REQUIRED',
          'unrecognizedProseIsNotAutomaticallyVerified': True,
          'executionEvidenceRequiredSeparately': True}
output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: result[k] for k in ['passedChecks', 'mismatches', 'status']}, ensure_ascii=False))
