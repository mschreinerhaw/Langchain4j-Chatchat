# Cline 联网搜索、执行隔离与报告实测

验收日期：2026-11-04～05（Asia/Shanghai）。环境：`192.168.195.221:18100`。基线 `7cf22fa71`，分支 `feature/cline-internet-search`。先完成[调用关系与出口审查](../audits/cline-internet-search-audit.md)，实现边界见[架构说明](../architecture/cline-internet-search.md)。

本目录只提交公开资料、脱敏回执、哈希和页面截图。认证文件、Provider 凭据、数据库备份、原始 SDK checkpoint、客户数据及完整服务日志均未提交。请求 fixture 是测试输入，不是模拟搜索结果或模拟行情。

## 真实执行

| 场景 | 实测结果 | 证据与边界 |
|---|---|---|
| 公开搜索 A3 | SUCCEEDED | 真实腾讯 WSA 搜索与 Python 官方网页读取；任务 `06805e38-42a5-33c2-b092-9dc64bedcba3`，报告历史可重开。官方多个页面不等于多个独立机构来源。 |
| 公司外部资讯 + 内部行情 B | 技术交付成功，质量复核发现问题 | 真实 `web_search`、`web_fetch`、`news_search`、`news_get_article`、`market_search`、`market_get_snapshot`；任务 `ab601e56-81db-36fc-94e9-ebe883f2b7ba`。动态页面只返回骨架，却被报告引用为公司信息依据；保留该缺陷记录，未将此报告作为业务全通过证据。 |
| 公司综合报告 B2 | PARTIAL，历史展示成功 | 任务 `87007f96-1e9d-3316-8130-485a471a8893`；实际使用内外部来源，保留索引 summary 与原文区别、具体证券日期和成交量单位缺口。PARTIAL 不改写为 COMPLETE，不据此宣布原金融业务验收全部通过。 |
| 月度公开市场图表 C7 | SUCCEEDED，实际页面及历史图表通过 | 任务 `be4ad177-b81d-4e2b-8f8b-95ad50521170`，真实读取 ECB CSV；Cline 从不可变文件解析 23 条观测，三次 Python 执行（前两次产物校验失败，第三次通过），保留全部版本回执。 |
| 图表回归 C8 | CANCELLED，不计通过 | 五次 Python 执行完成但引用/可选布局未通过；由验收执行者取消自己的重复失败任务，保留全部回执。 |
| 图表回归 C9 | FAILED，定位并修复发布层缺陷 | Coding 已完成，但发布层漏用服务端 `authorizedDatasetReferences`，误拒本任务动态快照。已补充精确授权回执的正反测试，未按字符串前缀放宽权限。 |
| qwen3.8-max 对照 C10 | CANCELLED，不计图表通过 | 新模型独立授权后完成真实搜索和网页读取，等待超过 12 分钟未进入 Python；停止可选对照并关闭其模型联网开关。 |
| 最终版本图表回归 C11 | SUCCEEDED，页面与历史图表通过 | 最终 R12 新任务 `4fdcc197-c1d6-4d9b-9e82-96a28df4f8c9`；5 次模型调用、2 次 Python 执行（首次字段访问错误，在原任务精确修复后通过），真实 PNG 1335×700。 |
| 搜索开启时 Python 出口 D-on | SUCCEEDED | 任务 `bad28086-101f-3217-95fd-bbfe24737452`，真实 Python 一次执行：DNS errno -3；公网及内网 TCP errno 101；HTTPS 解析失败。搜索权限未扩展 Sandbox 网络权限。 |
| 模型关闭 D | SUCCEEDED | 任务 `8096c7eb-b2ec-33ea-95e1-d3d97f1bfab2`，新合同无 web_search/web_fetch；真实 Python DNS/HTTPS 解析失败，公网和内网 TCP errno 101。三次模型调用、两次 Python（首轮输出格式错误后修复）。 |
| 旧任务动态撤权 | 预期拒绝，任务 PARTIAL | `3968c9ae-e9fc-3db5-9fd9-2f56bebaa2c1` 在实际 web_search 成功后关闭开关；冻结 revision 3 的旧任务随后实际调用 web_fetch，被服务端拒绝；数据库已为 revision 4 / disabled。Cline 明确未读到正文，没有绕过权限。 |
| Provider 故障 E | 真实 HTTP 与配额测试通过，部分场景只有单元测试 | 官方腾讯接口使用刻意无效凭据返回 `PROVIDER_REJECTED_REQUEST`；延迟 HTTP 测试返回 `HTTP_TIMEOUT`；追加调用触发 `TASK_SEARCH_QUOTA`。测试 Provider 已停用，正常 Provider 未替换凭据。 |
| News 兼容 F | 真实采集与查询通过，部分来源仍失败 | 来源 24 手动采集发现 160 条、重复 160 条、失败 0；另有定时采集成功。37 个来源，索引记录从 25042 增至 25206，真实资讯检索成功。来源 1/2 存在金融采集依赖失败，不声称所有来源健康。 |

