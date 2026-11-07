# P1-02 Skills 第一阶段源码审查

**状态：已完成源码对照，P1 真实发布、多 Skill、撤权和恢复组合均未验收。** 固定版本、源码指纹及证据等级见 [总矩阵](native-p1-source-comparison.md)。

## 真实原生执行链

官方 `sdk/packages/core/src/extensions/agent-plugin/loader.ts:1061 loadAgentPluginPackages` 读取插件及 Skill 描述；`extensions/config/user-instruction-service.ts:269,287,322` 提供 start、refreshType、createSkillsExecutor；`user-instruction-plugin.ts:101,132,200` 解析已配置目录和模型给出的 Skill 名，返回 `formatSkillInvocation:32` 的正文、可选参数及 skill-root。`extensions/tools/definitions.ts:761 createSkillsTool` 是模型可调用入口。

Skills 工具不是脚本运行器，也不会替模型规划所有 Skill 的使用顺序。脚本要靠文件和执行工具完成。原生 discovery 可以解析正文并缓存记录；**源文件被读取或缓存，和正文被注入每一次模型请求不是同一个成本。**

当前路径：`NativeCodingTaskService.skillCatalog:114` / `CodingSkillReader.freezeCatalog:30` → HTTP read-skill → `native-skills.js:77` 受控包分发 → 原 loadAgentPluginPackages → 原 config service → 原 skills executor → 模型自主调用；引用资源通过合并的原 read_files 读取。未发现 Java 按题目相关性强制指定 Skill，Node 也没有另一个模型选择循环。

## 差异、缺口及不成立的指控

| 编号/项目 | 本地源码证据 | 分类与影响 | 最小候选/验证 |
| --- | --- | --- | --- |
| 目录来源 | `NativeCodingTaskService.java:23,60–61,75,97–128`，显式 SkillBinding、最多 8 项，空列表无 skills 工具；builtin allowedIds=[] | 已确认授权目录限制；不是强制执行这些 Skill。未自动枚举所有已发布授权包，不应绕过 Agent 授权改为扫描宿主目录 | 明确目录来自 Agent 发布赋权还是用户显式绑定；复用已有目录。按实际入口验证大目录，不以绕过 Java 的 Node 试验冒充产品规模能力 |
| S-01 预取与模型内容 | `CodingSkillReader.java:30–39` 冻结所有绑定；read:80–102 读取资源；`native-skills.js:77–111` 全包顺序分发 | 已确认准备阶段全包/资源预取及额外 IO；模型只见元数据、主动调用后才见正文。**“全部正文每轮塞给模型”无源码证据** | 先分别度量 catalog bytes、资源传输、首次分发、实际正文披露及重复加载，再判断是否需要延迟分发；继续 SDK discovery/loader，不自制选择器 |
| 名称与描述 | `native-skills.js:126–133,153–160`，原 configuredSkills/目的元数据进入原工具 Schema；旧方法论加分发头 | SDK 选择保留，描述不是 Java 选择结果；方法论兼容包装与原生包身份须区分 | 测“不匹配不加载”和多包自主组合，记录实际披露元数据/内容 Hash，不导出私有正文 |
| 资源访问 | `native-skills.js:48–58,137–147`，读前路径/Hash 与动态授权复核；`CodingSkillReader.read:42` 实时 scopes/resolver 校验后读冻结版本 | 已有可用读取和撤权防线；缓存并不自动绕过权限。资源由既有清单限定，安全边界不删除 | 真实授权/撤权后重新读、压缩后重载、重启后恢复；完整性错误必须拒绝 |
| S-02 原包脚本路径 | Skill root 为 Node `/tmp/runtime-cline-authorized-skills/...`；命令冻结在 `NativeCodingCommandService.java:63–104`；`NativeProjectSnapshot.java:16–30` 只 materialize 项目 files；`PythonDockerRuntime.java:116–123` 单独 tmpfs 和项目只读挂载，没有 Skill root 挂载 | 已确认原包 root 没有进入 Sandbox 项目视图；SDK 返回的原脚本绝对路径不能视作可执行证明。模型可读后复制到项目再执行是另一条路径，不能称其完全无法执行代码 | 优先现有 Silo/输入快照及只读挂载，绑定 task/Skill版本/Hash；保持原 shell/Sandbox，不开放 Worker Shell、不新建 Skill 脚本执行器 |
| S-03 失败传播 | `native-skills.js:118–124` 任一 error 或数量不一致退出；资源预取错误发生在 Agent 创建前 | 已确认静态传播方式；真实可用性影响待测。授权/Hash 失败与单个可选包不可用不能混为一类，也不能一律忽略 | 用真实坏资源/暂不可用/撤权分别验证；保留完整性拒绝，论证可恢复失败何时返回同 Agent |
| 动态新增/更新 | Java 冻结 task catalog、版本和快照；Node discovery 在 prepare 执行，SDK service 有 watcher/refreshType，但当前受控目录不随外部发布自动扩展 | 会话快照与授权一致性设计差异，不是“SDK 一定实时发现全部新 Skill” | 新授权包下一任务可见或经明确合同版本变更；旧任务不自动扩权。再发布版本与撤权分别测试 |
| 插件附带 MCP | `native-skills.js:118` 拒绝 discovery.mcpServers | 故意的权限边界，不能为完整性盲目移除；Skill 文档/插件不得自动授予 MCP 权限 | 与 MCP 已评审目录绑定，需单独治理，不自动继承包内凭据/endpoint |

原有 `coding-agent-node/test/native-skills.test.js` 已含多包、引用、撤权、字节完整性、跨进程 rediscovery 及原 SDK compaction 后再加载等构件用例。**这些用例的存在/既有通过，不等于 P1 真实发布服务、真实模型长任务、故障及撤权整链通过。**

## 审查后真实验收计划

| 必测场景 | 需要确认的模型自主行为及产物 | 当前状态 |
| --- | --- | --- |
| 单 Skill | 由 SDK 元数据发现并由模型主动调用；执行结果和最终产物可核对 | 未测试 |
| 多 Skills | 无 Java 指定顺序，模型自主组合两个以上授权包；各内容/资源版本对应 | 未测试 |
| 不匹配 Skill | 工具可达但模型可不加载，不强制使用或注入正文 | 未测试 |
| 资源与脚本 | 原引用路径读取；授权只读脚本在 Sandbox 使用冻结字节；stdout/stderr/exit/产物 Hash | 未测试 |
| 执行失败 | 真实脚本错误进入同 Agent，模型自行判断后续；不能由 Java 固定修复 | 未测试 |
| 大目录 | 通过真实 Java 入口；目录上限、模型 Schema/正文 Token、分发 IO 和重载次数分别测量 | 未测试 |
| 动态新增 | 按评审后的授权/会话版本规则发现；新版本不替换旧冻结内容，撤权阻止新读取/执行 | 未测试 |
| 压缩/撤权/重启组合 | 恢复后仅仍授权内容可读，Hash、原预算及最终交付一致 | 未测试 |

最小实施范围只可修复已论证的目录/资源治理差异。保留原生选择、loader、只读资源和旧方法论兼容路径；不把预取优化变成第二套 Skills 管理系统。当前没有代码或服务变更，等待差异矩阵审查。
