# Intent Ensemble Retrieval Contract

本文档定义 Agent Runtime 在资产发现、模板发现和文档检索前的意图路由、多查询检索和结果 review 契约。该契约属于 MCP 工具编排和检索输入规范，不是某个业务问题的特例。

## 目标

当用户问题是聚合表达、跨系统表达或不包含精确资产名时，系统不得把自然语言短语硬猜为 `assetName`。Planner 必须先识别多个可能意图，按置信度排序，并把高置信意图及其扩写查询作为检索信号交给资产、模板或文档检索。

标准链路：

```text
User Question
  -> Intent Routing
  -> Intent Candidate Scoring
  -> Threshold Intent Selection
  -> Multi Query Expansion
  -> Retriever Pool
  -> Merge and Dedupe
  -> Retrieval Review
  -> Evidence or Secondary Retrieval
  -> Final Answer
```

## 适用范围

该契约适用于：

- `asset_query`
- `ssh_asset_query`
- `database_asset_search`
- `sql_datasource_asset_query`
- `http_endpoint_asset_query`
- `template_query`
- typed template query tools
- document/knowledge retrieval tools that accept `target_filters.v1`

## 字段契约

所有字段必须放在 `filters` 内，或由 Runtime/Resolver 归一化到 `filters` 内。

```json
{
  "filtersSchemaVersion": "target_filters.v1",
  "filters": {
    "intent": "用户原始或归一化后的主意图",
    "goal": "本次检索目标",
    "intentCandidates": [
      {
        "intent": "Kafka",
        "score": 0.96,
        "queries": ["consumer lag", "offset commit"],
        "keywords": ["max.poll.records", "batch.size"]
      },
      {
        "intent": "RocksDB",
        "score": 0.88,
        "expandedQueries": ["write stall", "compaction", "flush"]
      }
    ],
    "queryTerms": [
      "Kafka",
      "consumer lag",
      "offset commit",
      "RocksDB",
      "write stall",
      "compaction",
      "kafka消费慢是不是rocksdb写入导致的"
    ],
    "retrievalSignals": [
      "Kafka",
      "consumer lag",
      "offset commit",
      "RocksDB",
      "write stall",
      "compaction",
      "kafka消费慢是不是rocksdb写入导致的"
    ],
    "intentScoring": {
      "strategy": "threshold_intent_ensemble_plus_original_query",
      "threshold": 0.75,
      "fallback": "top2_when_no_candidate_reaches_threshold"
    }
  }
}
```

## Intent Candidate 结构

`intentCandidates[]` 支持以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `intent` | string | 候选意图名称或主题 |
| `score` / `confidence` | number | 0.0 到 1.0 的置信度 |
| `query` / `term` / `text` / `label` / `name` | string | 可作为检索词的别名字段 |
| `queries` | string[] | 针对该意图生成的检索查询 |
| `queryTerms` / `searchTerms` | string[] | 归一化检索词 |
| `expandedQueries` / `expanded_queries` | string[] | 多查询扩写结果 |
| `keywords` | string[] | 技术关键词、命令名、指标名、组件名 |
| `intentAliases` | string[] | 中英文别名 |
| `bilingualIntent` | string[] | 中英文混合检索信号 |

## 选择规则

Runtime/Resolver 必须使用以下确定性选择规则：

1. 按 `score/confidence` 从高到低排序。
2. 选择所有 `score >= 0.75` 的候选意图。
3. 如果没有任何候选达到 `0.75`，回退选择 Top 2。
4. 将被选中候选的 `intent`、扩写 query、关键词、别名合并为 `queryTerms`。
5. 将用户原始问题加入 `queryTerms` 和 `retrievalSignals`。
6. 合并时必须去重并保持稳定顺序。

不得固定只取 Top 2。Top 2 只是低置信回退策略。

## Multi Query 生成规则

Planner 应为每个高置信候选意图生成领域相关查询，而不是只检索意图名称。

```text
用户问题:
kafka消费慢是不是rocksdb写入导致的?

Intent Ranking:
Kafka   0.96
RocksDB 0.88
Flink   0.31
Linux   0.11

Kafka queries:
consumer lag
consumer poll
offset commit
max.poll.records

RocksDB queries:
flush
compaction
write stall
block cache
write amplification
```

## 精确资产名规则

`assetName`、`env`、`service`、`cluster` 等路由标签是精确路由字段，不是语义检索字段。

允许写入 `filters.assetName` 的来源只有：

1. 用户明确提供了已注册资产名或工具名。
2. 前置资产发现、模板发现或注册元数据 observation 返回了 canonical asset name。
3. Runtime 已经从用户选择的资产范围中获得了确定资产。

禁止行为：

- 不得把 `MySQL服务器`、`xxx数据库`、`Kafka问题` 这类自然语言聚合短语硬写成 `assetName`。
- 不得把意图词、组件名、能力描述拼接进 `assetName`。
- 不得从自然语言主题词发明 `service:<topic>`、`cluster:<topic>` 等标签。

不确定时必须使用 `intentCandidates/queryTerms/retrievalSignals` 检索。

## Review 契约

检索完成后，Review 阶段至少判断四件事：

1. 相关性：检索结果是否真正支持用户问题。
2. 覆盖率：是否覆盖所有高置信 intent。
3. 冲突：不同结果是否存在版本、环境、口径冲突。
4. 二次检索：如果缺少关键 intent 或关键证据，是否需要扩展查询再检索。

Review 不得因为检索结果不是完整最终答案就直接拒绝。只要结果能提供可执行资产、模板或证据线索，就应标记为有用证据，并把缺口交给后续步骤或二次检索处理。

## MCP Server Schema 规则

MCP Server 的 targetKind 过滤字段白名单必须允许以下语义检索字段：

```text
intent
goal
query
q
bilingualIntent
bilingualQuery
intentZh
intentEn
intentAliases
keywords
keyword
queryTerms
searchTerms
retrievalSignals
intentCandidates
intent_candidates
queries
expandedQueries
expanded_queries
```

如果新增检索字段，必须同步更新：

- Planner prompt contract
- Rewriter repair contract
- Runtime/Resolver argument normalization
- MCP Server targetKind allowed filter fields
- Asset/template retrieval services
- Contract tests

## 错误处理

如果检索字段被 schema 拦截，例如：

```text
Filter field is not allowed for targetKind=host: intentCandidates
```

这属于契约实现缺口，不应由模型绕开字段或删除 `intentCandidates`。正确修复方式是同步更新 MCP Server targetKind 字段白名单和相关测试。

## 最小测试要求

每次修改该契约相关实现，至少覆盖：

1. Planner prompt 是否要求生成 `intentCandidates` 和阈值选择规则。
2. Resolver 是否选择所有 `score >= 0.75` 候选，并在全低置信时回退 Top 2。
3. Resolver 是否合并原始问题和 multi-query expansions。
4. MCP Server 是否允许 `intentCandidates/queries/expandedQueries` 通过 targetKind 校验。
5. Asset/template retrieval 是否能消费语义检索字段。