### 图表证据

[最终版本图表可见页面](cline-internet-search-evidence/C11-browser-runtime-chart-visible.png) · [图表本体截图](cline-internet-search-evidence/C11-browser-runtime-report-chart.png) · [关闭联网后的历史重开](cline-internet-search-evidence/C11-browser-runtime-history-reopened.png) · [实际完整报告](cline-internet-search-evidence/C11-report.md)

C7 覆盖 `2026-10-05`～`2026-11-04`，币种 USD、计价币种 EUR，23 条观测。使用归档来源独立重算，报告首末值与变化百分比匹配；实际执行程序字节哈希与执行回执相同。见[复算记录](cline-internet-search-evidence/C7-independent-calculation.json)、[实际执行代码](cline-internet-search-evidence/C7-executed.py)、[归档 CSV](cline-internet-search-evidence/C7-source.csv)。

最终 C11 使用同一官方序列重新检索、获取和执行，复算亦通过，见 [C11 复算](cline-internet-search-evidence/C11-independent-calculation.json)、[C11 实际执行代码](cline-internet-search-evidence/C11-executed.py)与 [C11 归档 CSV](cline-internet-search-evidence/C11-source.csv)。截图只捕获当前会话视口，不包含无关历史侧栏；报告内容没有注入或替换。

技术结构验证为 `INCONCLUSIVE` 时，含义是业务正确性未由系统证明，不能改称业务验证 PASSED。模型生成的叙述仍需对照来源审阅，例如参考时点等额外说明不能仅凭记忆认定。截图使用真实页面滚动到图表后拍摄；没有注入报告内容、替换模型输出或使用伪造图片。

## 网络与权限

实际 Worker 探测中，公网 IP、元数据地址、宿主内网、普通 API、控制面均不可达，仅专用回调端口可达；公共 DNS 返回 `EAI_AGAIN`。最终容器配置及防火墙探测见 [部署与隔离记录](cline-internet-search-evidence/deployment.json)、[出口探测](cline-internet-search-evidence/worker-network-enabled.json)。

Python 使用独立 `--network none` 容器。搜索由 News 服务端校验并固定公网 DNS/IP、逐跳校验重定向、限时限量读取，与 Worker/Python 出口分离。模型开关、Provider 版本、Agent 资源和用户显式资源授权在服务端逐次复核，不能通过伪造工具参数放宽。

最终数据库状态见[模型策略记录](cline-internet-search-evidence/model-policy-final.json)：qwen3.7-plus 为关闭 / revision 4，qwen3.8-max 为关闭 / revision 2。模型页面实拍只有两个控件，见[关闭状态](cline-internet-search-evidence/model-search-disabled.png)。撤权前后时序及真实工具拒绝见[撤权回执](cline-internet-search-evidence/revocation-boundary.json)。该接口对外返回通用 `Invalid Coding callback binding`，不是 Provider 超时；没有将其虚构为特定 HTTP 403 或专用权限错误码。

