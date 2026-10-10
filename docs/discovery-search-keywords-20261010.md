# 能力发现的搜索词与任务上下文分离

长分析任务曾被重复写入 `query`、`filters.goal`、`filters.intent` 和 `filters.queryTerms`。这会把报告要求、执行约束和证据协议带入 OpenSearch 召回，降低搜索语义的清晰度。

根因包括参数绑定自动追加原始问题、Planner/Rewriter 提示要求保留原始问题作为搜索词、能力工具把 `query` 描述为完整请求，以及资产、模板和绑定子模板召回混用上下文与搜索词。

## 实现

- 模型选择简短关键词或能力短语；原始任务保留在模型上下文和执行轨迹中。Resolver 不再自动把原始任务加入 `queryTerms`。
- 能力桥保留模型给出的 `query` 短语：没有独立搜索词时，将其传入 `queryTerms`，避免被原来的 `intent` 上下文遮蔽。模型返回嵌套 JSON 或合同声明的点分参数路径时均可读取。
- 运维能力发现工具发布 `DISCOVERY_QUERY_PROFILE` 合同，复用现有模型检索桥接。模型可以替换检索参数中的长任务，不能修改环境、精确路由身份或权限字段。没有新增 Agent、业务关键词词典或质量门禁。
- 资产、模板和绑定子模板共享 `DiscoveryQueryPlan`。已有明确搜索词时，`goal/intent/query/q` 不再增加搜索单元；关键词分别召回后合并、去重，保留各自的来源。
- 旧调用没有明确搜索词时仍兼容 `intent/query`。模型桥接失败时保留原参数，遵循现有失败处理；本次修改没有强制截断或拒绝模型查询。

示例中的搜索请求可以表达为：

```json
{
  "query": "InnoDB",
  "filters": {
    "queryTerms": ["LiveData测试库_223", "InnoDB", "事务", "实例变量"],
    "env": "DEV"
  },
  "limit": 10
}
```

这里的库名是检索线索；精确资产路由仍使用已发现的注册身份。

## 验证

相关测试共 211 项通过：参数绑定、模型检索桥接、解释计划运行、查询单元、能力工具发布、绑定模板召回与 OpenSearch 查询安全。初次验证 210 项通过；补充修复后重新验证涉及的 28 项，其中新增点分路径测试。API 与 MCP 后端打包成功。

已部署到调试主机，API 健康状态为 `UP`，MCP HTTP 和 gRPC 服务启动成功，工具目录已返回新的参数描述及模型检索合同。首次 MCP 部署因启动检查时限回滚；调整检查时限与地址后部署成功，未修改应用配置。

- API SHA-256：`49e42dbb757220faeda55b7ba198fa5c2f3df3aa7f007faba9fae6987c304ac0`
- MCP SHA-256：`fde41e8230b82b79ae7fb6d7c7f414e1d9c118f2ef2b8cef755f4df6933217f7`
- API 备份：`/opt/chatchat-deploy-backup/search-keywords-20261010-h8wUke`
- MCP 备份：`/opt/chatchat-deploy-backup/search-keywords-20261010-h1YZC8`
- 最终版本部署前备份：API `/opt/chatchat-deploy-backup/search-keywords-20261010-tX5xTq`；MCP `/opt/chatchat-deploy-backup/search-keywords-20261010-8rVxQB`。
- 实际只读回归任务：`1f6e4cb3-714e-415e-9c48-937bcccec8ed`

只读回归任务成功完成，报告 4,584 字符。模型将长任务的 `query` 改为短检索短语；查询单元中未出现原始任务、报告要求或证据协议。模型分析经过 4 轮，自行发起 3 次 `READ_RECORDS`、8 次 `READ_TEXT` 并决定完成。本次回归同时发现仅返回 `query` 时能力词会在转换中丢失，已补上短语传递与点分路径读取并通过相关测试。

最终部署后通过 API 调用已发布的数据库能力工具，以 `query=InnoDB`、长 `goal/intent` 和已发现的资产身份进行检索。调用成功，查询单元包含 `innodb`，不包含原始任务、报告要求或 `evidenceAssessment`；保留现有注册资产的召回扩展词。API 健康状态再次确认 `UP`。
