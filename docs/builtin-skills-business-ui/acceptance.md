# 内置 Skills 产品体验整改验收

专项分支：`fix/builtin-skills-business-ui`。源码提交：`52d6d624e`、`c75ade121`、`41befb032`。

## 变更范围

复用现有镜像目录、Java 元数据投影、领域技能授权/发布/绑定、任务快照、Cline SDK、skill_read 回调及 Python Sandbox。没有另建存储或执行引擎，没有修改 Java 领域技能编译器或预置具体业务分析步骤。

- Agent 设置删除两个内置 Python Skills 禁用复选框及说明区域。已选数量、选择列表和提交参数仅包含业务 Skills；权限检查、绑定和取消绑定沿用现有机制。
- 旧 `disabledBuiltinSkillIds` / `builtinSkillPolicy` 统一退出 Agent 可配置项。保存、读取、版本配置规范化和任务传递不再采用这些禁用值；worker 对旧快照中残留的禁用策略同样忽略，包括旧格式异常值。内置可用性由平台镜像目录的 `enabled` 管理。
- 不批量改写历史 JSON、已归档版本或任务证据。旧配置不保存也立即按同一默认规则运行；正常保存时移除遗留配置。业务绑定继续保留，不能用退役字段获得任何资源授权。
- 原目录 `coding-agent-node/skills/catalog.json` 增加 displayName、descriptionZh、source、sourceVersion、builtin、enabled。中文别名与上游正文独立，英文 ID/name/version 继续用于调用和校验。市场主卡片展示中文，详情展示原始 ID、版本、来源、Git Commit 及许可证。
- Cline 只接收目录摘要，根据任务读取适用正文，必要时读取附属资源。Python Studio 源码模式补充通用的适用技能发现提示，不指定 Skill ID，不规定具体编程或分析步骤。实际工具授权和执行预算保持原约束。

源码入口：AgentWorkshopView.vue/.js、DomainSkillsView.vue/.js、AgentWorkshopController、SkillCatalogService、BuiltinCodingSkills、SkillAnalysisContextService、SkillAnalysisContext、CodingConfigurationSnapshot、worker skills.js/runner.js，以及 Dockerfile 和既有离线镜像构建脚本。

## 验收结果

| 要求 | 证据与结论 |
| --- | --- |
| Agent 不展示内置开关/说明 | [真实页面截图](ui-final/agent-settings.png)、[UI 判定](ui-final/ui-verdict.json) |
| 业务 Skills 可配置且数量正确 | 实际页面将两项业务绑定取消一项、保存为一项、重新绑定并保存为两项；[原始保存结果](ui-final/business-skill-rebound.json)，内置六项未计数 |
| 内置技能可自动发现 | [真实镜像重建及150项离线检查](worker-build.json)，包含固定 Cline SDK 原生发现 |
| 数据分析自动加载 | [真实 Cline 加载事件](analysis/selected-skills.json)：builtin:python-data-analysis 1.3.0 |
| Python 编程自动加载 | [真实 Cline 加载事件](development/selected-skills.json)：builtin:python-code-development 1.0.0 |
| 无关任务不加载全文 | [普通问答实测](unrelated/verdict.json)及原始观察；worker 单元检查另外验证实际 SDK 循环只收到目录、没有正文读取。该单元检查使用确定性模型，不冒充真实模型任务 |
| 市场中文与详情 | [中文列表](ui-final/marketplace-chinese.png)、[详情](ui-final/upstream-detail.png) |
| ID、SKILL.md、附属资源完整 | [旧/新实际镜像30个原始文件逐字节对比](original-files-integrity.json)，仅目录元数据排除在原件比较之外 |
| Docker 升级可发现/加载 | 实际新镜像离线重建、目录解析、原生发现、150项检查及两项真实任务共同验证；无运行时安装依赖 |
| 日志记录实际 ID/版本 | 两例 selected-skills.json 均取自 NODE_CLINE / SKILLS_LOADED 原始事件，不是预期清单 |
| 历史配置兼容 | [升级前实际 Agent](legacy-agent-before-upgrade.json)保存两个非空禁用字段；[升级后](ui-final/agent-after-upgrade.json)字段消失且两项业务绑定保留；同一 Agent 已实际完成附件分析 |

## 两项真实 Cline 任务

