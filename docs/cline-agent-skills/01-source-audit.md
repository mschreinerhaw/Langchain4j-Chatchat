# Cline 默认技能与 Agent 传递链审查

开发分支：`feature/cline-agent-default-skills`。基线为 `029fe6ee3`，包含服务器现有的数据探索功能，避免以 main 重建 API 时回退该功能。

## 现有实现

- `coding-agent-node/Dockerfile` 采用 Node 24.21.0，最终运行用户 node。现网覆盖为 UID 1000 / GID 32101，工作目录 `/srv/coding-agent`，只读根文件系统，没有共享 HOME 技能挂载。
- `package-lock.json` 固定 `@cline/agents`、`@cline/core`、`@cline/sdk` 为 0.0.93。实际运行入口是裸 `Agent`，不是 ClineCore 或桌面扩展；裸 Agent 不会自动扫描 `/opt/cline/skills`。
- 对安装包实际执行 `resolveSkillsConfigSearchPaths`，项目搜索目录包括 `.clinerules/skills`、`.cline/skills`、`.claude/skills`、`.agents/skills`，用户搜索目录包括 `~/.cline/skills` 和 `~/.agents/skills`。本次不扫描共享用户目录。
- 官方说明也规定技能以 SKILL.md 名称/描述发现，按需加载正文及资源：[Cline Skills 文档源码](https://github.com/cline/cline/blob/main/docs/customization/skills.mdx)。本项目以固定版本 SDK 的实际行为为准。
- 动态技能定义在 `chatchat-chat/.../skills/domain`；目录、发布状态和版本由 PostgreSQL 管理，源包及引用资源继续由 `DomainSkillPackageReader` / `DomainSkillArtifactStore` 使用现有 Silo 对象存储读取。
- Agent 的 `workflowConfig.boundDomainSkillIds` 进入 `SkillAnalysisContextService`，经已有 SkillRouter/权限策略生成能力卡；AgentChatModeHandler 将上下文送入执行属性；CodingConfigurationSnapshot 固定任务配置；CodingAnalysisService 创建契约；Node 接收 runtimeContext.skills。
- 原有 `skill_read` 从 Node 回调 `/internal/coding/cline/read-skill`；CodingSkillReader 按租户、用户、Agent、版本、资源清单和哈希校验读取，不会增加工具、数据源或网络权限。

## 审查发现和改造

后续真实客户测试发现模型拿到元数据后可能直接编程而不读取适用技能。Node 的通用说明现要求 Cline 在实现前判断目录中哪些技能适用，并先按需读取这些技能正文；引用仍按需要读取，不指定技能 ID，不注入正文，也不授予额外工具。是否适用及具体方法继续由 Cline 判断。

1. 原未绑定技能的 Agent 可回退到授权搜索目录，不能满足 Agent A/B 隔离。现在只有显式绑定的动态 ID 能进入该 Agent 的任务目录；已知绑定使用空查询读取授权元数据，避免语义搜索或搜索索引故障改变技能集合。
2. 镜像原无默认技能。本次增加两个独立包和引用文档（数据分析 1.3.0、代码开发 1.0.0），保存在 `/srv/coding-agent/skills`，以 `.cline/skills` 链接支持 SDK 原生发现。适配器显式通过 Cline 解析器解析包，再接入原 `skill_read`，不引入第二个 Agent、分发器或模型循环。
3. 内置技能和动态技能按任务合并。内置 ID 或名称（忽略大小写）保留给镜像；任何动态版本均不能覆盖内置，冲突通过目录及 `SKILL_CONFLICT` 事件明确呈现。动态重复 ID 明确拒绝；不同 ID 的动态同名版本按 Agent 绑定顺序保留首项，记录被排除版本和保留版本，不按版本字符串猜测升级优先级。
4. `workflowConfig.disabledBuiltinSkillIds` 按 Agent 禁用内置能力；不删除镜像文件。Java 校验合法 ID，随现有技能上下文固定到任务，Node 仅按该任务策略过滤。禁用一个 Agent 不修改其他 Agent 或镜像。
5. 动态内容在首个模型准入前固定到已有任务 privateState，正文及可读引用按哈希验证；模型仍只先看到能力卡，只有主动 `skill_read` 才接收正文和引用。恢复和重复读取复用任务快照，同时重新检查实时授权。新任务解析更新版本。发布/撤回或权限撤销仍可拒绝访问；不承诺绕过授权撤销继续读取。
6. 技能市场保留动态分页及发布/回收/卸载操作，额外展示镜像内置技能的来源、名称、说明、版本和适用 Agent；内置技能不进入普通卸载操作。Agent 编辑页提供独立禁用选项。

Python Sandbox、输入快照、文件映射、哈希验证、执行权限、结果与图文报告交付均沿用既有路径。Python Studio 仅生成源文件时，skill_read 只读镜像能力，仍无代码执行权限。
