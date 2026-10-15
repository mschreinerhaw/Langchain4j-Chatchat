# Runtime OS Coding Agent 容器联调记录（2026-10-15）

## 范围与结论

Node Coding Agent 已封装为 `runtime-coding-agent:1.0` 镜像，并在 `192.168.195.221` 的独立 `coding-agent-stage` Compose 项目启动；生产 Java API/MCP JAR 与配置未改，Coding/Cline 仍关闭。容器部署、鉴权、健康检查、重启和 Node→Java 网络连通性已经实测。**D1 Java→Node→Python Sandbox→Evidence 端到端链路尚未完成；D2/D3/G3 未验收。**

## 本轮代码

- `coding-agent-node/Dockerfile`、`.dockerignore`、`deploy/docker/coding-agent/compose.yml`：固定 Node 24.21.0 基础镜像；非 root、只读根目录、专用 tmpfs、无 Docker Socket/业务挂载，限制能力、内存、CPU 和 PID；密钥文件注入；端口仅映射宿主 loopback。
- `coding-agent-node/src/server.js`：容器内监听、回调主机显式白名单、拒绝 HTTP 跳转、经鉴权的 `/healthz`、文件密钥读取；原有任务接口和合同/fencing 绑定保持不变。
- `ClineCallbackAuthFilter`：Java 回调按明确配置的容器地址校验来源；默认仅允许 loopback。`ClineModelCallbackController` 在模型返回后再次核验执行所有权和停止状态，阻断取消后的迟到回执。
- `ClineModelReceiptStore` 和 `CodingTaskRuntimeConfiguration`：模型响应回执复用既有 Evidence Store，接入 A2 停止证明验证。`CodingInvocationContext` 提供受信调用身份。本轮没有新增通用 Runtime/Evidence Store，也没有改变既有授权模板取数流程。

## 实测结果

| 检查 | 实测 |
| --- | --- |
| Node 单元测试 | 9 项通过，0 失败；覆盖 SDK 修复循环、签名回调、HTTP 幂等、健康鉴权和容器回调主机白名单。 |
| Java 定向测试 | `ClineCallbackAuthFilterTest` 3 项、`ClineModelCallbackControllerTest` 2 项，合计 5 项通过；H2 测试覆盖模型回执/预算与模型运行中取消。完整多模块构建成功。 |
| 本地真实 Docker | 镜像构建成功；容器非 root、只读根目录。HTTP 无认证 401、认证 200。`internal: true` 网络阻断宿主双向通信，移除后映射端口正常；本机无 Java 8080 服务，Node→宿主得到 `ECONNREFUSED`。 |
| 服务器离线部署 | Docker Hub 拉取基础镜像被拒绝；将本地构建镜像离线传入。镜像 tar SHA-256 为 `dc640c431fe9edb586042cc65ccb9c8491fdc559bbfbd831d91ac46697a410e4`，服务器校验一致。 |
| 服务器容器 | `coding-agent-stage-coding-agent-1` 正常运行，health=healthy；UID:GID `1000:32101`、根目录只读、非 privileged、capabilities 全删、内存 2 GiB、PID 128，端口仅 `127.0.0.1:8791`。 |
| 服务器网络与鉴权 | Node `/healthz` 无认证 401、认证 200；容器访问 Java `host.docker.internal:8080/` 返回 200；原 API `/` 200、MCP `/` 302。容器重启后认证健康接口仍返回 200。 |
| 合成故障复现 | 数据为空的 Node 合成请求得到 202 后转为 FAILED，首个错误明确是 Java `/internal/coding/cline/reserve-model` 404；随后重启 staging Node 清除进程内测试会话，原会话查询返回 404。此请求不是生产 Runtime Coding 任务。 |

首次服务器容器因 `root:root`、`0600` 密钥文件无法被非 root 进程读取而重启。现已使用专用数值组 `32101` 和 `0640` 修复；不使用宿主普通用户组。Docker Hub 不可达通过校验哈希的离线镜像解决。以上是实际调试问题与修复，不代表业务执行已连通。

## 阶段门槛

生产 API 当前仍没有启用 Cline 回调路由；本地新增的 Java 模型回调尚未部署。`execute-python`、`cancel-python`、`read-metadata`、`read-artifact` 和可信 `finish` 仍待实现。授权 DatasetHandle 到不可变 Coding 输入的绑定、独立验算、证据归集、费用/工具预算、跨进程恢复和容器出站白名单仍待联测。无真实生产 Coding 任务、无真实模型提供方调用，不能将本轮健康/连通性测试视为 D1 完成，也不能进入 D2/D3 或宣称 G3 通过。
