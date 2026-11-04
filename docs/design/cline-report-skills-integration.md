# Cline 报告 Skills 集成与交付设计

日期：2026-11-04。实施依据见 [源码及运行审查](../audits/cline-report-skills-audit.md)。

## 开源选型和许可

实际下载审查的 Cline 官方提交为 [`26378461e978f2b4e2e6d67b57121b86b2a79ba5`](https://github.com/cline/skills/tree/26378461e978f2b4e2e6d67b57121b86b2a79ba5)，根目录 LICENSE 为 Apache-2.0。保留原文及子目录，将 LICENSE.cline 随每个选用包分发；来源目录、固定提交、SKILL.md 和所有资源的 SHA-256/大小写入原有 catalog.json。构建不运行远程下载脚本。该提交没有额外 NOTICE 文件。

| 候选 | 实际目录及依赖审查 | 结论 |
| --- | --- | --- |
| data-analyst | `skills/data-analyst`；顶层明确只用 clickhousectl，包含多个外部子模块/符号链接；依赖 ClickHouse 与专用 CLI | 不打包顶层，避免错误开放数据库及不完整复制 |
| analyzer | `skills/data-analyst/skills/analyzer`；单 SKILL.md，给出趋势、对比、质量和结论方法，无执行脚本 | 保留原文；其提到的查询仅在现有受控工具内解释 |
| plotting | `skills/data-analyst/skills/plotting`；SKILL.md 与 `styles/cline-chart-tokens.css`；支持静态报告导出，也有 HTML/本地工具示例 | 全包保留，静态 PNG/SVG 使用既有 Matplotlib；HTML、shell、外部连接不获授权 |
| artifact-management | `skills/data-analyst/skills/artifact-management`；单 SKILL.md，包含建议目录、导出与本地 HTTP 示例 | 保留原文，产物路径和上传由现有 Runtime 合同约束，不能启动 HTTP 或外部上传 |
| reading-data-dict | `skills/data-analyst/skills/reading-data-dict`；示例依赖 gh、文档仓库和 `../clickhouse/` schema fallback | 不孤立打包，继续复用现有 Python 分析技能与已交付 fieldDefinitions/授权知识工具 |
| doc-coauthoring | Anthropic 官方实际提交 [`ca45fc496a3436251f191354091d557da31c15b5`](https://github.com/anthropics/skills/tree/ca45fc496a3436251f191354091d557da31c15b5/skills/doc-coauthoring)；目录只有 SKILL.md；内容包含交互式访谈、Reader Testing、子 Agent 建议 | 本次下载版本的仓库根及该目录未提供明确适用 LICENSE 文件，README 的概括不能替代逐项许可；不再分发。其交互式共同写作流程也不适合直接成为无人值守报告的固定步骤 |
| Microsoft | 现有原生 azure-ai-ml-py、azure-kusto 等来自固定 `3f5b461b2314e01c400564051e92469114c5b68f`，MIT，保留原有 LICENSE.microsoft；资源探索方法另有 `7a2367fc8a7d834c15b5ee8543a3d86c3003608f` 审查记录 | 保持既有包，不将 Azure SDK/服务示例当作已授权 OpenSearch 工具，不新增服务依赖 |

技能不是执行程序。选用包没有安装新 Python/Node 库；使用部署 Python 镜像实际公布的 pandas、Matplotlib 等版本。Cline SDK 继续固定 0.0.93，构建及测试通过真实 Cline parser/watcher 发现原始内容。版本升级需重跑完整性、发现、冲突和真实任务验收，不能只替换依赖版本。

## 运行时适配

新增平台自有 `runtime-report-delivery` 薄技能，明确证据筛选、去重、实体核对、分析正文与原始清单的区别、图表适用性和既有交付协议。它不规定章节或检索/统计/绘图的固定顺序。原生技能正文不修改，Runtime 的工具边界与路径优先于其通用示例。

所有新增技能仍通过 `skills.js::taskSkills` 暴露描述，由 Cline 按需 skill_read。保留现有内置保护、动态同名冲突、版本与禁用元数据，无第二套 Skills 管理服务，不将全文注入每轮请求。

`ComputedResultBoundary` 在原有全文/记录比较之外，识别多个短源片段构成的大篇幅格式化复制：至少三个不同片段，覆盖联合长度至少 2,400 字符且占报告 40%，报告至少 4,000 字符。范围和片段数有上限，重叠片段不重复计数；正常少量引用可继续交付。它是复制边界，不是业务推理或 Java 代写。

资源任务仍先通过原有 `CodingKnowledgeService` 逐次授权并固定 MCP 来源。实际分析任务或图文偏好且存在已授权资源工具时，可以使用既有 Python Sandbox。市场观察读取结果通过 `CodingDataSupplyService.captureResource` 进入现有不可变输入版本，返回 calculationInput 描述；Python 从文件读取，禁止将 prompt 数字复制进源码。目录搜索不生成计算输入。

每次 Python 执行继续冻结输入版本并验证完整 fileId/SHA-256 集合，调度前再次核验资源访问和源版本。结果沿现有 collector、visualReport、校验/来源证据和 Silo 归档，无直接数据库、任意 MCP、主机 shell 或网络权限。文字报告不能替代已经开始的 Python 执行。无可靠数值时可省略 visualReport，以文字和数据限制交付。

## 兼容与验收

既有业务 MCP 模板与权限不调整；普通资源 QA 保持文字提交路径；无授权资源的任务不获得 Python 兜底。旧任务合同与输入版本保持原样，不重解释历史文件。新报告复用同一 UI 组件、图表资源和历史记录。

必须记录真实 MCP 检索、Cline 选择与加载技能、Python 退出与产物哈希、报告呈现和历史重开，不能把本地单元/SDK 测试当作真实环境验收。部署、回滚、耗时和 token 结果另记，不以该设计文件宣称通过。
