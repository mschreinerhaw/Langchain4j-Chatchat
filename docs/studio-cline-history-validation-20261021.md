# SQLStudio / PythonStudio Cline 生成与最近会话验收

2026-10-21。在 `192.168.195.221:5221` 的隔离项目 `runtime-os-app-stage` 完成两项真实模型生成、历史 API 和实际浏览器验证。生产版本未替换。

生成执行版本为 `db6edb6aef472d645177a5d9fda0a0670913f907`；最终展示修复版本为 `de7f78e8c4b100710d47c4e85ee661328976f3b1`。后者只改变前端展示及验收脚本；部署清单证明四个模块和 API 类覆盖包的哈希与生成时完全相同。模型回执保留原版本身份，没有把查看历史算成重新生成。

## 结果与实证

| 场景 | 正式 Coding 任务 | 耗时 | Cline 调用 | 输入 / 输出 Token | 产物 |
|---|---|---:|---:|---:|---|
| SQLStudio：根据给定表定义生成参数绑定、账户汇总 SQL | `sql-coding-5f8a0e61-ff3e-4fb5-950f-6566cf4b4c0c` | 40.099 秒 | 3 | 4466 / 1935 | `sql/trade_account_summary.sql` |
| PythonStudio：生成 Decimal 分类汇总及独立测试文件 | `python-coding-77d0ed023fd799baf2a51b4e26c3006681d2b12b1d22ef9695625a286b9a8d82` | 83.454 秒 | 3 | 6607 / 4655 | `main.py`、`test_main.py` |

两项均为 `COMPLETED`。发起请求选择的模型、服务端解析的模型及真实供应商回执均为 `qwen3.7-plus`；Java 交付准备的独立模型调用为 0。Java 仍代理 Cline 的模型 API 请求以保管服务端凭据，这些请求归属上述 Cline 调用，不是第二套分析决策。

两项分别使用 SQLGlot 和 Python AST 完成语法校验。工作区契约明确 `maxCodeExecutions=0`、`networkPolicy=NONE`、`allowedDataRefs=[]`，只允许 `workspace_read` / `workspace_propose`。没有执行生成的 SQL、Python 主程序或生成的 unittest，也没有自动接受提案或修改工程文件；语法通过不代表业务计算正确性已经认证。

正式最近会话 ID：

- SQL：`595ca946-4806-4fdf-bacc-02a01a44d7fd`。
- Python：`ea21b619-9d39-3f4e-abbb-76d571fd00ac`。

两项均存在一条用户问题和一条助手结果，含全部生成源码；重复查询没有增加消息。真实浏览器经 SSH 回环访问已部署 UI 和 API，没有 HTTP 夹具：两项在智策空间最近会话中可见、可搜索和打开，渲染后的三个文件均完整包含归档源码，页面脚本错误为 0，打开历史触发的新生成请求为 0。原始代码、截图和完整历史保留在受控证据目录，仓库只提交脱敏回执及哈希。

这是每场景一次最终观测，不是稳定性能基准；没有据此宣称原有多模板分析的性能目标已经达成。完整单轮耗时、Token 和产物哈希见 [SQL 回执](studio-cline-history-20261021-evidence/sql-verified-acceptance.json)、[Python 回执](studio-cline-history-20261021-evidence/python-verified-acceptance.json)。成本计量不完整时仍保留原有预算预留，不把未知成本写成已知的 0。

## 修复清单

