# Skills 选型与加载记录

P0 提交 `a73763a1a` 已通过 198 项 Java 专项、88 项前端及 5 项 Cline SDK 探索测试后，进入本阶段。这里记录方法指导的兼容性，不把技能文件当成数据访问授权。

微软官方仓库核验固定到 `7a2367fc8a7d834c15b5ee8543a3d86c3003608f`，许可证为 [MIT](https://github.com/microsoft/skills/blob/7a2367fc8a7d834c15b5ee8543a3d86c3003608f/LICENSE)。候选的原路径、内容 SHA-256 及兼容结论见 [核验记录](runtime-resource-exploration-20261104-evidence/microsoft-skills-review.json)。

| 实际技能 | 依赖与选型结论 |
| --- | --- |
| [foundry-iq](https://github.com/microsoft/skills/blob/7a2367fc8a7d834c15b5ee8543a3d86c3003608f/.github/plugins/foundry-iq-skills/skills/foundry-iq/SKILL.md) | 官方名称并非 foundry-iq-knowledge-bases；兼容性声明为 Azure。参考其检索、引用与知识源边界思路，不作为本平台 OpenSearch 执行技能加载 |
| [azure-search-documents-ts](https://github.com/microsoft/skills/blob/7a2367fc8a7d834c15b5ee8543a3d86c3003608f/.github/plugins/azure-sdk-typescript/skills/azure-search-documents-ts/SKILL.md) | 面向 Azure AI Search TypeScript SDK，依赖 Azure Search 服务和独立凭据；不能直接操作 OpenSearch |
| [azure-ai-document-intelligence-ts](https://github.com/microsoft/skills/blob/7a2367fc8a7d834c15b5ee8543a3d86c3003608f/.github/plugins/azure-sdk-typescript/skills/azure-ai-document-intelligence-ts/SKILL.md) | 面向 Azure Document Intelligence，依赖其服务端点及授权；不将其当作本地 PDF 版式能力 |

已有微软原生包未改写，也未追加 Azure SDK 或服务依赖。保留现有原生知识技能，补充 `financial-news-analysis`、`financial-market-analysis`、`evidence-based-answering` 三个 Runtime OS 技能。它们指导当前已有受控工具的使用，区分新闻发布时间与采集时间、行情截止与单位、事实与未证实因果，以及设计方案与测试证据。

继续使用同一 `SKILL.md`、catalog.json 与 skill_read 机制。任务的签名执行上下文携带实际已授权资源 ID；对应技能只进入该任务的元数据目录，正文由 Cline 按需读取。未进入目录的原生技能不能通过猜测 ID 读取。未启用资源探索的 Coding Agent 维持原来的 6 项默认目录，新增 4 项资源技能不会常驻其上下文。Java 的内置目录保存元数据，不预读技能正文；业务领域技能和原生微软目录仍沿现有机制处理。

本阶段也复用原有来源弹窗与报告渲染：新闻显示发布、采集时间和原始链接；金融显示实际截止时间、已采集数据声明及原始记录表格；重复工具进度按资源折叠。没有新增独立报告产品或资源管理工作台。
