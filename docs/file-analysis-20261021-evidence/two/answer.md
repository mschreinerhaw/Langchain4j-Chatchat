# Excel 数据质量分析报告

## 概述

共收到 **2** 份 Excel 文件，均为合成验收数据，不涉及业务金额单位。使用 Python `Decimal` 进行精确计算。

## 文件 1：file-analysis-acceptance-two-1.xlsx

- 数据集引用：`user-asset:6a9f9c90e7f016f48adcb771795ec1c6f5d1cc4c692498a2ff8317e5a7556ede:336360c735218bb3dffe7f98a08bb0f4368691eefc7c8faea84c2ad160bc799a`
- SHA-256：`336360c735218bb3dffe7f98a08bb0f4368691eefc7c8faea84c2ad160bc799a`
- 大小：4914 字节
- 表头：['id', 'amount']
- 记录数：4
- amount 缺失数：1
- amount 非数值数：1
- amount 负数数：1
- 有效数值个数（含负数）：2
- 有效数值合计（Decimal）：`5`
- 重复 id：id=2（出现2次）

## 文件 2：file-analysis-acceptance-two-2.xlsx

- 数据集引用：`user-asset:15f6beca59c5c11348a16755c972cbb464de516e28505296262ac8d18389c589:8443d6b86cc903381b38ce18e4db314f3d6ad8f59486e02dd725595ba9df6880`
- SHA-256：`8443d6b86cc903381b38ce18e4db314f3d6ad8f59486e02dd725595ba9df6880`
- 大小：4898 字节
- 表头：['id', 'amount']
- 记录数：3
- amount 缺失数：0
- amount 非数值数：0
- amount 负数数：0
- 有效数值个数（含负数）：3
- 有效数值合计（Decimal）：`82`
- 重复 id：无

## 两份文件按 id 比较差异

- 共同 id 数：2
- 仅在文件 1 中出现的 id：2
- 仅在文件 2 中出现的 id：3

### 相同 id 但 amount 不同的记录

| id | 文件1 amount | 文件2 amount |
|---|---|---|
| 1 | 10 | 12 |
| 4 | invalid | 40 |

## 方法说明

- 所有数值合计使用 Python `Decimal` 精确计算，避免浮点误差。
- 有效数值包括正数、零和负数；负数单独计数但仍纳入合计。
- 缺失值包括空单元格、空字符串及行中缺失该列。
- 非数值指无法解析为 Decimal 的字符串（如文本）。
- 文件 1 的有效数值合计由 Python 实际计算得出（非人工估算）。