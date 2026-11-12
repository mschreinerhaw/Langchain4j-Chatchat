# Native Cline HTML 报告交付

## 行为与协议

图文报告模式（`reportMode: visual`）的新 Native 任务交付一个 HTML 入口，类型仍为现有 `REPORT`，无需新增存储或另一个 Agent。JS、CSS、JSON/CSV 数据、图片及字体使用原有 `SOURCE`／`AUXILIARY`／`RESULT`／`CHART` 类型随同上传。源码名称、目录和图表结构由 Cline 自主决定。Markdown 可作附件，不能代替此模式的 HTML 入口。非图文任务保持原交付协议。

Native 会话取得输出要求；没有 HTML 或多个 REPORT HTML 入口时，原生任务获得真实交付反馈并自行修复。Java 不编写报告或安排分析步骤。原生模型循环、Python 环境、授权供数、取消及 Silo 版本／SHA-256 验证沿用现有机制。

`NativeVisualReport` 为已登记的 HTML 入口附加 `htmlReport` 描述（`runtime_html_report.v1`，entryFileId、entryPath、taskId、runId），外层保留兼容的 `python_visual_report.v1`。数据探索和普通会话优先展示 HTML；旧的 PNG／Plotly／Markdown 报告继续兼容。

通用任务 API `/api/v1/coding/tasks` 可传入 reportMode、reportPresentation；`GET /api/v1/coding/tasks/runs/{runId}/{taskId}/delivery` 返回现有交付状态、产物及展示描述。展示偏好不增加数据或工具权限。

## 读取与展示边界

`GET /api/v1/interactions/user-assets/{entryFileId}/report-bundle` 继续由 Java 验证当前账户。入口必须为已登记的 Native HTML 产物；资源必须属于相同账户、任务、run，且来自 coding-node 交付。上传的任意 HTML、输入快照、另一运行或账户的文件不能混入。

接口固定 JSON、no-store。资源保留真实相对路径、大小及 SHA-256，由原 Silo 文件服务读取并校验。单文件上限 8 MiB、合计 24 MiB、最多 64 个资源；不改变模型请求次数。

前端验证任务绑定、每个文件的大小和 SHA-256，才组装 iframe 文档。HTTPS 使用 Web Crypto；当前 HTTP 入口也可通过固定版本 `@noble/hashes@1.8.0` 校验，不跳过哈希验证。报告的普通 JS、CSS、图片和字体在内存中绑定到实际交付资源；相对 fetch 仅返回此次交付的本地数据。没有将主应用 Token、模型 Key 或存储凭据传给报告。

iframe 使用 `sandbox="allow-scripts"`，不授予 same-origin、弹窗、表单或顶层导航权限；设置 no-referrer 和限制子资源、网络 fetch 的 CSP。移除外部链接导航、嵌套框架和刷新指令。报告不可读取父页面 DOM／localStorage。运行错误由实际 iframe 消息回传，显示明确失败提示，不伪造正常画面。

当前交付目标是离线报告，支持普通脚本及预打包依赖；ES Modules 应先打包，CSS @import 应展开。外部 CDN、未交付文件、模块脚本等明确拒绝，不静默加载。查看器支持全屏，以及下载已打包所有资源的离线 HTML（这是展示副本，原始交付文件及其哈希仍保存在 Silo）。此浏览器沙箱不替代 Coding Node 执行隔离，也不将结构／文件完整性等同于业务正确性。

## 验证

- Java 50 项相关测试最终通过：原生交付、任务资源库存、HTML 无 Markdown 的投影、路径与重复文件拒绝、真实 SQL 所有者边界、同任务跨 run 排除、匿名／其他账户／非交付入口拒绝、旧存储和数据探索回归。
- 前端 53 项测试通过：报告组装、真实/缺失依赖、哈希破坏、外部 URL、模块脚本、旧报告兼容、无 Web Crypto 的 HTTP 环境和数据探索直接展示 HTML。
- 真实 Chromium 确定性用例：独立 JS/CSS/JSON 加载、计算显示 42、按钮展开明细、样式生效、父页面 localStorage 隔离。
- 真实 Cline 用例使用明确标记为 SYNTHETIC 的 CSV（A=12、B=25、C=5），不提供最终脚本；由 Cline 自主读取、计算和生成报告。独立校验 count=3、total=42、mean=14，保留模型及实际命令回执、Silo 哈希、浏览器检查和截图。

