# 仓库国家/省州分布（交付快照）

## 结论
本次交付的仓库数据集中在 **1 个国家/省州分组：United States / TN**，合计 **5 个仓库**，覆盖 **1 个城市**。
也就是说，在当前数据范围内不存在跨国家或跨省州的分布差异——柱状图只有一根柱子，无法给出“哪个国家/地区仓库更多”的排名结论。这是数据范围本身的结果，而不是计算失败。

## 核心指标
| 指标 | 数值 | 单位 | 计算方式 |
| --- | --- | --- | --- |
| 仓库总数 | 5 | 个 | 对查询返回的各国家/省州分组的 warehouse_count（COUNT(*)）求和 |
| 国家/省州分组数 | 1 | 个 | 交付结果按 w_country、w_state 分组后的行数 |
| 涉及国家数 | 1 | 个 | 分组 w_country 取值去重计数 |
| 覆盖城市数 | 1 | 个 | 对分组 city_count（COUNT(DISTINCT w_city)）求和；当前仅 1 组，不存在跨组重复计数 |
| 仓库主键范围 | 1–5 |  | 各分组 min_warehouse_sk 的最小值与 max_warehouse_sk 的最大值 |

## 分布结果
![仓库数量柱状图](warehouse_country_state_bar.png)

数据明细（全部交付分组）：

| 国家 | 省州 | 仓库数量（个） | 覆盖城市数（个） | 仓库主键最小值 | 仓库主键最大值 |
| --- | --- | --- | --- | --- | --- |
| United States | TN | 5 | 1 | 1 | 5 |

## 城市覆盖与数据范围
- **城市覆盖**：1 个城市。该数字来自源查询的“分组内不同城市数”指标（COUNT(DISTINCT w_city)）之和；当前只有 1 个分组，因此不存在跨分组重复计数问题。源查询未返回城市名称清单，**无法列出具体城市名称**。
- **地理范围**：仅 United States / TN 一个分组，1 个国家。
- **仓库范围**：共 5 条仓库记录，主键取值落在 1–5，跨度 5 个值，与仓库记录数一致。
- **时间范围**：交付数据中**没有任何日期或时间字段**，因此无法界定“当前”对应的业务时点或统计期间；只能表述为一次交付的仓库维表快照。
- **完整性**：交付声明该查询为 warehouse 维表按国家/省州全量分组结果（无过滤参数、结果完整），因此可以认为除上述分组外没有其它国家/省州分组被遗漏。

## 口径与字段来源
| 源字段 | 业务含义 | 声明来源 | 状态 |
| --- | --- | --- | --- |
| w_country | 仓库所在国家；源查询按该字段分组，作为分布横轴的第一层维度 | 数据集 sourceDefinition.description（按国家和省州统计仓库）与 queryDefinition 的 GROUP BY w_country | CONFIRMED |
| w_state | 仓库所在省/州；源查询按该字段分组，作为分布横轴的第二层维度 | sourceDefinition.description 与 queryDefinition 的 GROUP BY w_state | CONFIRMED |
| warehouse_count | 分组内仓库记录数（仓库数量）；公式 COUNT(*) | queryDefinition 显式别名 warehouse_count 与 description（统计仓库数量） | CONFIRMED |
| city_count | 分组内不同城市数量；公式 COUNT(DISTINCT w_city) | queryDefinition 显式别名 city_count 与 description（覆盖城市数量） | CONFIRMED |
| min_warehouse_sk | 分组内仓库主键（w_warehouse_sk）最小值；属于标识符，不作金额或数量求和 | queryDefinition 显式别名 min_warehouse_sk 与 description（仓库主键范围） | CONFIRMED |
| max_warehouse_sk | 分组内仓库主键（w_warehouse_sk）最大值；属于标识符，不作金额或数量求和 | queryDefinition 显式别名 max_warehouse_sk 与 description（仓库主键范围） | CONFIRMED |

## 局限
- 分布只有一个分组，无法开展跨国家/省州的数量对比、排序或份额分析（也不存在可计算的“占比”——单一分组占比恒为 100%，不构成有效结论）。
- 城市覆盖只有数量、没有名称与城市级明细，无法回答“具体哪些城市有仓库”。
- 缺少日期字段，无法说明统计截止日或历史变化，也无法判断该分布是否长期稳定。
- “仓库数量”是 warehouse 维表按 COUNT(*) 得到的记录数，其业务口径依赖源查询声明（每行代表一个仓库）；本次未获得独立的仓库台账可比总数，因此未做跨源对账。

## 校验与审计附录
- 已核对交付清单、数据分片哈希与行数（1 行），并逐字段映射存储列到声明字段，六个声明字段均无 null/缺失。
- 分组键（国家+省州）唯一；明细表仓库数量合计与指标“仓库总数”一致。
- 图表数值与明细表使用同一计算结果，未做四舍五入替换。

*统计口径单位“个”为用户指定的计数单位；仓库主键为标识符，未参与任何求和。*

## 查询结果明细

已找到 1 行数据，下面展示前 1 行；完整结构化数据已随结果返回用于表格展示。

| w_country | w_state | warehouse_count | city_count | min_warehouse_sk | max_warehouse_sk |
| --- | --- | --- | --- | --- | --- |
| United States | TN | 5 | 1 | 1 | 5 |