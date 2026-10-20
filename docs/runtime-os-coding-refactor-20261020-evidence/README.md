# 2026-10-20 改造证据索引

本目录仅含源码依赖/调用位置、测试汇总及脱敏运行证据；不包含部署凭据、原始业务行或模型 Prompt。

- `baseline-source-audit.json`：冻结 Commit、分支/Tag、模块与 Maven 依赖。
- `source-audit-final.json`：最终产品代码 `40ad2e56` 的 RAG、共享索引和间接装配扫描。
- `code-changes.txt`：相对冻结 Tag 的完整新增、修改、删除清单。
- `baseline-test-summary.json`：首次基线实际生成的报告；不能视为完整后端已执行。
- `directed-test-summary.json`：306 项定向测试，含实际补跑的 SQLGlot 条件测试。
- `live-fixes-test-summary.json`：联机问题修复后的 70 项专项。
- `full-test-final-summary.json`：最终全量尝试生成的 1,152 项报告及失败名单；有原生崩溃和未执行依赖模块，整体不通过。
- `module-package.json` / `module-manifest.json` / `runner-manifest.json`：模块、API 白名单类和 Node 的哈希。
- `deployment.json` / `deployment-runner-analysis-fast.json`：最终验证环境镜像和备份；`*-initial.json` 保留首轮候选证据。
- `service-and-boundary.json`：最终健康、回调认证及业务库/MCP/外网隔离检查。
- `qa-summary.json` / `simple-data-fixed-summary.json`：真实 QA 与修复后 Parquet 分析闭环。
- `performance-attempts.json`：按版本/场景分别记录取消、失败及成功样本；未知字段为 null，不将失败样本混入成功耗时统计。
- `post-validation-sessions.json`：验证结束时 Node 活动会话为 0。

完整说明与回退方法见 [改造交付记录](../runtime-os-coding-refactor-20261020.md)。所有通过项均不解除全量回归及业务正确性门禁。
