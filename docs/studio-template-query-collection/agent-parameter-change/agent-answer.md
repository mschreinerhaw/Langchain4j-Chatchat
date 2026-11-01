## 结论

在**最低面积原值 ≥ 294,242** 的筛选条件下，本次查询返回的仓库明细共 **3 个仓库**，其**面积原值合计为 1,893,263**（原值直接相加，未换算、未推断单位）。

| 指标 | 数值 | 计算口径 |
|---|---|---|
| 筛选后仓库数量 | 3 | 返回明细中 `w_warehouse_sk` 去重计数（明细无重复主键） |
| 面积原值合计 | 1,893,263 | Σ `w_warehouse_sq_ft`，逐行明细精确相加（Decimal） |

说明：本次仅对**筛选后返回的 3 行明细**求和，未使用、也未获得全仓库汇总，因此该合计**不能**代表全仓库面积总量；它只代表阈值 ≥ 294,242 的这 3 个仓库。

## 符合条件的仓库明细

| 仓库主键 w_warehouse_sk | 仓库名称 w_warehouse_name | 国家 w_country | 州 w_state | 面积原值 w_warehouse_sq_ft |
|---|---|---|---|---|
| 1 | Conventional childr | United States | TN | 977,787 |
| 3 | Doors canno | United States | TN | 294,242 |
| 4 | Bad cards must make. | United States | TN | 621,234 |

明细内部校验：
- 全部 3 行 `w_warehouse_sq_ft` ≥ 294,242，与声明的谓词 `WHERE w_warehouse_sq_ft >=?`（`?` ← min_area = 294,242，来源 user_query）一致，无越界行。
- 最小值恰为 294,242（主键 3），处于阈值边界（含等于），与 `>=` 语义一致。
- 按 `w_warehouse_sk` 升序返回，与声明的 `ORDER BY w_warehouse_sk` 一致；主键无重复。
- 逐行相加结果与上报合计一致（1,893,263 = 1,893,263）。

## 字段与口径溯源

数据来自模板 `STUDIO_271B4D6FA9283DBE9AA526D49F0BFFF7` 的工具，`sourceDefinition.queryDefinition` 为实际执行的 SQL 声明：

```sql
SELECT
    w_warehouse_sk,
    w_warehouse_name,
    w_country,
    w_state,
    w_warehouse_sq_ft
FROM tpcds.warehouse
WHERE w_warehouse_sq_ft >=?
ORDER BY w_warehouse_sk
```

生效参数：`min_area = 294,242`，`parameterSources.min_area = user_query`，`scopeStatus = USER_PARAMETERS_VERIFIED`，无被丢弃/拒绝的覆盖项与未映射约束 → **查询范围与用户请求的阈值一致**。

| 数据集 datasetReference | 源字段 | 声明含义 | 声明来源 | 状态 |
|---|---|---|---|---|
|  | w_warehouse_sk | 仓库主键（SELECT 列，描述声明为“返回主键”） | sourceDefinition.description + sourceDefinition.queryDefinition | CONFIRMED |
|  | w_warehouse_name | 仓库名称（描述声明为“名称”） | sourceDefinition.description + sourceDefinition.queryDefinition | CONFIRMED |
|  | w_country | 仓库所在国家（描述声明为“地区”的一部分） | sourceDefinition.description + sourceDefinition.queryDefinition | CONFIRMED |
|  | w_state | 仓库所在州（描述声明为“地区”的一部分） | sourceDefinition.description + sourceDefinition.queryDefinition | CONFIRMED |
|  | w_warehouse_sq_ft | 面积原值：即 WHERE 谓词所作用、绑定 min_area=294242 的面积字段；单位未声明 | sourceDefinition.queryDefinition WHERE 子句 + description（“按最低面积原值阈值筛选…不推断单位”） | CONFIRMED |

（`inputVersions.fieldDefinitions` 为空对象；上述含义依据 `sourceDefinition` 中的生产者描述与实际执行的 SQL 声明确定，属同一生产者声明层级。）

## 限制

- **面积单位未确认**：数据与声明均未给出 `w_warehouse_sq_ft` 的单位，本报告只给“面积原值”，不做任何单位换算或单位推断。
- **不存在全仓库基准**：交付数据仅含阈值筛选后的 3 行，未交付未筛选的全仓库行，故无法给出全仓库数量/面积，也未进行“筛选占比”类计算。
- 数据为单次查询快照（`resultComplete = true`，返回 3 行），不代表其他时间点或其他阈值下的仓库情况。
- 未做阈值敏感性、趋势或统计推断：这些均超出“仅基于返回明细统计数量与面积原值合计”的请求范围。

## 查询结果明细

已找到 3 行数据，下面展示前 3 行；完整结构化数据已随结果返回用于表格展示。

| w_warehouse_sk | w_warehouse_name | w_country | w_state | w_warehouse_sq_ft |
| --- | --- | --- | --- | --- |
| 1 | Conventional childr | United States | TN | 977787 |
| 3 | Doors canno | United States | TN | 294242 |
| 4 | Bad cards must make. | United States | TN | 621234 |