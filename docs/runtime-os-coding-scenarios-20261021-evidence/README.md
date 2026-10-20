# 补充场景脱敏验收证据

对应部署代码 `eb571d57758866aaf85de33de844285d86dcba11`。原始问题、模型消息、业务数据、生成代码及完整报告保留在验证服务器受控证据目录，不进入本目录。

- [联机指标](scenario-metrics.json)：修复前、中间候选、最终候选分别记录；缺失遥测为 null，不视为 0。
- [收尾检查](post-validation.json)：3 个最终任务通过、执行器停止、9 次 Python 容器释放、Node 活跃会话 0。
- [定向测试](directed-summary.json)：Java 190 项、最终提示复测 35 项（重叠）、Node 44 项、浏览器 12 项及脚本 3 项。
- [全量回归](full-regression.json)、[失败差异](regression-delta.json)：全量未通过，Windows JNI 崩溃导致部分模块未运行。
- [SQL 解析](sql-validation.json)：MySQL/Hive 两个独立建表片段，均 56 字段；仅语法解析，不代表数据库兼容性、执行成功或业务标准认证。
- [源码依赖审计](source-audit.json)、[场景字面量检查](scenario-literal-check.json)、[变更清单](code-changes.txt)：保留共享基础能力和未分类兼容路径的原因见主报告。
- [API 部署](deployment.json)、[Node 部署](deployment-runner-analysis-fast.json)、[边界探测](boundary.json)：仅隔离验证环境。
- [模块清单](module-manifest.json)、[组包校验](module-package.json)、[Node 清单](node-manifest.json)：代码、镜像和包哈希可追溯。
- [旧任务收敛](before-reconciliation.json)、[设计产物哈希](scenario1-final-artifacts.json)、[主机产物哈希](scenario2-final-artifacts.json)。

性能数据是单次联机观察，尚无代表性重复基准。证据中的业务结构校验状态不代表业务正确性已认证；生产发布门禁仍关闭。
