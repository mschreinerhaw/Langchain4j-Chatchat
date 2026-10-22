# 图文报告检查状态及历史展示修复

> 2026-10-22 字体与展示补丁已更新：历史正文仍在原归档中保留，但页面不再展示“完整分析正文”“分析代码与执行状态”，也隐藏“图表分析”章节的重复说明。下方旧版验收中的“正文可见”描述的是此前版本，不代表当前页面行为。中文字体补丁不改写已归档 PNG；旧图如有方块，需要重新执行分析生成新产物。

## 联机定位与差异分析

截图对应父任务 `37a99a30-ddce-441e-a43a-a72cffcfa2ef`，Run `att-1-5b4b638a-1c7c-42c8-b238-906f2b6e0d8d`。原 Python 实际执行两次并归档：第一版检查含 PASSED/FAILED，第二版含 PASSED/UNRESOLVED。第二版保留五张图和 1,474 字符正文，但 VisualReportArtifacts 仅接受 PASSED/FAILED，触发 `Invalid computation check`，降级结果还遗漏了正文。

执行成功、产物协议有效、业务检查通过是不同状态。UNRESOLVED 代表尚无足够证据解决问题，不能强制改为 PASSED。复用原 Python Sandbox、ExecutionFeedback、Cline 上下文、任务事件、PG/Silo 归档及图文组件；不重新建设执行框架。

## 改动

- 公共报告生成指令明确 PASSED、FAILED、UNRESOLVED 三种检查状态。UNRESOLVED 使 SUCCESS 降为 PARTIAL，保留图、指标、正文和原检查说明；FAILED 仍使报告失败。未知值依然拒绝。
- 新增 artifactValidationStatus，区分技术协议不合法和业务检查不通过。技术失败反馈 VISUAL_REPORT_INVALID / OUTPUT_CONTRACT，原修复次数、预算、授权与同任务上下文继续生效；不把业务不确定性当成可通过改标签解决的错误。
- 报告降级仍保留经过路径脱敏和长度校验的正文。Java 记录拒绝任务、执行 ID 和异常类型，不输出客户数据或异常原文。
- 既有任务监控增加所有者校验的归档报告展示校验接口。核对 task/run、执行停止证明、代码哈希、结果哈希、manifest 哈希及每份产物哈希后，以当前展示校验器生成视图。不重新执行 SQL/Python/模型，不改写原任务、原代码、检查点和原证据。原图资产继续通过既有幂等归档接口提供引用。
- 历史旧版失败报告由组件根据已授权代码资产找回 run/task，读取重新校验视图；客户端再次匹配原 task/operation/code/result 身份。失败或越权时保留原失败提示，不伪装成恢复成功。
- 前端将 UNRESOLVED 显示为“尚未解决”，与“检查失败”和“分析程序检查通过”区分。

## 验证方式与边界

本地契约、H2 证据重放、策略及组件测试分别记录；它们不代替服务器真实 PG/Silo/MCP 归档验证和浏览器验证。历史报告重新校验限单行、结果 1 MiB、图产物单份 8 MiB、总 64 MiB。检查声明来自原 Python 程序，结构与文件完整性校验不认证业务结论。原第一版 FAILED 及第二版 UNRESOLVED 均保留。

## 部署及实际结果

实现提交 `a09051947`，完成门禁补充提交 `64849e712`，发布分支 `release/1.2-20261022`。部署于原服务器 `192.168.195.221:5221`，仅替换 API 镜像；配置、挂载与其他业务容器保持。发布目录 `/opt/chatchat-releases/1.2.0-20261022-visual-check-repair-r2`，备份引用在该目录的 `evidence/deployment.json`。

API 镜像 `runtime-os-api:1.2.0-20261022-visual-check-repair-r2` 为 743,682,233 字节，仍为一个应用 JAR 层。JAR SHA-256：`15cd9e9320557258100bbb6f5a606c3910ef6f9754db329072505a0008f1725f`。

| 验证范围 | 实际结果 |
|---|---|
| Java 相关回归 | 59 项通过，0 失败、0 跳过，包括原证据重放、manifest 篡改拒绝、跨用户拒绝、技术反馈及禁止 SDK 绕过完成门禁 |
| 前端相关回归 | 13 项通过，Vite 构建通过 |
| 原任务第一版归档 | 仍为 FAILED，3 项 PASSED、2 项 FAILED，保留 4 张图及 825 字符正文 |
| 原任务第二版归档 | PARTIAL，4 项 PASSED、2 项 UNRESOLVED，保留 5 张图及 1,474 字符正文 |
| 原证据与任务 | 19 条原证据 ID/revision/hash 未改变，原任务时间线与用量完全相同；没有新增模型/Python 执行或客户源查询 |
| 真实浏览器历史页 | 登录原产品，打开原会话，自动重新校验成功；五张图均解码成功，正文可见，两项检查显示“尚未解决”，无页面脚本异常 |

