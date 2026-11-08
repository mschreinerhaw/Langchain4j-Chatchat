# Runtime Native 业务适配层（待验收）

状态与失败清单见 [业务阶段提交说明](../docs/audits/coding-agent-native-business-review.md)。该入口不替换原有默认服务启动命令，不代表五类业务验收通过。

## 构建与启动边界

复用已验证的 v4.1.26 Standalone Worker 镜像及已有受限 Executor。业务 Worker 派生镜像仅覆盖 `src/tool-outcome.mjs`，保留原生 Core 和 descriptor；实际镜像与文件 Hash 见证据快照。不得改用旧 SDK Worker。最终结束工具修复尚未完成 Docker 部署回归。

Gateway 用 Node 22 启动：

```sh
CLINE_BUSINESS_CONFIG=/private/business-config.json node cline-agent-server/src/business-server.mjs
```

打包需包含整个 `cline-agent-server/src`，以及仓库现有 `coding-agent-node/src/knowledge-tools.js`、`src/skill-distribution.js`、`src/code-repair.js` 和其引用的 Skills 资源。这些是纯协议/资源依赖，不加载旧 server/runner/Agent Loop。Gateway 不挂载 Docker Socket；只挂载专用 Executor bridge、该专项实例 state/workers 目录和只读源码。沿用已审查的 Executor 固定 API，不能开放任意 Docker 参数。

私有 JSON 配置必须包含：`secret`（至少 32 字符且与专项 Runtime 匹配）、`signingKey`、`workerImage`（sha256 digest）、`stateRoot`、`workersRoot`、`callbackUrl`（loopback Runtime 根地址）、`executor.socketPath`、`executor.token`。可选 `port`（默认 18341）、`maxWorkers`（默认 2）、`editTool`（默认 apply_patch，可选 editor）。私有配置及模型凭据不提交仓库。

Java 专项实例显式设置 `chatchat.coding.cline.backend=native`、`node-url`、`shared-secret[-file]`，启用已有 coding/analysis 服务。默认 backend 仍为 sdk。编译需使用 API 自身 release libs，不能混入 MCP 的同名依赖覆盖版本；本轮最终源码只完成独立 javac 校验，完整可发布 Maven/Docker 构建仍需补齐。

Worker 的固定 MCP 名设置为 `runtime`，模型 broker 使用现有 Runtime 模型回调，MCP broker 复用授权数据与 Managed Python。Executor 固定工具清单需包含最终版本的 `submit_and_exit` 和所选官方编辑工具，再执行真实部署验证；只改 Gateway 白名单不充分。

## 检查与操作

`GET /healthz` 必须携带 Bearer shared-secret，检查 engine=cline-standalone、version=4.1.26、legacySdkWorker=false。健康响应含 productionReady=false。`/v1/runs`、`/get`、`/cancel` 使用既有 Java 适配器合同，禁止把裸用户输入当作可信合同；用户仍从 Runtime 业务入口提交。

```sh
node --test cline-agent-server/test/*.test.mjs
```

Java 单元探针在 `test/business-java`，依赖实际 API/classes 和 API 自身 libs，使用 `javac/java --add-modules jdk.httpserver`。这些单元探针不调用真实模型或真实远端 Python，不可计为业务通过。

重启不自动重放历史任务；UNKNOWN 必须先核查 Runtime Journal、Executor 及远端操作。回滚按业务报告操作，只调整专项实例，旧节点保留，禁止同一任务双执行。
