# Microsoft 原生技能资产与加载边界

上游：[microsoft/skills 固定提交](https://github.com/microsoft/skills/tree/3f5b461b2314e01c400564051e92469114c5b68f)，提交 `3f5b461b2314e01c400564051e92469114c5b68f`，引入日期 2026-10-31，MIT。每包附上游根许可证 `LICENSE.microsoft`。包装版本 `upstream-3f5b461b2314` 不写回上游 frontmatter。

| 原生名称 | 上游目录 | 适用范围和前提 |
| --- | --- | --- |
| azure-kusto | `.github/plugins/azure-skills/skills/azure-kusto` | ADX / KQL；需要另外授权的 Azure 连接，不能用于替代 MySQL 方言 |
| azure-ai-ml-py | `.github/plugins/azure-sdk-python/skills/azure-ai-ml-py` | Azure ML Python SDK；不隐式安装 SDK、创建资源或授予 Azure 凭据 |
| mcp-builder | `.github/skills/mcp-builder` | MCP 工具开发方法；不替代已有模板发布、治理与执行服务 |
| skill-creator | `.github/skills/skill-creator` | 原生 Skill 组织与质量方法；不触发 Runtime OS 领域技能编译 |

通用 Pandas / NumPy 分析和 Data Visualization **待补充微软官方直接适用资产**。现有 `python-data-analysis` 与 `python-code-development` 是 Runtime OS 自有技能，未冒充 Microsoft。Fabric、Cosmos DB 与其他 Azure SDK 技能本批未引入；TPC-DS MySQL 场景不需要这些额外服务及凭据。

2026-11-01 任务书允许通用能力由既有技能与适用的微软原生技能组合提供。因此“待补充”指微软直接来源的通用资产，不代表系统没有 Python 分析、可视化或数据探索方法。各项能力的实际来源、执行证据，以及新增 `microsoft/microsoft-sql` 来源的 MySQL 兼容性审查，见[本轮复验记录](../sql-chain-revalidation/README.md)。不能把 Azure/T-SQL 包改名或改写成微软 MySQL 分析技能。

## 完整性及升级

`coding-agent-node/skills/catalog.json` 记录每包 repository、原始 path、commit、license、引入日期、SKILL.md SHA256 及全部子文件大小和 SHA256。25 个文件（含各包许可证）从固定提交的 Git blob 导入，避免 Windows checkout 自动转换换行。`.gitattributes` 对这四个目录禁用文本转换。

复现：检出上述固定提交到 `target/microsoft-skills-upstream`，在项目根执行 `node coding-agent-node/scripts/vendor-microsoft-skills.mjs`。已导入时必须显式 `--refresh`，且只允许同一提交、清单内文件未被修改的情况。跨提交升级需另行审查，不会覆盖本地修改或删除未知文件。上游 scripts 仅作为原始资源保存，导入及加载过程均不执行。

## 按需加载

1. Java 只给任务交付名称、描述、来源及版本等元数据，剔除 resources / references。
2. Cline 查看当前任务目录，自主选择 `skill_read`。完整原生 SKILL.md（包括 YAML frontmatter）只在读取时返回；资源文件按明确的相对路径再次读取。
3. worker 使用已安装 Cline parser 校验原生正文，原文不转为 Runtime OS 技能，不要求上游增加顶层 version。
4. 资源路径必须位于包的显式哈希清单；拒绝绝对路径、路径穿越、URL、符号链接和完整性不符。支持 singular `reference/`、`references/`、scripts、嵌套目录及二进制 base64 交付。
5. Agent 禁用技能后，目录中不出现，读取也不可用；其它 Agent 不受影响。技能只授予方法，不授予 SQL、工具、网络或 Python 执行权限。

本专项没有修改 Java 领域技能编译、需求分析模板或原有 SQL 参数公共编译器。SQL 模板与这些技能不存在外键、发布依赖或固定执行流程。
