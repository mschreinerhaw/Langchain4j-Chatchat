# Runtime Native 业务适配层

状态与失败清单见 [业务阶段提交说明](../docs/audits/coding-agent-native-business-review.md)。该入口不替换原有默认服务启动命令，不代表五类业务验收通过。

2026-11-09：业务执行与独立 Monitor 已统一集成至专项分支；浏览器 Native 多轮对话、真实错误修复、一个 CSV Runtime 任务的验算归档已取得证据。本轮最低可用九项通过，完整构建及最新浏览器结果见 [统一交付报告](../docs/testing/native-cline-business-monitor-delivery-20261108.md)。这不覆盖全量回归、金融场景和完整图文交付的未通过项，不批准生产替换。

## 构建与启动边界

复用官方 v4.1.26 Standalone 及已有受限 Executor。业务 Host 通过官方工具路由公开 submit_and_exit，并将结束校验绑定到 Runtime 实际 Python 回执；不修改 Core Agent Loop。原生 MCP 调用保留既有 operation context 适配。实际编译文件及镜像 Hash、验收状态见 [业务验收报告](../docs/testing/native-cline-business-acceptance-20261108.md)。

Gateway 用 Node 22 启动：

```sh
CLINE_BUSINESS_CONFIG=/private/business-config.json node cline-agent-server/src/business-server.mjs
```

打包需包含整个 `cline-agent-server/src`，以及仓库现有 `coding-agent-node/src/knowledge-tools.js`、`src/skill-distribution.js`、`src/code-repair.js` 和其引用的 Skills 资源。这些是纯协议/资源依赖，不加载旧 server/runner/Agent Loop。Gateway 不挂载 Docker Socket；只挂载专用 Executor bridge、该专项实例 state/workers 目录和只读源码。沿用已审查的 Executor 固定 API，不能开放任意 Docker 参数。

私有 JSON 配置必须包含：`secret`（至少 32 字符且与专项 Runtime 匹配）、`signingKey`、`workerImage`（sha256 digest）、`stateRoot`、`workersRoot`、`callbackUrl`（loopback Runtime 根地址）、`executor.socketPath`、`executor.token`。可选 `port`（默认 18341）、`maxWorkers`（默认 2）、`editTool`（默认 apply_patch，可选 editor）。私有配置及模型凭据不提交仓库。

Java 专项实例显式设置 `chatchat.coding.cline.backend=native`、`node-url`、`shared-secret[-file]`，启用已有 coding/analysis 服务。默认 backend 仍为 sdk。编译需使用 API 自身 release libs，不能混入 MCP 的同名依赖覆盖版本；本轮完整 20 模块 Maven 打包通过；全量回归仍有失败，详见验收报告。

Worker 的固定 MCP 名设置为 `runtime`，模型 broker 使用现有 Runtime 模型回调，MCP broker 复用授权数据与 Managed Python。Executor 固定工具清单需包含最终版本的 `submit_and_exit` 和所选官方编辑工具，再执行真实部署验证；只改 Gateway 白名单不充分。

## 检查与操作

`GET /healthz` 必须携带 Bearer shared-secret，检查 engine=cline-standalone、version=4.1.26、legacySdkWorker=false。健康响应含 productionReady=false。`/v1/runs`、`/get`、`/cancel` 使用既有 Java 适配器合同，禁止把裸用户输入当作可信合同；用户仍从 Runtime 业务入口提交。

```sh
node --test cline-agent-server/test/*.test.mjs
```

Java 单元探针在 `test/business-java`，依赖实际 API/classes 和 API 自身 libs，使用 `javac/java --add-modules jdk.httpserver`。这些单元探针不调用真实模型或真实远端 Python，不可计为业务通过。

重启不自动重放历史任务；UNKNOWN 必须先核查 Runtime Journal、Executor 及远端操作。回滚按业务报告操作，只调整专项实例，旧节点保留，禁止同一任务双执行。

Owner 资产分析入口可显式提供 `requiredArtifacts`，最多八个唯一的结果文件名（`md/png/xlsx/json/jsonl/py`，仅安全 basename）。这些文件与 `rows.jsonl` 一同进入不可变任务合同及请求指纹；Collector 必须在真实停止操作的 Hash 校验归档中逐项找到。文件存在及结构检查不替代独立业务验算，通用 `INCONCLUSIVE` 结果仍不得标记 Native 业务 COMPLETED。

Native 分析采用精简核心合同；完整来源、Schema、环境和资源元数据保留在工作区 `runtime-inputs.json`。默认 SDK 合同保持原有路径。Native 专项只对源码 Hash、实际停止回执和脚本栈位置一致的显式 `raise RuntimeError(...)` 提供程序错误分类；未知来源、资源限制、取消和超时仍受既有 STOP 策略约束。

构建业务 Worker 时，在干净、固定的官方源码目录使用 `CLINE_RUNTIME_OPERATION_CONTEXT=1 CLINE_SOURCE_ROOT=/fixed/upstream bash cline-agent-server/scripts/build.sh`，保留既有 MCP 元数据适配。补丁按固定锚点应用，重复应用会拒绝。Standalone check-types、lint 及构建必须通过，并核对 Core 源文件指纹和 runtime-manifest 后派生镜像。

真实修复故障验证可使用 `test/business-repair-live.py --root /isolated/root --commit SHA`：它仅在新任务独立工作区放置抛出真实异常的已有源码，在 Native Session 创建前完成；修复与再次执行由官方 Cline 自主完成。请求、故障注入及真实回执保存在私有证据目录，不重复派发已有请求。