脚本：`scripts/verify-native-html-report-live.mjs`（认证从环境读取）；`RUNTIME_TEST_SEPARATE_ASSETS=1` 验证独立资源模式。`scripts/verify-html-report-browser.mjs` 可通过 `RUNTIME_TEST_HTML_BUNDLE` 加载真实归档资源包，实际点击明细按钮、核对文本变化和执行错误。模型任务脚本不以文件存在或退出码代替业务数值验证。

开发中保留的失败：独立脚本最初被二次资源重写而误拒绝，已修复并增加覆盖；接口内容协商曾返回 XML，已固定 JSON；测试夹具上传非支持的 HTML 格式，改用直接持久化的非交付夹具验证拒绝行为。容量取消测试重复暴露控制线程退出与取消信号的竞争；已新增合并的停止唤醒和确定性竞争窗口测试，确保终态登记不依赖下一次恢复扫描且不伪造 Worker 停止回执，最终 50 项通过。首次真实测试启动器的脚本路径错误发生在任务提交前，修正后执行真实任务。

## 部署与回滚

只更新源码构建的 API 镜像，包含前端与 Java；Cline Node、Executor、MCP、Silo 和共享模型配置不变。构建和部署证据位于服务器 `/opt/runtime-os/releases/native-html-report-20261112-r1` 至 `r5`。Compose 私有备份保存在对应 rollback 目录，恢复原 API image 后重建该服务即可回滚。不得删除 Silo 数据或数据库历史。已有报告刷新后仍能读取，无需重算。回滚至功能之前的版本时，HTML 文件仍保存在 Silo，可下载；旧版页面没有 HTML 预览能力。

## 两轮真实模型验收

1. 内嵌资源：task `native-01000be31db3187cf81e63312cb8162779536bb1f8e10227d6cf1b51899847e0`，13 个真实模型请求、6 个原生命令回执，耗时约 210 秒。Cline 选择内嵌 JS/CSS，输出 HTML、PNG 和 RESULT；通过结构化数值、哈希及浏览器按钮交互验证。
2. 独立资源：task `native-624a05066db91106197b5c4fc08867a247e7a6f78c7d63743804eed09accf94d`，14 个真实模型请求、4 个原生命令回执，耗时约 194 秒。Cline 按交付要求自主编写 HTML、独立 JS/CSS、JSON 和图表；实际 fetch 加载、数值显示、明细展开与匿名 401 验证通过。

两轮均由 Native Core 自主创建工程、执行计算、选择交付文件；没有人工提供最终报告脚本。输入 SHA-256 相同，独立核对均为 count=3、total=42、mean=14。真实资源包也通过生产 Vue 查看器的 Chromium 挂载、全屏、离线下载和重新打开验证，并在禁用 Web Crypto 的环境再次通过。检查脚本为 `scripts/verify-html-report-viewer.mjs`。

最终 API 镜像为 `runtime-os-api:native-html-report-20261112-r5`，本机 image ID `sha256:d2489fc306e08ea952a73cd7bd23982f0e784cc819365aa806ce4962232f233a`，源码提交 `e958f00c7`；未冒称镜像仓库 Digest。失败记录包括测试启动器、内容协商和 HTTP Web Crypto 兼容性问题，均已修复，保留在开发日志和构建记录中。临时测试 Worker 在确认 STOPPED 并归档日志后已按任务 ID 精确删除，共 2 个，没有全局清理或删除数据卷。

最终部署后复查：已删除测试 Worker 的历史 HTML 交付仍可读取，资源哈希未变，匿名请求为 401；页面标题来自真实 HTML 的 title，实体解码后作为纯文本展示。线上编译产物检查通过。详细证据见 [evidence/native-html-report-20261112](evidence/native-html-report-20261112)。

清理了本次 r1/r2/r3 三个无容器引用的候选镜像，保留当前 r5、可回滚 r4 及原有服务镜像。当前回滚使用 r5/rollback 中的 Compose 备份，对应 r4 镜像。没有执行全局 Docker prune 或删除数据卷；本次镜像标签清理前后根分区可用字节均为 15,092,649,984，共享构建缓存仍保留，因此不声称回收了额外磁盘空间。