数据分析 Agent 任务：`4fa9e0ba-ddea-3ed8-a03a-63053f95b66e`；Coding 任务：`analysis-coding-36569784b5f6d48e591317a08874c3f02343ea5c26f1cb5599ce9960144ff423`。

普通自然语言要求分析本次上传的合成 CSV，没有指定 Skill ID。Cline 自主读取 Python 数据分析技能、生成代码，在 Sandbox 实际执行并上传代码/图表。[独立核对](analysis/execution-and-numerical-validation.json)从原 CSV 以整数分重新计算：金额325.25元、销量8件、三个区域；区域明细与报告一致，最终执行 exit 0 / VALIDATED，上传字节哈希相符。

执行历史保留一次失败及随后成功的修复，不描述为一次成功。报告状态 PARTIAL，保留极小合成样本、无日期、未声明含税/折扣等分析限制；这不妨碍本任务“自动发现、加载并实际运行”的功能验收。没有将这些数值写入生产逻辑。本例不额外声称已完成系统页面图表渲染验收。

Python 编程任务：`python-coding-6b1d5b733bf18c5f432a1972ee6c0300db81a3b24f6553c7aff4a2db33496599`。

Python Studio 收到普通的程序编写需求，skillIds为空；Cline 自主读取 Python 程序开发技能，交付 main.py 和 test_main.py。[实际结果](development/result.json)为 COMPLETED，原始事件和代码哈希均保留。源码模式只进行语法检查和候选文件写入，**没有执行生成的测试**，不宣称运行行为已验证，也没有增加代码执行权限。

## 回归、部署及限制

Java 专项33项通过；前端两个相关测试文件40项通过；新 worker 镜像完整150项通过，其中包含本地7项技能专项，数量不重复相加。Vue结构检查与前端构建通过，最终API打包成功。构建期间一次错误的静态目录参数导致资产校验拒绝，改回项目原输出目录后通过；没有跳过资产校验。

最终 API 镜像：`runtime-os-api:builtin-skills-business-ui-20261101-r2`。最终 worker：`runtime-os-coding-agent:agent-skills-business-ui-20261101-r2`，镜像ID `sha256:b8c1848725e563b4d5f04c835f0b03257f48be89b858b7e2c67b703529410afd`。

[部署回执](deployment.json)记录备份、无活动作业时切换、API 本地 MCP 目录就绪以及其他容器/会话内容不变。未修改 MCP/数据库/文件系统/网络授权。最后一轮 API 只调整详情按钮样式；[2070个API/Chat/Agent类字节完全一致](final-java-bytecode-comparison.json)，worker镜像未变，因此两项运行证据对应同一执行实现。

隔离 Agent `builtin_skills_ui_20261101` 用于页面及历史兼容验证；[测试后已回收删除](fixture-cleanup.json)，不修改现有业务 Agent，原始分析任务仍可读取。用例的旧配置、原始请求、模型事件、执行失败/修复、输出和验证原件一并保留。错误 reportMode 请求在创建任务前被拒，另存 analysis-invalid-request，未计入真实任务成功数。

## 复现

无需选择或安装内置技能。普通用户直接描述任务；业务技能仍在 Agent 设置的 Skills 选择器中配置，内置基础技能由平台镜像目录维护。

本地回归：

```powershell
# coding-agent-node 目录
node --test test/builtin-skills.test.js test/microsoft-skills.test.js
# chatchat-api/web-app 目录
node node_modules/vitest/vitest.mjs run src/js/views/AgentWorkshopView.test.js src/js/views/DomainSkillsView.test.js
```

实际任务脚本 `scripts/builtin-skills-acceptance/run-live.mjs`：设置 SKILLS_ORIGIN、SKILLS_AUTH_FILE（正常登录响应文件）和新的 SKILLS_OUTPUT 目录；执行 development，或设置已正常发布的 SKILLS_AGENT_ID 后执行 analysis。不会覆盖已有 submission.json，不预先选择内置 Skill。`verify-analysis-live.mjs` 核对实际执行、下载产物及原 CSV 数值。

真实页面脚本 `chatchat-api/web-app/scripts/test-builtin-skills-ui-live.mjs` 使用 E2E_ORIGIN、E2E_AUTH_FILE、E2E_OUTPUT、E2E_AGENT_ID、E2E_AGENT_NAME。只允许指向自己的隔离测试 Agent，要求预先绑定两项有权访问的业务 Skills；测试实际取消/恢复绑定并保存，不修改他人 Agent。
