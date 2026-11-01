# 新业务及系统图表验证

使用资产 248 的 warehouse、inventory、date_dim、item 实际注册元数据；工程 0fec15ec-f8aa-4950-a84c-07f220fd5a6b。模型实际生成两项新业务 SQL，不再固定商品 1。

| 用例 | 业务 | 已取得证据 |
| --- | --- | --- |
| B01 无参数 | 按国家、省州统计仓库与覆盖城市，返回仓库主键范围 | 模型原始输出、元数据、实际试执行 1 行 |
| B02 有参数 | 指定仓库和期间的逐日库存快照，数量下限及可空类别 | 模型原始输出、元数据、实际试执行 5 行 |

原始记录位于 cases/B01、cases/B02。修复版 r2 的试执行因首尾空白一致性检查失败，保留为 trial-r2-failed.json，没有把此前成功标志拿来继续发布。该问题已增加带注释、占位符说明和末尾换行的真实 H2/JDBC 回归；远程 r3 复验已通过，随后按正常链路发布并被独立 MCP 客户端及普通 Agent 实际调用。

[独立 JDBC 基准](blind-business/independent-jdbc.json)另行执行了同义查询：仓库 2 的 5 个日期、仓库 4 的 Books 类别和数量下限 500 的 3 个日期、仓库 1 的 5 个日期及不存在仓库的空结果。没有从 Agent 回答抄写期望数字。

复现入口为 `scripts/sql-tpcds-acceptance/studio-scenario.mjs`，配置见 [scenario.json](blind-business/scenario.json)。依次 setup、generate、status、apply、draft；通过既有数据源授权入口批准精确模板编码后，再 test、publish。每次真实试执行回执按 testId 保存，失败不能覆盖成成功。

系统内分类柱状图和日期折线图均已真实执行并渲染，见 [图表报告](06-chart-and-python-validation.md)。报告的未知单位和快照日期覆盖限制继续保留，不作完全业务认证。

## 依赖查询所需的可复用输出

后续A06暴露：原分布模板只有各分组最小主键，不能把任意首行当成全局最小，也不能让Java未执行的reasoning节点编造聚合结果。因此通过正常Studio模型生成、试执行、精确编码审批和发布流程，保留原六列并增加全表global_min_warehouse_sk，现为v4。见 [实际模型请求](compatible-geography/request.md)、[原始模型SQL](compatible-geography/model-generated.sql)、[试执行](compatible-geography/test.json)和[发布](compatible-geography/publish.json)。它是具备声明语义的新SQL能力，没有固定仓库号或查询答案；当前普通Agent保持原通用提示词，原失败记录未删除。

库存模板仍是参数化数据查询，不含分析程序。按需Skills与SQL/Python发布彼此独立；本轮Python还通过实际Cline生成并在Sandbox执行，未假装两张图来自一个预置Python模板。
