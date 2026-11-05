# Cline 原生 Skills API 与迁移依据

调查日期：2026-11-06。当前安装五个 Cline 包均为0.0.93；npm `latest` 的 agents/core/sdk 均为0.2.0。实际下载三个稳定版tarball并验证registry SHA-512完整性，再核对公开类型；没有把GitHub main或nightly当成已发布稳定版。版本、类型hash及公开导出实测见[SDK调查证据](../testing/evidence/cline-native-skills-20261106/sdk-api-audit.json)。

结论：保留现有 `@cline/agents`，组合 `@cline/core` 已公开的完整原生组件即可完成接入，无需升级SDK或迁移整个ClineCore。原来的Parser集成不能算原生生命周期；本轮实际走包发现器、配置服务、原生Skills执行器、原生工具和原生文件读取。

## 六项能力

| 能力 | 0.0.93公开API与实际接入 | 0.2.0核对 | Runtime边界 |
|---|---|---|---|
| Discovery | `loadAgentPluginPackages({pluginPaths,searchPaths:[]})`发现标准包；`createUserInstructionConfigService().start()`发现SKILL.md | 同名公开导出和类型仍在 | 仅分发已授权文件；禁止扫描共享HOME、其他工作区或自动MCP目录 |
| Metadata | 原生service的`listRecords('skill')`和`createSkillsExecutor().configuredSkills` | 接口仍在 | 绑定SDK发现身份与Runtime来源ID，供授权及脱敏观测 |
| Loading | 原生`createSkillsExecutor()`处理调用、正文与`skill-root` | 接口仍在 | 调用前重查动态授权、固定版本与已分发文件hash |
| Resources | `createDefaultExecutors().readFile`与`createDefaultTools`产生的`read_files` | 接口仍在 | 精确文件清单、真实路径、hash校验，禁止宿主文件及跨任务路径 |
| Tool Integration | `createDefaultTools`只启用`skills`、`read_files`，交给现有Agent | 接口仍在 | Shell、网络、编辑器、搜索、MCP、子Agent工具均不因此启用 |
| Session / Context | 现有Agent规范消息、原生PrepareTurn/Compaction与Runtime检查点 | 公开Agent接口保留；本轮运行验证仍针对0.0.93 | 同一SDK会话、Java模型连接、预算、Sandbox、STOP与证据治理继续使用原入口 |

直接Agent本身没有文件发现器；这不意味着必须重写Loader，也不意味着只能使用整个ClineCore。官方把上述组件作为独立公开接口导出。所有运行时导入都来自包根公开入口，没有私有文件深导入或SDK fork。

三个包的公开入口分别核对：`@cline/agents`导出浏览器安全的Agent/AgentRuntime、工厂与会话类型，没有独立的文件发现API；`@cline/core`导出表中的文件发现、配置服务及工具组件，也导出可选的完整ClineCore；`@cline/sdk`的`dist/index.d.ts`是`export * from "@cline/core"`。0.0.93与0.2.0的agents/sdk入口类型hash分别相同，core入口有变更，但本轮所需的六个公开导出仍在，不能把版本号变化等同于缺失能力或迁移必要性。

## 最小元数据兼容处理

两个已发布版本的原生`skills`工具description getter均只输出可用名称，未包含description。单靠哈希化legacy名称无法合理选择领域方法。本轮仅将原生executor的`configuredSkills`（id/name/description/disabled）序列化到原生工具`skill`参数说明；不写系统提示、不生成推荐顺序、不决定相关性、不加载正文、不增加强制阅读规则。没有复制发现器、解析器或Loader。

另外实测了公开的`service.createExtension({includeSkills:true,includeRules:false,includeWorkflows:false,registerSkillsTool:false})`：15个Skill被注册为15个斜杠命令，未注册规则、消息构造器或模型钩子；这不能替代直接Agent的模型可见用途元数据。未改写的官方工具description和skill参数也都不含已发现的用途说明。`extensionProbe`字段保留实测计数，避免因漏查该API而误判必须迁移ClineCore。

该投影是唯一新增的SDK能力兼容层。删除条件：官方工具自身公开完整可用Skills用途元数据，或提供直接可用的元数据格式化接口。升级时应移除这一行投影并验证动态Skill仍可按用途选择。定向查询官方Issue/PR及当前稳定版后，未找到可据以宣称该缺口已在稳定版修复的依据；0.2.0实际发布产物仍是名称列表。没有为此盲目升级或引入完整默认System Prompt。

官方工具自身包含“适用时读取”的条件性引导；本轮保留上游工具定义，没有叠加Runtime的“编码前一律读Skill”。Runtime没有Skill调用次数或数量上限，简单任务的直接编码与复杂任务的多Skill读取均有同一真实SDK会话测试。

