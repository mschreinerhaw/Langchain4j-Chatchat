# 联网搜索与 News 职责审查

2026-11-04；基线 `7cf22fa71`，实施分支 `feature/cline-internet-search`。先审查实际调用和隔离，再实施。

## 已有调用与复用点

- 模型管理：`api/model/PlatformModelCatalogService` 以 platform_model_config / platform_model_publication 保存草稿和发布配置，已有 InternalSecretCipher、发布与默认模型机制；前端 ModelManagementView 高级选项尚无联网配置。
- 现有外部搜索：`runtime-news/search/provider/TencentWebSearchClient` 调用腾讯 WSA；`ExternalWebSearchToolExecutor` 依赖 Java 预处理关键词及缓存。`WebSearchToolExecutor` 在本地资讯不足时自行回退外部。MCP 的 `WebSearchExecutionWorkflow` 还混合内部金融数据发现。这不是供 Cline 自主选择来源的三个原子工具，不能仅改名称后宣称完成。
- Cline：`CodingAnalysisService` 冻结合同，`runner.js` 只接受已登记工具，Node 的 knowledge 回调进入 `ClineModelCallbackController` / `CodingKnowledgeService`，后者逐次授权、记账并保存来源证据。`CodingResourceReader` 通过已有 MCP 控制面读取 News/市场，不允许任意目标和任意工具。
- News：采集、标准化、去重、OpenSearch、Temporal 调度及重试均有独立组件。`NewsContentAnalyzer` 目前是关键词分类、股票代码提取及有界摘录，不调用模型；不能将它误判成独立报告 Agent 后删除。旧搜索接口及 FinalSummaryWebSearchEnhancer 有实际调用方，需要保留兼容路径，Cline 新路径不使用其自动外部回退/额外正文分析。

## 实际网络边界

`deploy/docker/application/restrict-coding-worker.sh` 按当前容器身份配置主机 INPUT / DOCKER-USER：Worker 仅能到 API 的专用 8081 回调端口，其余丢弃。现有 coding Docker 网络 internal=true、IPv6=false。2026-11-04 从实际 Worker 内探测公共 DNS 返回 EAI_AGAIN，直接 HTTPS 失败；这证明当前探测受阻，不能替代后续针对 IP、内部普通 API、Python 及功能开关的完整验证。

`PythonDockerRuntime` 对 NONE 环境实际使用 `--network none`；`CodingAnalysisService` 要求报告环境为 NONE，`CodingPythonExecutionBridge` 再核验任务与环境。新增搜索不得把 Worker 或 Python 改为有公网出口，也不能仅靠删除 MCP 工具关闭联网。搜索服务端的受控 HTTP 出口与分析代码的网络权限必须分离。

## 改造边界

复用模型数据库、现有资源授权、MCP、Coding 回调、任务预算、不可变输入、来源证据与报告归档。默认关闭联网，每次调用重新检查模型/Provider/Agent/用户授权；运行中关闭后立即拒绝新调用，旧任务不能继承重新开放的权限。Provider 配置集中保存并加密，模型只保存 Provider ID。服务端网页读取需要 DNS/IP 固定、逐次重定向验证、响应大小/时间限制和 robots 处理；网页是来源数据，不是新权限。搜索工具不提供摘要模型、搜索策略编排或新 Agent 框架。

后续验收须区分真实调用、模拟错误、未执行与失败；没有有效 Provider 凭据时不能把模拟搜索当作联网成功。