| 实际问题 | 修复及验证 |
|---|---|
| 验证环境 workspace 开关关闭，Python 退回 Java 生成；SQL Agent 模式走旧链路 | 所有新 SQL 生成/修复/优化任务均选择同一 Cline 工作区，Agent 模式继承授权助手模型。Python 生成、补全、修复、优化和测试生成进入该工作区；旧直接代码生成接口拒绝旁路。未启用 Cline 时明确拒绝生成。Python 解释/问答复用既有 CodingAnswerPort。 |
| Java 在 Cline 前额外制定分析计划 | `StudioCodePlanService` 改为模型调用为 0 的交付凭据，保留身份、语言、操作、源码、模型指纹及原 InterpretationPlan 校验和存储。方法选择、代码生成、解析反馈修正由 Cline 决定。 |
| SQL / Python 生成结果没有完整进入最近会话 | 新增 `StudioTaskHistory`，复用既有 ConversationService 和 Evidence 检查点；保存问题、模型、任务 ID、状态、日志及代码。历史与执行状态分离，写入失败不能隐藏已受理任务 ID或使已提交代码变成失败，后续查询重试投影。 |
| 首次 Python POST 返回 500 | PostgreSQL 的消息 ID 列上限 64 字符，原拼接 ID 过长。改为按租户、用户、任务、角色生成稳定 36 字符 UUID，保留真实长任务 ID 在消息字段中；没有扩大数据库字段或修改执行任务 ID。 |
| 首次 SQL 在第二轮前耗尽预算 | 全局大回复上限占满旧的 32768 Token 工作区预算。工作区回复默认限制 8192 Token，总预算默认 262144、最大模型调用仍为 8。两者可配置，已有合同保持冻结；未放松累计准入、停止证明或未知计量的保留规则。 |
| 历史中的 SQL 参数空格及 Python 缩进被清理 | 后端通用回答清理及前端分析报告清理均会改变源码。工作区结果携带真实 v2 文件格式标记，持久化/读取与 Markdown 展示保留字面代码；普通问答清理保持原规则。Markdown HTML 仍转义，脚本不会执行。等价历史合并和再次保存保留来源标记。 |
| 旧历史需要修复但不能重跑模型 | 对历史投影使用版本化的已应用 revision；读取从持久化任务和已验证产物恢复，不重置 Coding 合同、预算或 SDK。重复读取去重，恢复测试覆盖重启和暂时不可用。 |
| 切回智策空间时列表未更新、运行中 Studio 记录被当成 Agent 恢复 | 切换空间刷新原最近会话索引。Studio 记录保留任务来源，历史查看不会通过 Agent Task API 恢复或创建第二次生成。 |
| 联机测试驱动误算 Python 会话 ID | Python 既有作用域使用可信登录名，索引支持服务端验证的 ID/登录名别名。驱动改为通过正式会话列表和真实任务 ID 定位会话，没有更改权限规则或伪造会话。 |

首次失败和中间候选全部保留：`c4c64344` 的 SQL 预算失败和 Python HTTP 500；`5ffc420e` 两项代码生成成功但发现历史展示改变源码；`db6edb6a` 完成真实生成和 API 保真，`de7f78e8` 修复浏览器渲染。未合并不同版本样本来计算加速比例。

本轮共 6 个受理任务均已停止、无待协调恢复，Node 活跃会话为 0。首次 HTTP 500 后未返回 ID 的 Python 任务通过原持久化记录定位，再由正式接口收敛并恢复其历史；没有删除失败证据。见 [收尾检查](studio-cline-history-20261021-evidence/post-validation.json)。

## 测试、权限和剩余门禁

- 15 类 **132 个不同 Java 用例**通过，0 失败、0 错误、0 跳过，重复执行按类去重。覆盖 SQL 工程 HTTP、跨用户/租户、版本冲突、元数据、Cline 预算、消息持久化、去重和恢复。
- 包含 2 个真实 Node/Cline SDK、Java/H2、SQLGlot / Python AST 的跨进程用例；这些用例的模型是确定性夹具，与上表真实供应商调用分别记录。
- **103 个前端用例**通过；另有实际 Chromium SQL 编辑器工作流测试，使用明确标注的 HTTP 夹具。
- 最终已部署 UI 的两个历史条目通过真实浏览器检查，无 HTTP 夹具，完整代码逐项匹配，见 [浏览器结果](studio-cline-history-20261021-evidence/studio-history-browser.json)。
- 打包/验收脚本 3 项回归通过，前端构建成功。
- Node 非 root、根文件系统只读、无 Docker socket、丢弃全部 capabilities；探测确认不能直连 MCP、业务数据库及外网，未认证内部回调和任务接口返回 401，见 [边界结果](studio-cline-history-20261021-evidence/boundary.json)。