## 原始包及资源

系统镜像manifest和Agent授权清单只是分发依据，不再负责发现或选择。Runtime在私有任务目录创建标准Agent Plugin外壳，把原始`SKILL.md`及声明资源按原字节交付；由官方loader识别文件和元数据。插件外壳只含Skills，不分发可启动MCP或执行扩展的配置。原生命名空间区分系统与动态包，不再用自研名称优先级删除授权Skill。

七份第三方原始包及全部资源保留hash。自有八个Skill的顶层`version`被官方严格包校验拒绝，已将其移入标准`metadata.version`，正文逐字保持；这不是修改第三方包。旧Runtime领域方法原本没有SKILL.md，因此分发时生成标准元数据外壳、保留原instructions正文，并分别记录来源hash与分发文件hash，不能将生成的外壳冒充原始包。

另外从固定提交取得[OpenAI原始skill-creator](https://github.com/openai/skills/tree/49f948faa9258a0c61caceaf225e179651397431/skills/.system/skill-creator)的7个文件和[Anthropic原始pdf](https://github.com/anthropics/skills/tree/ca45fc496a3436251f191354091d557da31c15b5/skills/pdf)的12个文件（含许可证与参考），先逐个校验Git blob SHA-1、size与SHA-256，再经同一授权分发和官方发现/加载/参考读取API验收。17个发现身份中，OpenAI与系统Microsoft的同名skill-creator由官方命名空间并存，不由Runtime删除。[原件兼容证明](../testing/evidence/cline-native-skills-20261106/vendor-original-packages.json)不含原正文；这些只是临时格式/资源测试包，没有进入生产镜像、调用Provider或执行包内脚本。系统Microsoft原件则在完整Node回归中逐字核验。

上述两份临时包使用受控的授权网关fixture，不等于已通过Java Skill库的发布准入。现有库仍会根据来源完整性、许可证、包预算和风险检查拒绝包；例如执行/敏感访问样例可能触发`EXECUTION_OR_SENSITIVE_ACCESS_REVIEW`。本轮不绕过这些治理规则，也不把SDK格式兼容夸大成所有上游包均可直接发布。生产交付范围始终是已准入、已授权的原始包。

原生发现会读取磁盘上的SKILL.md以提取元数据；“按需加载”指正文只在模型选择原生工具后进入会话，不是宣称发现阶段零文件I/O。动态包在Runtime分发时取得授权文件，不计作模型已阅读。二进制资源原字节可以分发并校验，但官方`read_files`只支持其已实现的文本/图像类型；不宣称任意二进制资源可被模型解析，也不补写自研二进制Loader。参考文件分页使用官方行范围接口。

当前Java模型桥仍使用既有的文本/工具消息协议，工具结果以JSON文本交付。SDK文件读取器支持图像块，不等于本轮已经验证图像参考能以多模态内容送达模型；此类参考的视觉消费不列为通过，也不因集成Skills而扩大模型桥或宿主文件权限。真实图文验收另做归档完整性和人工图像复核。

## 官方依据

- [官方SDK说明](https://github.com/cline/cline/blob/main/sdk/README.md)：Agent与ClineCore的职责、标准Agent Plugin包和Skills目录。
- [官方SDK变更记录](https://github.com/cline/cline/blob/main/sdk/CHANGELOG.md)：0.0.76原生Skills工具路由，0.0.81/0.0.83包发现、命名空间与边界校验。历史Desktop问题不直接作为本仓库根因。
- [core发布元数据](https://registry.npmjs.org/@cline%2fcore)、[agents发布元数据](https://registry.npmjs.org/@cline%2fagents)、[sdk发布元数据](https://registry.npmjs.org/@cline%2fsdk)：稳定版本和不可变tarball完整性。动态main仅作说明，能力结论以已校验发布产物和实际运行测试为准。

重现：在仓库根执行 `node coding-agent-node/scripts/audit-native-skills-sdk.mjs target/cline-skills-sdk-comparison OUTPUT.json`；比较目录放置已从registry下载的三个稳定版tarball及解包文件。工具Schema可用 `export-native-skills-schemas.mjs`重新导出，Java回归直接消费其产物。

最终补充：授权清单数量不再由 Java 六项或通用 128 列表上限裁剪；仅精确、转义字段路径获得例外，来源授权、内容/嵌套/敏感字段、传输及执行预算保持。123/150 项 Java 测试与 138 项真实 SDK 发现测试见验收记录。真实正式探索图表和失败节点 UI 尚有缺口，不能将原生 API 可用性等同于业务报告全项通过。