此外尝试了合成附件的真实模型故障修复演练：首轮测试描述触发现有多工作流规划保护，未进入 Coding；简化目标后的第二轮因模型响应被拒绝而终止，只发生一次模型调用，未进入 Python。两轮均不计为修复闭环通过，也没有调整预算或绕过授权/工作流。相应新技术反馈与最终门禁由本地测试覆盖；本次不能宣称新场景已经完成真实模型自动修复验收。

原会话现在刷新即可恢复展示。两项实际业务勾稽问题仍是“尚未解决”，本修复没有把数据问题改成通过，也没有重算客户交易数据。机器可读结果见[验收记录](evidence/runtime-visual-check-repair-20261022.json)。重新校验是已归档文件的展示投影，原始失败证据仍可审计；页面显示恢复不意味着这些未决业务问题已经解决。

## 增量部署包

服务器文件 `/opt/chatchat-releases/chatchat-1.2.0-20261022-visual-check-repair-hotfix.tar.gz`，8,481,131 字节，SHA-256：`44a9acd1a0751bef75ac4581335934437f281ae70506591c4907e4b03ff99e29`。

此包基于已经验收的 ASCII-recovery 安装及其 API JAR（SHA-256 `889be8ef5f7e59bd75f86f243d21f25538d6cf2a052f43e71bd285180ce8424c`），不是空机器首次安装包。包括 API 差分、部署工具、Docker 构建文件、文档和脱敏验收记录；不包含客户报告、截图、凭据、私有配置或基础镜像。

解包至 `/opt/chatchat-releases/` 下的新发布目录，按照包内 INSTALL.md 校验文件、重建 JAR、构建和部署。部署拒绝活动任务，并备份配置和持久状态。已真实解包校验所有清单哈希，由包内差分重建出与线上一致的完整 JAR。[包验收记录](evidence/runtime-visual-check-repair-package-20261022.json)单独保存；回滚命令在 INSTALL.md 中。

## 中文字体默认依赖与精简展示补丁（2026-10-22）

### 根因及改动

联机核对原两版生成代码，都将 `font.sans-serif` 设置为 `['DejaVu Sans']`，覆盖原镜像的中文字体候选列表。原镜像已经包含 Noto Sans CJK SC，因此仅重复安装字体不能解决该问题。

- 完整 Python Runtime Dockerfile 将 `fontconfig`、`fonts-noto-cjk` 列为默认系统依赖，并内置 `matplotlibrc`。构建时选择镜像实际拥有的 Noto 字体，要求必须有 CJK 字体；验证失败即终止构建。
- 默认 `font.family` 明确包含 CJK 和 Latin 字体，使仅覆盖 `font.sans-serif` 的历史写法仍能显示中文。SVG 使用字形路径，避免客户端缺字体再次出现方块。字体和配置均在镜像内，沙箱无需网络、安装命令或额外权限。
- 已发布环境升级使用 `fonts.Dockerfile`，继承经 ID 核对的原 Python 镜像，复用其中字体及全部 Python 依赖，离线修改配置并真实验证；基础镜像没有 CJK 字体时构建失败。完整新建镜像仍由完整 Dockerfile 安装默认字体。
- Cline 图文报告公共指令要求保留已发布镜像字体默认值，不下载字体、不强制替换为仅支持 Latin 的字体。
- 共用 `PythonVisualReport.vue` 移除正文重复展开区和代码/执行状态区，同时停止读取展示用源代码。名为“图表分析”的章节仅隐藏标题和重复说明，仍保留引用的图和表。新消息、历史消息使用同一组件；业务结论、指标、表格、检查与归档记录保留。