真实 SSRF 拒绝覆盖 loopback、宿主私网与云元数据地址。真实 httpbin 重定向尝试被页面访问规则拒绝，没有走到重定向校验，故不算真实重定向安全通过；混合 DNS、重定向与 TLS 降级由单元测试覆盖，未进行受控域名 DNS rebinding 的真实演练。

## 测试分类

- 真实 Cline、MCP 控制面、腾讯 Provider、公开 HTTP、Python 容器、Runtime OS 页面与历史：见上表。Cline 工具经已有签名 Coding 回调及 MCP HTTP 控制面派发；不是另建 JSON-RPC 会话，也没有新增独立 Web Search Agent。
- Node/原生 SDK：188 项通过，同时在 Windows 和 Linux 离线镜像运行，含真实资源工具配置下的预算末段修复回归。单元测试中的 gateway/model doubles 不计入真实检索或真实绘图。
- API 最近一次针对性 reactor 验证：60 项，59 通过、1 跳过（本地未提供 `coding.sqlglotPython`，跳过 SQLGlot 集成用例），完整 package 成功。
- 此分支已有 News 27 项、MCP 许可过滤 20 项、模型与资源配置/Java dispatch 等针对性验证通过。前端针对性测试与构建通过；未把早期全量前端测试运行冒充为最终全量重跑。
- 只有腾讯 Provider 具备真实有效凭据并完成搜索；Brave/Tavily 实现有单元与故障验证，未宣称使用有效生产凭据完成真实验收。

### 保留的失败与修复

没有删除或重标历史失败任务。早期 Agent 配置保存遗漏 Internet 资源、Java dispatch 工具白名单遗漏、许可菜单未注册均由真实失败定位后修复。BC3/BC4 分别保留代码锚点与动态输入路径失败；BC5 三次 Python 进程成功但产物校验失败，最终任务 FAILED，不算报告成功。

C8/C10 是验收者取消的自建测试。已确认 Worker 无活动会话、Python 无活动进程；取消期间未确认的模型操作仍按既有机制保留 reconciliationRequired/预算预留，没有伪造停止计费证明，也未自动重放付费调用。这些账本待核验状态不计入成功任务。

本轮修复还包括：动态 `input_read` 可读取本任务已交付快照；精确代码锚点保留原始字节；引用声明错误不再遮住图文校验详情；动态页面骨架与索引摘要的来源规则；Python 执行后禁止文字提交替换其产物；预算末段保留 Python 修复与 artifact review；发布层只接纳 Coding 服务端回执实际登记的动态引用。全部保留原授权、不可变输入与精确补丁校验，没有改用容错字符串匹配或提高数据权限来通过验收。

## 复现

目标环境已发布 `internet_research_acceptance_20261105` 验收配置，仅使用现有 RoleChat 类型，无绑定私有文档。其他环境需使用自身已授权的 Agent ID。模型页面只含开关与 Provider 选择，密钥与 Endpoint 仍由服务端集中配置。

通过环境变量提供登录凭据，不写入命令历史或仓库；可以使用同一次登录生成的私有 `REPORT_AUTH_FILE`，避免并行验收重复登录使旧会话失效。

```powershell
$env:REPORT_REQUEST_FILE='scripts/fixtures/cline-internet-search/market-chart.json'
$env:REPORT_OUTPUT='target/internet-replay'
$env:REPORT_REQUIRE_CHART='1'
node scripts/verify-cline-report-browser.mjs
```

其他 fixture 为 `public-search.json`、`company-internal-public.json`、`sandbox-egress.json`。CLI 使用 `scripts/verify-cline-report-live.mjs` 保存实际任务、工具事件、执行回执及结果摘要。验收产物目录包含私有认证文件，不能整目录提交；本报告链接的是白名单脱敏导出。

部署与回退说明见 [deployment 文档](../architecture/cline-internet-search-deployment.md)。