本轮全量回归在 `c4c64344` 运行，248 个已报告类、1155 个用例，仍为 **52 失败、39 错误、0 个 JUnit 跳过**。`HarnessToolRecoveryTest` 中 Windows `librocksdbjni` 触发 `EXCEPTION_ILLEGAL_INSTRUCTION`，部分依赖模块未运行；不能称为完整后端通过。与前一候选 `eb571d57` 的 91 个失败/错误指纹一致，无新增或消失；这不是与最初冻结 Tag 的完整比较。后续 API 修复由上面的定向测试和真实场景覆盖，全量门禁仍未放行。`maven.test.failure.ignore` 只用于收集失败，没有跳过或移除失败用例完成验收。

完整失败清单见 [全量报告](studio-cline-history-20261021-evidence/full-regression.json)、[差异与执行范围](studio-cline-history-20261021-evidence/full-regression-delta.json)。

本轮不删除或更改受控业务模板的执行步骤，不为资产、客户、表字段或模型名称新增生产分支。新增生产源码中的场景字面量检查为 0，两个 Studio 服务中的直接 `.chat(...)` 为 0；模型桥仍按 Cline 授权调用模型。原文档 RAG 未恢复，共享 Skills/MCP/业务元数据和通用文档处理继续保留。遗留未分类 Java 兼容调用的审计仍沿用 [上一轮报告](runtime-os-coding-scenarios-20261021.md) 的生产门禁。

会话内容受既有存储预算约束，完整源文件以不可变 Evidence 产物为准。本次三个文件均验证完整显示。历史上从未保存到服务端的浏览器临时内容不能凭空恢复；本轮已登记交付信息的任务可以从服务端记录恢复。

## 部署和回退

最终 API：`runtime-os-api:protocol-recovery-cb9f44d496d0`，Jar SHA-256：`cb9f44d496d09290dcd7ca939134ae289295cbea670cdf624696050f814ca4cb`。

Node 保持 `runtime-os-coding-agent:analysis-fast-daebd60fa2d6`，MCP 和无关容器保持原版本。工作区在隔离环境启用，固定解析器使用预装 Python 3.10 和 SQLGlot 30.23.0；wheel 与仓库锁定 SHA-256 一致，生成过程中不联网安装依赖。前端由通过测试的源码构建，文件清单和覆盖包均校验哈希，保留无关归档条目。

可审阅的 [代码变更清单](studio-cline-history-20261021-evidence/code-changes.txt)、[模块清单](studio-cline-history-20261021-evidence/module-manifest.json)、[组包校验](studio-cline-history-20261021-evidence/module-package.json)、[部署记录](studio-cline-history-20261021-evidence/deployment.json) 均已保存。原始代码、任务日志、私有请求、截图及配置备份位于服务器受控目录 `/opt/runtime-os-docker-stage/evidence/runtime-os-studios-20261021/`；认证文件和完整模型消息不提交仓库。

回退前先确认正式接口中的任务已停止。恢复同一备份组的 Compose 和 deployment.env，再仅重建 API / 共享网络命名空间的 node-proxy，恢复既有 worker 网络限制并重载网关，不删除任务、快照或产物：

- 回到本轮 Studio 改造之前：`.../runtime-os-studios-20261021/backup-1792517442`，对应 API `1cba3b32e5ba` / Node `daebd60fa2d6`，工作区开关恢复原状态。
- 仅撤回最后的前端字面渲染更新：`.../runtime-os-studios-20261021/backup-1792519739`，对应 API `86799c654a0b` / 同一 Node。
- Git 原始回退点仍为远程备份分支 `backup/runtime-os-before-coding-refactor` 和 Tag `runtime-os-pre-coding-refactor-20261020`，解析到 `4bd002584151cc2423ed6b08f377782243477e4b`；使用独立 worktree 恢复，避免丢弃现有改动。

全量回归、业务口径及代表性性能场景未通过前，不替换生产版本。
