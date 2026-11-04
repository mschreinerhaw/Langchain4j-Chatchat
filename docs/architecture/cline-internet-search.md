# Cline 受控互联网工具

模型管理保存 `internetSearchEnabled` 和 `searchProviderIds`（最多 16 项）。API 数据库的 `platform_model_internet_policy` 默认关闭，`platform_model_internet_provider` 按顺序保存多选引擎；保留 `provider_id/searchProviderId` 作为旧客户端及单引擎合同的兼容入口。旧单选记录读取为单元素列表，未设置新字段的旧客户端保留原行为。保存立即生效，无需发布模型。只有已发布且启用的聊天模型可以执行联网工具。删除模型会撤销联网授权；再次创建同名模型不会继承开启状态。

授权为模型开关、所选 Provider 集合、Agent 的 `internet_search` 只读资源权限和当前用户显式资源授权的交集。任务冻结模型和每个 Provider 的 ID、名称、版本，执行前后重新检查当前授权。工具可携带 `provider_id`，Worker 枚举只包含当前任务允许的引擎；省略时使用第一项。API 拒绝集合外 ID，派发前移除该工具参数并绑定服务端 Provider 版本。任何已选引擎被停用、删除或变更后，旧任务不能继续联网；集合变更、关闭后再开启均递增模型版本，旧任务不会复活。保留来源的查看只读取当次已归档快照，仍校验当前用户资源权限，不重新联网。

调用路径：Cline 原生 SDK 工具 `web_search` / `web_fetch` → 已有签名 Coding 回调 → API 当前权限与预算 → 已有 MCP 控制面 → News 内原子 HTTP 适配器。MCP SPI 注册 `internet_web_search` / `internet_web_fetch` 为只读、非普通 Agent 可直接调用的工具。执行参数不接受用户、租户、密钥或任意控制面地址；这些由服务端任务合同提供。搜索内容和网页正文始终标记为不可信来源数据。

News 复用其现有 HTTP、Jsoup、数据源、内部 HMAC 认证和腾讯签名实现，不新增推理 Agent。搜索关键词、读取对象、轮次、交叉核验和报告均由 Cline 决定。旧 News 采集、去重、索引、Temporal、重试和非模型的分类/摘录继续保留。Runtime 经旧 MCP 工具链访问时强制禁止旧的外部搜索回退，模型参数不能重新启用；其他旧调用方保留兼容路径。

Provider 中央配置存于 News 的 `internet_search_provider`。模型管理的“检索引擎”入口支持新增、编辑、启停和删除；名称、URL、API Key 和调用限制均为可维护数据。接口协议适配腾讯 WSA、Brave、Tavily，同一协议可维护多项不同实例。新实例由服务端生成 ID，不在启动时创建固定引擎。既有数据库条目保留；模型侧只列出已配置并启用的引擎供多选，失效的旧勾选显示为可移除项。

API 管理入口为 `GET/POST /api/v1/platform/models/search-providers` 和 `DELETE /api/v1/platform/models/search-providers/{id}`，继续由现有数据库 API 权限策略控制，通过已认证的 MCP 控制面转发至签名 News 内部接口。状态列表返回名称、Endpoint、状态和限制，不返回密钥、Secret ID 或密文。POST 字段为 id（新建可省略）、kind、name、endpoint、apiKey、secretId（仅腾讯）、enabled、timeoutMillis、requestsPerMinute、maxTaskCalls、maxDailyCalls。空密钥保留同类型原值；腾讯的 Secret ID 与 Key 可分别更新。凭据用现有 AES-GCM 加密，保存或关闭表单后清空输入，不进入模型合同、工具定义和代码环境。删除清空凭据并保留递增版本的墓碑和审计记录，重启不恢复固定项，重新使用同一 ID 也不能复活旧任务。HTTP Provider 必须为 HTTPS，调用时只连接已验证的公网 IP，绝不跟随携带密钥的 Provider 重定向。

`internet_search_request` 保存任务/用户/Agent/Provider、调用摘要、耗时、响应字节数、状态和结果；`internet_search_quota` 原子限制每分钟、每日和每任务尝试次数。幂等调用不重复派发；中断后结果未知时明确返回 `REMOTE_OUTCOME_UNKNOWN`。不自动重试付费请求。Provider 返回实际 credits 时记录原值，没有价格依据时不编造金额。web_fetch 同样占用工具与 Provider 访问配额。

网页读取仅支持公开 HTTP/HTTPS、80/443 端口、最多三次重定向、1 MiB 压缩输入与解压内容、最多 20000 字符正文以及总时间上限。所有 DNS 答案必须为公网，连接固定到该组答案并保持原 TLS 主机校验；每次重定向重新检查，禁止 HTTPS 降级。拒绝本机、私网、链路本地、元数据、CGNAT、文档和特殊 IPv6 范围。robots.txt 按 Agent 分组和最长 Allow/Disallow 规则检查；访问失败保守拒绝。不会执行 JS、携带登录 Cookie，或绕过登录、验证码、付费墙。正文提取可能不完整，结果显式记录截断和来源类型。

Worker 仍仅能访问 API 专用回调监听器，Docker 网络 internal=true、IPv6=false、非 root、drop ALL capabilities；部署脚本验证这些实际属性并更新主机防火墙。Python 仍为 `--network none`。开启搜索不改变任何代码执行网络权限。网页数据通过既有不可变输入快照交付给 Python，来源与图表复用报告证据链。

社区 Skill 来源为 [Tavily Skills](https://github.com/tavily-ai/skills)，固定提交 `b282c623b12b3c58fd279ed072f7e67b693e4654`，MIT。保留原文件与许可证，适配包 `runtime-internet-research` 只在有联网资源的任务中出现，保留精确查询、原始页面和独立来源核验方法；删除 CLI、OAuth 和外部研究 Agent 的执行建议。Skill 无权增加工具或网络权限。

部署使用完整 Maven JAR 和离线测试的 Worker 包；`prepare-cline-internet-stage.py` 验证哈希并预建镜像，`deploy-integrated-runtime.py` 备份 API/MCP PostgreSQL、停写后的 News H2、配置与原容器，保留会话、资源和模型配置。新表为增量创建，默认模型联网关闭。部署失败自动恢复本次替换的原镜像和容器；正常回退必须选择兼容当前资源配置与任务合同的版本，保留新增表和业务数据，不用旧数据库备份覆盖新会话。首次上线前的版本不认识 `internet_search` 资源和 `internetSearch` 合同字段，不能直接降级后宣称新任务历史仍可读取；需要先补齐兼容读取能力或制定迁移方案。紧急停止新联网调用优先关闭数据库中的模型联网开关。只有确认数据库损坏且获维护授权后才恢复数据库备份。

动态 `calculationInput` 与初始任务输入使用同一不可变快照和计费机制。`input_read` 接受初始输入或本任务已登记的数据版本；模型无法传入任意快照、路径或其他任务的引用。工具额外返回实际挂载文件路径，避免推测分片名称。所有 Python 执行仍绑定该次执行的完整输入版本与文件哈希。

最终发布层同时校验初始来源、当前可读知识引用，以及 Coding 服务端回执中已登记的 `authorizedDatasetReferences`。该清单由本任务数据版本生成，不能从模型报告自行补充。Python 执行后只能沿实际产物与 `artifact_read` 审阅路径完成；文字提交不能替换失败执行或丢弃图表。

验收必须另外提供真实 Provider、网页、Python 与生产页面证据；单元测试和故障注入不替代完整验收，具体结果另记。
