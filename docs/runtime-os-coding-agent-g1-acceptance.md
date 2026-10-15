# Runtime OS Coding Agent G1 验收报告

日期：2026-10-15。验收范围：B1–B4 的 MCP Python Coding 执行设施；不包含 C 阶段证据发布、业务规则校验或 Cline SDK。

## 验证证据

定向命令：

```powershell
mvn -pl chatchat-api,chatchat-mcp-server -am -Pbackend-only '-Dfrontend.skip=true' '-Dcoding.realDocker=true' '-Dtest=PythonManagedExecutionServiceTest,PythonManagedInternalAuthFilterTest,PythonOperationWorkspaceTest,PythonCapabilityServiceTest,PythonDataFileServiceTest,McpPythonExecutionProofVerifierTest,McpPythonControlPlaneClientTimeoutTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

完整日志：[coding-b-final-validation.log](../target/coding-b-final-validation.log)。17 个 reactor 模块构建成功；定向测试合计 **15 项，失败 0、错误 0、跳过 0**，其中 integration 1 项、API 1 项、MCP 13 项。JUnit 报告位于各模块 `target/surefire-reports`。这不是全仓库测试。执行时 Docker Desktop Linux Engine 版本 29.6.2，使用实际本地镜像 `python:3.11-alpine` 的内容 ID `sha256:d9368b3a5ac59afea7b5d4f2e2aea0941dbf9fdee9c369c5bec00b98244bc929`；测试结束后 `docker ps -a --filter name=chatchat-py-` 未留下容器。

| 验收点 | 依据 | 判定 |
| --- | --- | --- |
| 同 operationId 幂等、冲突及并发竞争 | 真实 H2 JDBC、8 个并发请求、容器创建计数 | 通过 |
| 任务级 fencing 拒绝旧 token 启动/提交 | H2 行锁 + 真实 Docker 接管停止 | 通过 |
| 独立代码与输入快照 | H2/文件测试、两只真实 Docker 容器并发输出不同结果 | 通过 |
| 实际取消及子进程停止 | 真实 Docker 持续写入/子进程，inspect 确认停止 | 通过 |
| 网络结果丢失与重启恢复 | 实际 HTTP start 超时后查询；独立子 JVM 在容器运行中 `halt`，新 JVM 同 H2 文件库查询并归档 | 通过 |
| 未知启动意图不重放 | H2 注入 `STARTING`/容器缺失并查询 `UNKNOWN`，创建计数不变 | 通过 |
| 零行、缺失及部分输出区分 | 工作区 manifest 单元测试 | 通过 |
| 容器删除后授权读取归档 | 真实 Docker 完成与取消测试 | 通过 |
| 内部授权与停止证明 | 普通管理员令牌拒绝；作用域、执行身份与回执哈希核对 | 通过 |
| 旧接口兼容 | PythonCapabilityService、PythonDataFileService 原有定向测试 | 通过 |

## 阶段门槛与残余风险

**G1 通过，范围限于本地真实 Docker + H2 文件/JDBC 的 B1–B4 执行设施。** 可进入 C1–C3 证据回流实施；这不表示 G2 或业务分析结果通过验证。生产 MySQL/PostgreSQL 方言、跨机器共享工作区、累计 CPU/费用/输出字节硬额度、宿主输出目录磁盘配额未在本门槛证明。MCP 节点仅接收内部 Runtime 账户命令；A2 权威租约与预算通过 API bridge 准入，节点保存 fencing 水位与预算引用，但不直接读取 A2 数据库。API bridge 的 A2→MCP→回执结算完整链路尚无真实联合集成测试，需在 C3 固定脚本验收中补齐。C 阶段仍需核对真实输入和业务计算后才可发布 Evidence Bundle。
