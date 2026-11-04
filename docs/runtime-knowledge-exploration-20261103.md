# Runtime OS / Cline 授权知识探索 P0

基线 `743a01304`，工作分支 `feature/runtime-knowledge-exploration`。本次沿用现有 API、Cline worker、OpenSearch、文档原文存储、资源授权和 Runtime Evidence Store。

## 源码与环境审查

| 链路 | 已有能力 | 本次补齐 |
| --- | --- | --- |
| 原文 | `SearchService`、`DocumentIndexRegistry`、`PerDocumentIndexService` 已有版本、生命周期与授权读取 | 工具返回前重新读取当前原文；同版本原地修改也使旧引用失效 |
| 权限 | `SkillExecutionScopePort` 从数据库计算 Agent 绑定与用户/角色资源交集；无绑定不代表全库 | 每次工具、模型回合、报告交付及历史引用重新计算；拒绝模型身份、角色、索引和 DSL 参数 |
| 检索 | `OpenSearchDocumentIndexService` 已有 BM25、章节分块、版本字段 | 知识专用只读查询强制前置 tenant + document IDs 过滤；空集合不发查询 |
| 实际索引 | API 使用 `runtime_os_stage_documents`；原始项目资料还在既有 `chatchat_documents_dev` | 配置 `chatchat.search.open-search.knowledge-index-names` 读取既有来源索引；复用同一连接和凭据，不创建新索引 |
| Cline | 签名回调、合约哈希、租约 fencing、预算、工具白名单、多轮 SDK 循环 | 增加知识回调与能力协商；角色问答不再由 Java 预选知识文本 |
| Evidence | 已有任务隔离、内容哈希、来源边、归档与结构校验 | `KNOWLEDGE_DOCUMENT_REFERENCE`、`KNOWLEDGE_CHUNK_REFERENCE`，报告来源边包含实际知识证据 |
| Skills | 镜像目录、原生 `SKILL.md`、目录元数据与 `skill_read` 按需加载 | 新增 `opensearch-knowledge-exploration`；仅知识任务目录可见，保留现有微软原始包 |
| 展示 | 自然语言问答、阶段进度、折叠工作台、Markdown 报告 | 多次知识工具聚合为一个探索活动；点击引用后实时核权并显示原文分块 |

## 工具与授权

模型函数采用下划线，以兼容模型供应商的函数名约束：`knowledge_search` 对应业务协议中的 `knowledge.search`，其余同理。工具包括 search、get_document、read、list_sections、related。版本比较由模型多次 search/read 完成。

Java 从受信任务合约取得 tenant、user 和 Agent；`document_type` 只是业务筛选条件。索引名只接受管理员配置的明确名称，禁止通配符、路径和任意 DSL。每次召回前注入当前文档集合，并再次使用原文、分块序号、文本和版本核验命中；过时索引片段不会交给 Cline。

知识任务具备独立的只读知识工具权限，无 Python 执行权限。现有 SQL Studio、Python 任务仍沿原有合约执行。工具调用使用已有 journal reserve/start/stop-proof/settle，受原有 deadline、调用数与输出预算约束。

引用同时包含文档 ID、版本号、包含原文与相关元数据的版本哈希、章节、分块和精确文本哈希。Evidence Store 保存定位信息而非原文副本。报告须使用实际交付的证据引用，并在正文放置对应引用链接；这证明来源与定位有效，不等于 Java 已认证每个自然语言结论。

撤权、删除或原文更新后，已有任务不再复用之前的披露上下文继续模型调用或提交报告；需要新任务重新取得当前证据。历史 JSON、引用原文、报告下载以及两个 SSE 回放入口也执行校验。新知识任务不把旧会话摘要作为当前文档证据。已下载到用户设备的副本不属于服务端重新披露。

上述历史检查针对本次新链路记录了 Knowledge Evidence 或来源任务的结果，不回填旧链路中缺少来源元数据的历史答案。历史披露校验独立于 Cline 执行开关：执行服务停用、无法重新核权时仍拒绝旧知识结果，普通无知识来源的结果不因此被拒绝。

