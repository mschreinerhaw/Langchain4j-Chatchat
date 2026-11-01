# 仓库地理分布：无法按要求回答（缺少完整仓库维表与字段级定义）

## 结论先行

**无法给出“仓库分布在哪些国家和省州、各多少仓库、覆盖多少城市”的分布结论。** 原因有两点，均属证据缺失，而非计算失败：

1. **交付快照只包含 1 条分组记录**，不是完整的仓库维表。数据集级声明称该来源为“全仓库维表，无参数……按国家和省州统计仓库及覆盖城市数量，返回每组实际仓库主键范围”，但本次交付的 `rowCount = 1`，只覆盖单一分组。若据此汇报“只有 1 个国家/1 个省州/5 个仓库”，就是把被截断的样本当作全量人口，属于不可接受的外推。
2. **没有字段级定义**：`inputVersions[].fieldDefinitions` 为空对象，未给出任何字段的名称含义、单位或键语义。按证据边界，只能报告源字段的结构与字面值，不能把任何字段绑定为“仓库数量”“覆盖城市数”或“国家/省州”并输出业务汇总。

因此本报告为 **INPUT_REQUIRED**：给出可验证的结构事实，并明确所需的补充材料。

## 一、本次交付快照的结构与字面值（可验证）

- 逻辑行数：**1**（与 manifest 的 `rowCount = 1` 一致）
- 传输层为 Parquet 部件 1 个（`part-00000.parquet`），已按 manifest 声明校验 SHA-256 与行数；存储列 `c0 → datasetReference`、`c1 → record`（JSON 解码，Decimal 保留精度）。
- `datasetReference` 取值唯一：。
- 唯一记录的字段字面值如下（**原样引用，未赋予业务含义**）：

| 交付记录序号 | 国家字段字面值 (w_country) | 省州字段字面值 (w_state) | 仓库数字段 (warehouse_count) | 城市数字段 (city_count) | 仓库主键最小值 (min_warehouse_sk) | 仓库主键最大值 (max_warehouse_sk) |
|---|---|---|---|---|---|---|
| 1 | United States | TN | 5 | 1 | 1 | 5 |

结构画像（1 条记录内）：

| 字段 (源字段名) | 出现行数 | null | 缺失 | 观测存储类型 | 非空不同值数 | 数值最小/最大 |
|---|---|---|---|---|---|---|
| city_count | 1/1 | 0 | 0 | int | 1 | 1 / 1 |
| max_warehouse_sk | 1/1 | 0 | 0 | int | 1 | 5 / 5 |
| min_warehouse_sk | 1/1 | 0 | 0 | int | 1 | 1 / 1 |
| w_country | 1/1 | 0 | 0 | str | 1 | — |
| w_state | 1/1 | 0 | 0 | str | 1 | — |
| warehouse_count | 1/1 | 0 | 0 | int | 1 | 5 / 5 |

## 二、可以做的快照内结构核算（不是业务分布结论）

- 交付分组行数：**1**；`w_country` 字面值不同数：**1**；`w_state` 字面值不同数：**1**；`(w_country, w_state)` 组合数：**1**。
- 快照内 `warehouse_count` 数值字段求和 = **5**，`city_count` 数值字段求和 = **1**。这两个数字只对“已交付的这 1 行”成立，**不能**表述为“公司共有 5 个仓库”或“覆盖 1 个城市”。
- 主键范围自洽性检查：唯一交付分组 United States/TN：主键范围宽度 5（1–5），warehouse_count = 5，两者一致（与连续主键假设相容，但未交付主键明细，无法逐一验证）

## 三、为什么不能把上表读成地理分布

- 一个分组无法构成分布：国家/省州的计数需要完整的分组集合，缺失的分组既不能补 0，也不能假定不存在。
- `w_country` / `w_state` 的字段名与取值（如 `United States`、`TN`）在拼写上像国家与省州，但**拼写不构成身份声明**；字段级定义缺失时，它们只能是“国家类字段/省州类字段”的候选，不能据此命名业务维度。
- `min_warehouse_sk` / `max_warehouse_sk` 是主键范围端点，属标识符语义，**不得**作为金额或被求和；`warehouse_count` 与 `city_count` 的计量单位（个数？SKU 计数？是否存在重复主键去重口径）未被声明。
- 跨省州对 `city_count` 求和可能重复计算同名城市，本次只有 1 行也无法验证该口径。

## 四、需要的补充输入（任一即可推进）

1. 该模板返回的**完整结果集**（`$.data.data.rows` 全部行及 `rowCount`），使分组覆盖全量仓库；或
2. 仓库维表的**行级明细**（至少包含仓库主键、国家、省州、城市字段），由本次分析自行分组去重；以及
3. 国家/省州/城市字段与仓库计数的**字段级定义**（含义、单位、主键口径，例如 `warehouse_count` 是否等于该组内 `warehouse_sk` 去重计数）。

## 五、字段来源与确认状态（fieldProvenance 摘要）

所有字段的 `declarationSource` 均为：`inputVersions[].fieldDefinitions` 为空（无字段级定义）；仅有 `sourceDefinition.description` 的数据集级用途描述，未建立字段级映射。因此 6 个源字段的含义状态全部为 **UNCONFIRMED**，按其原始名称字面引用，不参与业务汇总。

## 六、方法与审计说明

- 读取与校验：manifest SHA-256、schema SHA-256、部件 SHA-256 与行数均在程序内校验通过；JSON 单元格用 `json.loads(parse_float=Decimal)` 解码，缺失与 null 分别保留（本次无缺失、无 null）。
- 未做任何填充、插补、去重或口径修补；未生成图表（字段含义未确认，业务图表不被允许）。
- 校验产物：`/workspace/output/validation/diagnostics.json`。
- 供分析的来源引用：。

## 查询结果明细

已找到 1 行数据，下面展示前 1 行；完整结构化数据已随结果返回用于表格展示。

| w_country | w_state | warehouse_count | city_count | min_warehouse_sk | max_warehouse_sk |
| --- | --- | --- | --- | --- | --- |
| United States | TN | 5 | 1 | 1 | 5 |