# LiveData测试库_223 部署与运行阻断项

2026-10-16 预检基于 `main@bdc7f898`、第二阶段门禁矩阵、失败清单、实际构建及目标主机只读检查。用户提供了目标主机和登录信息；报告不保存凭证。

| 优先级 | 问题 | 影响与下一步 |
|---|---|---|
| P0 | 后端全量门禁失败：3451 项中 142 失败、64 错误 | 本轮重新单独复跑后，`AgentAuthorizationIsolationTest` 3 项均因 Runtime semantic policy 未发布而无法启动上下文，`CommandTemplateDiscoveryDatabaseQueryTest` 8 项中 3 失败，`HarnessToolAccessTest` 7 项中 3 失败；两项 G3 在全套运行时失败且包含执行结果未知。结果见 [focused-gate-rerun.json](livedata-223-evidence/focused-gate-rerun.json)。逐项修复并重跑完整套件，才能解除真实数据执行阻断。 |
| P0 | 未完成目标资产的 MCP 发现和授权确认 | 无资产 ID、实例类型/版本、环境、只读授权模板与参数绑定记录。先通过现有 MCP 身份发现资产和已发布模板，不按名称猜测地址，不让 Cline 直连。 |
| P0 | 隔离 Java API/MCP 未激活 | 主机 8080/8090 为现网；旧 18080 测试入口不在监听。新 Java JAR 仅隔离存放；需要独立配置、数据源权限与资源预算后才能启动，不能在现网端口直接替换。 |
| P1 | Worker 构建依赖 Docker Hub 的基础镜像拉取超时 | 标准 Dockerfile 构建失败；已用目标主机现有、SDK 版本相同的镜像作为基础覆盖源码，隔离 Node 健康检查通过。正式发布前须验证镜像来源和完整依赖锁定。 |
| P1 | 大结果首次 `collect` 仍受 4 MiB/1 万行契约限制 | 第二阶段分页读取不等于首次归档可无限扩容。需流式归档和独立验算，超限必须显式拒绝。 |
| P1 | 分片重入仍重新扫描源页 | 相同内容片可幂等复用，但没有源游标精确恢复；大输入重试成本及源数据变化风险须验证。 |
| P1 | 真实 SDK/Agent API 业务验收缺失 | `hy3` 最小模型探测已通，仍需隔离 Java 回调、真实 Cline 任务、Python 产物、完整执行尝试和独立确定性验算。 |

现网 API/MCP 和生产 Worker 未变更。隔离测试 Node 位于回环端口 8793；出现资源压力时停止并删除 `coding-agent-phase2-stage`。远端产物和隔离容器状态已固化在 [部署清单](livedata-223-evidence/deployment-manifest.json)。继续工作时先建立可回滚的独立 Java 测试栈，复核权限/模板/数据正确性用例，再启动只读数据库诊断。候选 SQL 见 [未执行的模板清单](livedata-223-innodb-template-candidates.sql)。