当前历史 JSON 采用保守的整次响应拒绝：一个返回视图包含失效知识结果时会返回 403，而非逐行改写历史审计内容。调用方可新建任务重新探索；包含过期结果的混合历史列表可能因此暂时不可读。这一体验限制不能解释为已完成逐条历史脱敏。

## 范围与事实边界

P0 使用 BM25。请求 `mode=hybrid` 会明确返回 `mode_used=bm25` 和限制，未声称执行向量融合。related 是词法相关候选，不伪造文档引用关系。当前原文索引没有可信页码；返回 `page=null` 与不可用说明，前端不补造页码。目录定位来自现有 `TextChunker`，不是重新解析 PDF 版式。

技能指导区分设计承诺、部署配置、操作说明与实际执行测试。备份方案存在不能证明恢复能力已经演练或满足 RTO/RPO；证据不足时应明确说明缺口。

微软官方现有 [Foundry IQ 技能](https://github.com/microsoft/skills/blob/main/.github/plugins/foundry-iq-skills/skills/foundry-iq/SKILL.md) 标注 Azure 兼容，包含知识库生命周期及查询工作流。它是参考来源，不能作为 OpenSearch 执行工具直接装配。现有微软 Skills 原文未改写，也未引入 Azure 服务。[OpenSearch Hybrid Search 文档](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/) 描述的融合流水线属于后续 P1；本轮未配置向量模型与融合管道。

## 验证与发布

最终本地 Java 专项 **182 项通过**（API 164、知识模块 10、角色问答 8），Node **167 项通过**，前端专项 **54 项通过**并完成生产构建。另完成 SQL Studio 前端 **180 项**、Python 报告展示 **61 项**回归。测试覆盖真实 H2 journal/Evidence、授权集合预过滤、索引伪造与陈旧命中、同版本更新、撤权/删除、历史引用、SSE 阻断、模型参数越权、SDK 多轮查询/阅读/改写和既有问答/分析/资产链路。合成测试不能替代真实文档答案验收。

真实索引审查确认 11 份验收文档的 725 个分块均与现有 Java 分块器生成的当前原文一致。此前的数据编织文档漏检源于查询强制 `standard`，而原索引使用 IK 分析器；知识查询现改为遵循各索引的 `search_analyzer`，不修改原文或重建索引。精确名称对照中，目标文档由前 10 位均未出现变为首位命中。

展示覆盖普通 Markdown 与实际发布的动态报告两条路径，均通过同一受控接口打开引用原文。结构化来源列表保留经过 Java 核验的标题、版本、章节与引用定位，不把原文副本塞入来源卡片。

发布脚本：`scripts/package-knowledge-exploration.mjs`、`scripts/prepare-knowledge-exploration-stage.py`。叠加到运行中基线 JAR，核验无关条目字节不变；在现有 worker 基础镜像内再次离线运行 167 项测试后才允许替换服务。保留原容器、数据库备份与网络策略回滚文件。

联机脚本：`scripts/verify-knowledge-exploration-stage.py`。包含 50 道真实项目文档问题，覆盖直接问答、多文档归纳、版本/设计与验证证据、无依据问题及权限拒绝。使用单独验收账号、专门只读 Agent 和显式文档授权，不修改现有用户或业务 Agent。

生命周期脚本：`scripts/verify-knowledge-lifecycle-stage.py`。只创建和删除带随机前缀的专用合成文档，测试撤权、恢复授权、同版本更新、同会话重新获取原文及删除后残留索引。独立无文档授权账号用于隔离检查。验收账号的认证材料、原文和完整回答仅留在目标环境权限为 0700 的发布目录，仓库证据文件不包含这些数据。

环境入口为 `http://192.168.195.221:18100`；API 仍绑定主机回环 `18180`。最终发布 API 与统一 worker 标签均为 `knowledge-exploration-20261103-r8`，JAR SHA-256 为 `209a1ccf5a4a6c36633b6950f69165b525c9b4ce1d461800434d3a7e30c7ce2a`。实现提交为 `89acd56bc119b13c5515b712f77d0814d45ff0ef`，发布目录为 `/opt/chatchat-releases/knowledge-exploration-20261103-r8`。保留目录下的 `backup` 和原容器用于回滚，没有新增数据库迁移或独立知识服务。部署核验确认无关容器、会话内容及消息索引计数未改变，详见 [部署记录](runtime-knowledge-exploration-20261103-evidence/deployment.json)。

既有索引配置使用显式启动参数：

```text
--chatchat.search.open-search.knowledge-index-names=runtime_os_stage_documents,chatchat_documents_dev
```

该参数经过 Spring Binder 回归，并同时写入部署 Compose。不能只依赖带下划线的环境变量拼写来推断配置已生效。检索审计日志只记录索引名、授权文档数、候选数、陈旧候选数和交付数，不记录原文和凭据。

源码构建前端后再执行发布打包脚本。静态构建产物遵循仓库现有忽略规则，部署包包含完整生产构建；源分支保留 Vue/JS/CSS 源码。打包记录相对审查基线的已提交、暂存及未暂存源文件哈希。

50 题真实文档验收已完成：**42 SUCCEEDED、7 PARTIAL、1 VERIFICATION_ERROR**。测试使用环境已发布的默认模型 `qwen3.7-plus`、并发 2、正文上限 600 字；预期来源仅供事后评估，不发送给模型。共解析 197 个来源引用。20 道直接问答中 19 道命中预期来源（95%），这是来源选择指标，不能当作答案正确率或事实性引用覆盖率。完整 [问题集](runtime-knowledge-exploration-20261103-evidence/questions.json)、[执行记录](runtime-knowledge-exploration-20261103-evidence/acceptance-summary.json) 与 [统计结果](runtime-knowledge-exploration-20261103-evidence/acceptance-metrics.json) 保留失败样本。

包含所有尝试的端到端 P95 与直接问答 P95 均为 **168.961 秒**，包括排队和轮询开销，**未达到 10 秒目标**。D06a 在 420.660 秒验收期限后请求取消；诊断发现模型反复复制错误的证据 ID、遗漏正文引用，Runtime 拒绝了这些报告，未将其作为有效答案交付。不能将该样本排除后宣称全量通过。后续需优化模型证据 ID 使用可靠性、上下文和工具调用开销；本轮没有通过放宽引用校验来换取成功率。

50 题运行在 r7；最终 r8 仅补强关闭执行服务时的历史披露保护及字面引用语法识别。检索、知识工具/报告流程、角色问答交接、Node 模块及前端资源均与验收版本字节一致，见 [版本等价核验](runtime-knowledge-exploration-20261103-evidence/benchmark-compatibility.json)。[源码核验](runtime-knowledge-exploration-20261103-evidence/source-attestation.json) 确认最终发布包的 28 个运行源码文件与实现提交一致。新增保护另有关闭执行功能的 Spring 上下文测试覆盖。

撤权、恢复授权、同版本更新、同会话更新/删除及残留索引的 [11 项生命周期检查](runtime-knowledge-exploration-20261103-evidence/lifecycle-summary.json) 均通过。最终 r8 上再次验证：授权用户引用返回 200，同租户、同 Agent 但无文档授权用户引用返回 403，原文接口业务码 404，见 [隔离记录](runtime-knowledge-exploration-20261103-evidence/isolation-summary.json)。[浏览器检查](runtime-knowledge-exploration-20261103-evidence/browser.json) 使用生产构建和真实引用接口，覆盖普通消息与实际已发布报告中的引用点击；注入展示消息属于 UI 测试夹具，未冒充完整用户端对话测试。

业务示例 M01 的人工抽查确认：答案区分 LiveData 备份操作、LiveBOS 模型级导入导出与恢复演练证据，明确不能据此证明系统恢复能力已验证。这只是一项抽查，**事实性回答引用覆盖率、引用支持率 ≥95% 及整体答案正确性尚未认证**，仍须逐题对照原文审查。P1 向量融合及 P2 PDF 页码、复杂版式与高级版本对比不属于本次已交付能力。