字体回退方式参见 [Matplotlib 官方字体说明](https://matplotlib.org/3.6.0/users/explain/fonts.html)。该补丁不阻止业务脚本显式配置字体；生成指令和构建检查针对默认配置及本次真实故障写法。

### 发布信息与验证

实现提交 `6b2fc7df661a3cbcb89ec53d860c01f88709ed04`，发布分支 `release/1.2-20261022`。

| 项目 | 当前值 |
|---|---|
| 发布目录 | `/opt/chatchat-releases/1.2.0-20261022-chart-fonts` |
| API 镜像 | `runtime-os-api:1.2.0-20261022-chart-fonts` |
| API 镜像 ID | `sha256:dc4b7a054220ea582f4cbc5d2e12997031a642273c10f10bca925742e742d285` |
| API 大小 | 743,682,105 字节，一个应用 JAR 层 |
| API JAR SHA-256 | `e9366adaf33956d0c72dea16fc12f60aac54e54aa4cf4c37094f391780124875` |
| Python 镜像 | `chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022` |
| Python 镜像 ID | `sha256:7c665132dd360b4f84a669a16b53de93c0ff142e1ecf8f56bef1a07d6346fdc8` |
| Python 大小 | 2,302,924,816 字节，比原 Python 镜像增加 28,662 字节 |
| 默认环境 | 原 asset/default 选择保持；MCP 已发布环境版本更新至 7，新任务动态解析新版镜像 |

Java 相关回归 37 项、前端相关回归 14 项全部通过，Vite/Maven 打包成功。旧镜像运行同一字体回归程序确实因缺字断言失败；新镜像在无网络、只读、非 root 容器内通过。真实现有 Managed Python Sandbox 也成功生成 PNG/SVG 和结果 JSONL：中文字形覆盖检查通过、无缺字警告、保留 DejaVu Sans 覆盖写法仍通过，PNG 27,932 字节，SVG 30,059 字节。实际下载并检查 PNG，中文标题、轴标签和负数正常。此测试使用合成数据，不调用模型。

真实浏览器登录并打开原历史会话，三个指定区域不显示、五张原图仍可加载、两项 UNRESOLVED 状态保持。PG/Silo/MCP 归档复核确认原 19 条证据、代码/结果哈希及任务时间线不变。浏览器图像解码成功仅证明归档可读取，不代表旧 PNG 方块已被修正。

本次仅替换 API 和已有代理；MCP、Node/Cline 及其自动恢复设置沿用原部署。默认 Python 环境通过原 MCP 管理 API 取消发布、修改镜像、重新发布，保留原资源限制、网络策略和依赖。进行此操作前必须确认活动任务为零，原镜像保留用于回滚。禁止直接修改数据库或静默重标记原镜像。

### 回滚与历史产物边界

API 回滚使用本次发布目录的 `scripts/deploy-execution-feedback-stage.py rollback --api-only --release-dir /opt/chatchat-releases/1.2.0-20261022-chart-fonts --revision 6b2fc7df661a3cbcb89ec53d860c01f88709ed04`，空闲任务窗口执行；备份引用见 `evidence/deployment.json`。

Python 环境回滚经原 MCP 管理 API，先取消发布，再将同一环境的 `dockerImage` 改回 `chatchat-python-runtime:3.11-v1.2-cjk-20261021`，保留其余配置，再重新发布。版本号继续递增，不回退或篡改历史版本。`environment-before.private.json` 仅在服务器保留，不进入部署包，它记录的是中间版本 6；原始版本 5 的镜像即上述 CJK 镜像。

已归档 PNG 是不可逆的像素产物，更换镜像无法恢复其中已经丢失的字形。需要以新版默认环境重新执行原分析，生成新代码/执行记录和图表；原图、原检查与原证据继续保留。本次没有重跑客户分析或伪造历史图像修复。

### 字体补丁离线包

服务器文件 `/opt/chatchat-releases/chatchat-1.2.0-20261022-chart-fonts-linux-amd64.tar.gz`，801,656,737 字节，SHA-256：`6f6662f2ab758b077673a5c6a3784953da67e695b1310ac049fa688f08880edf`。

包含完整 Python 字体镜像、API 差分、发布工具、文档、验收记录和 INSTALL.md，不含凭据、私有配置、备份或客户图表。适用于已有 Runtime OS 1.2 安装，API 差分要求基线 JAR 为本文件上方 visual-check-repair-r2 版本；不用于空机器首次安装，也不包含原 Java/API 基础镜像。字体构建无需改动原 Python 依赖，完整离线 Python 镜像占据包的主要体积。

已真实解包并校验 13 份有效载荷，通过包内差分重建出完整目标 JAR、加载包内 Docker 镜像，再以只读无网络非 root 容器运行真实字体绘图检查，全部通过。详见[字体补丁验收](evidence/runtime-chart-fonts-20261022.json)及[包验收](evidence/runtime-chart-fonts-package-20261022.json)。文档中的原旧版增量包仍可用于其对应基线；部署本次补丁应使用此新包。
