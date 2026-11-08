# 第二阶段隔离构建与复现

固定基线 `db60dec41`，官方 Cline `v4.1.26 / a459f1d04dea030833b558be645011ab739c463b`。构建和测试只使用 `/opt/cline-server-p3-stage2-20261108`。不得套用到生产共享配置或替换默认 Coding Agent。

运行前将本分支 `cline-agent-server/`、改动后的 `DurableCodingTaskService.java`、三份下列迁移及 MCP 初始化 DDL 复制至该目录的 `source/`，维持仓库相对路径。已有输入是经过验证的第一阶段镜像、官方源码工作区、Bun 1.4.2 与已有 API/MCP 发布 Jar；准备脚本只从旧容器只读复制 Jar，不改旧容器。输出保存各 Jar SHA256。PostgreSQL 使用新数据库和本任务私有密码，所有服务凭据由准备脚本生成并限制读取，不可提交到 Git。

```sh
python3 source/cline-agent-server/scripts/stage2-prepare.py
python3 source/cline-agent-server/scripts/stage2-deploy.py
python3 source/cline-agent-server/scripts/stage2-build.py
python3 source/cline-agent-server/scripts/stage2-gateway.py
```

`stage2-build.py` 在单独官方源码副本增加两处 Host 上下文透传，运行官方 `check-types` 和 Standalone esbuild，使用第一阶段已验证资源/ABI/descriptor，并更新修改后 bundle 的资源 Hash。镜像继承第一阶段的受限操作系统与官方 Standalone，不加入旧 SDK Worker。最终 Worker 使用镜像摘要，服务代码和 bundle 均位于镜像内。

Java 适配使用 `javac` 链接既有发布 Jar 的类及依赖，覆盖的既有实现只有 `DurableCodingTaskService` 的小幅元数据扩展。没有重新打包或部署业务 Spring Boot 应用；本轮不宣称跑过整个 Maven reactor。实际 PostgreSQL/JDBC 和 Docker 测试覆盖新增接口与执行链路。

复用迁移：`V20261009_05__runtime_evidence_revision_gate.sql`、`V20261015_01__mcp_python_managed_operation.sql`、`V20261019_03__mcp_python_unadmitted_cancellation.sql`，以及初始化 DDL 的 `mcp_python_environment` 表。新环境使用已有本机 Python 镜像，`network=none`、固定 UID、capabilities drop、只读文件系统、CPU/内存/PID 约束均由既有 `PythonDockerRuntime` 生成。

新增服务：`cline-p32-db`、`cline-p32-authority`、`cline-p32-remote`、`cline-p32-control`；故障测试时另开 `cline-p32-fault`。内部网络 `cline-p32-control` 不开放外部端口；只有 Gateway 暴露宿主 loopback `127.0.0.1:18330`。Gateway 另外保留模型转发网络，仅使用复制的私有模型 profile。任务 Worker 继续 `network=none`，通过各自 UDS Broker 访问授权模型/MCP。

故障代理只在隔离测试拓扑使用。启动 `test/stage2-fault-proxy.mjs`，只挂载其脚本、自身配置和隔离状态目录，把隔离 authority 的 `mcpUrl` 设为 `http://cline-p32-fault:18403/mcp`。常规部署直接使用 `http://cline-p32-remote:18402/mcp`。代理丢弃请求或真实响应，不生成工具返回值。禁止对共享 MCP 使用该故障配置。

```sh
python3 source/cline-agent-server/test/stage2-operations.py
python3 source/cline-agent-server/test/stage2-faults.py
python3 source/cline-agent-server/test/stage2-native.py
# 通过 authority 相同 classpath，在隔离容器中分别运行：
# com.chatchat.mcpserver.python.NativeJournalGuardProbe
# com.chatchat.mcpserver.python.NativeManagedFenceProbe
python3 source/cline-agent-server/scripts/collect-stage2-evidence.py /var/tmp/cline-p32-evidence
```

先等待各服务实际 HTTP 健康检查通过，再运行测试；Docker 端口已监听并不等于应用就绪。故障测试会 kill/restart **本任务的 authority**；原生验收会 kill **本任务 Worker/Core**。执行前检查容器归属标签 `cline.stage=P3.2` / `cline.workerId`，不得替换成共享服务名称。测试已受理的外部操作只查询/取消，不能因超时重派。

权限限制与未解决的 Docker Socket 风险见 [操作协议与隔离方案](../../docs/design/cline-server-p3-stage2-operations.md)。最小权限执行代理/rootless daemon 尚未实施。本轮不以容器隔离或健康检查代替真实操作验收，也不把该 POC 拓扑当作生产部署方案。
