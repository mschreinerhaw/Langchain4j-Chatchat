# 首次代码效率整改验收

Worker 实现 `f09cb4b5d`、Java 冻结任务 `c97962110`，基线 `1ecf091df`，专项分支 perf/cline-precode-context-20261106。

## 固定测试与部署

- Windows/Linux 各237/237 Node，真实 Docker Python 10/10，无跳过。原236项日志保留，新增原生压缩检查后的最终237项日志另列。
- Java CodingAnalysisServiceTest 45/45，既有资源授权与多数据集合同测试同时验证强制 Skill 前置要求消失、传输映射/Decimal/证据权限要求仍在。
- Monitor 浏览器真实 SDK + 确定性 gateway fixture：登录、只读会话、hash、XSS、模型/Skills/事件、离线恢复、移动端、退出通过，错误0。
- 新增10项覆盖精确 Skill 正文复用、版本和任务隔离、Unicode 参考分页、快照引用纠正、动态撤权仍检查、新证据不误报停滞、前置用量/大小、3类模型失败不执行 Python 或隐藏重试，以及已安装SDK原生压缩后全文恢复。
- 原生压缩检查实际调用 sdkPrepareTurn，构造超过上下文的合法工具对，确认早期 Skill 全文从准备后的请求消失，再次披露取得完整正文。该检查不调用真实 Provider，不冒充联机发生了压缩/重新读取；Worker f09cb4b5d 未因新增测试改变。
- 确定性故障是受控回归，不等于真实 Provider 首 Token/流中异常的生产验收。

Worker 镜像 runtime-os-coding-agent:cline-precode-20261106-r2，
image sha256:f25e53c37d603b28f831916bff1fa86a2e0d7273088ee36387fb4e7683f5d80c。
源码f09cb4b5d，签名回调通过、未签名拒绝、原 HostConfig/Mounts 权限逐字段保持，其它容器ID不变。
原 Worker 保留为 runtime-os-app-stage-coding-agent-1-before-sdk-reuse-f2aa363efd1a（中间版本）；最初基线备份为 runtime-os-app-stage-coding-agent-1-before-sdk-reuse-4da48d1e57df，
私有配置备份 /opt/chatchat-releases/cline-precode-20261106-r2/backup；回滚须等任务停止并执行原部署的只替换 Worker/回调验证流程，不重放未知执行。

Java 发布包 sha256:06c4f666dff00704c8b5c0f0994c8c342655eeff47ceffe190f5e238fa98527f，镜像 runtime-os-api:cline-precode-objective-06c4f666dff0。
仅 CodingAnalysisService.class 改变，前端、所有依赖和其它类逐项不变；两项常量字符串删除后方法反汇编一致。
API HostConfig、有效挂载和启动配置保持；回调代理因共用 API 网络命名空间，重建后仅 HostConfig.NetworkMode 指向新的 API 容器，继承其 hostname，原镜像、只读 Nginx 白名单挂载、环境与其它安全配置不变。
API 和代理以外的容器 ID 保持。重启后正常重新登录，签名回调成功、未签名401、网关认证健康检查成功。
见 [包条目证明](evidence/cline-precode-20261106/api-artifact-proof.json) 与 [部署证明](evidence/cline-precode-20261106/api-deployment.json)。

部署中保留了失败记录：旧脚本证据/token路径失效、无 compose 命令；API 重启使旧登录会话失效；仅 reload 代理无法加入新 API namespace；Docker 拒绝同时显式设置 hostname 和 container network mode。
失败均在联机复测前发生，未提交模型样本或重放执行。替换失败回滚原 API/代理后验证签名边界，修正部署操作后才启动最终批次。
回滚保留 API runtime-os-app-stage-api-1-before-precode-b58de79b7489、代理 runtime-os-app-stage-node-proxy-1-before-precode-d9bca128ed44，配置备份 /opt/chatchat-releases/cline-precode-20261106-r3/api-proxy-deployment-backup。
须先核对全平台任务/Node/Python 空闲，恢复备份的 compose/env 并将两容器一并恢复，重新登录、重载原网络限制，验证签名通过、未签名拒绝与网关健康；不能只恢复 JAR 而遗留代理 namespace。

## 模型阶段观测边界

| 阶段 | 实际可取得的指标与限制 |
|---|---|
| Runtime 准入 | 独立 Node 预算/准入路径测量前置等待；Java 原子准入内不可分离时 UNKNOWN |
| 请求传输 | Node callback 连接、请求本地写出、响应头/体/整体耗时；不能解释成 Provider 纯网络时间 |
| Provider 首 Token | 仅复用 Java 明确给出的 firstTokenMs；buffered callback 首事件不是 TTFT |
| Provider 流式输出 | 仅已有 generationTimeMs；非流式或未知保持 UNKNOWN |
| 最后 Token 到完成 | 当前接口未提供，UNKNOWN |
| Runtime 响应处理 | 新增成功响应的 Node JSON 解析处理耗时；错误路径和 Java 内部拆分仍未知 |
| 最终错误 | 保留实际 errorCategory/diagnosticCode；callback deadline、callback 中断、model request timeout 与 Python 错误分开 |

普通 Monitor 展示首提案/接受/Python、前置调用和已知输入 Token、Skill 正文/参考唯一数、来源/披露字节、重复/复用、
输入读取/工具拒绝和实际工具。不展示敏感正文，阶段“准备/探索/修复”为事件推断标签，不冒充模型想法。
Skills 请求比例为字节比例，未知观测不以0伪装。进展卡片明确为本代次 SDK 会话；跨恢复 fence 的首次时间由性能收集器合并事件计算。
Node 进展卡片首次 Python 时间为工具派发，不冒充 Sandbox 进程实际开始时间；实际执行次数须有 Java stopProofRef 和计量证明。
性能附表另经所有者 API 读取不可变 MCP 归档 receipt.startedAt，逐项校验 task/run、stop proof、注册 hash/size。
该字段是 MCP 执行回执开始时间，不是 OS process-spawn 探针；没有记录的开始时点仍 UNKNOWN。原 Runtime、预算、授权、STOP、SDK recovery 和结果验证未放宽。

## 联机验收

Worker 第二候选中的真实 Parquet 任务在模型第一轮直接调用 Python，未读 Skill，首提案135,461ms、首派发135,720ms、首结果139,267ms，两次模型后完成。
这是单项事实，不能替代整体对照。

真实线上 Monitor 浏览器验证 build f09cb4b5d 与最终 Java 生成的 CSV 目标：强制 Skill 前置句消失，传输映射仍存在；前置 Token、Skill 交付、请求占比、UNKNOWN 阶段与实际工具均可见，浏览器错误0、仅登录 POST、无执行重放。
见 [联机浏览器证据](evidence/cline-precode-20261106/monitor-browser.json)。

Worker 第二候选的10项技术执行完成10/10，首提案中位109.5s，对比基线109.2s没有改善证据。最终 Java+Worker 的5类各2次试验另计 verified，不覆盖前30项。

既有账户中间版本父任务 SUCCEEDED、Coding COMPLETED、6次模型和2次实际执行，报告 PARTIAL、业务 INCONCLUSIVE。
通过所有者 API 获取并校验3个不可变输入文件（115行），独立 Decimal 核对总资产、当日盈亏、成交金额和持仓市值匹配。
另校验停止 manifest 及原始归档 rows.jsonl/图形的 hash、大小、执行绑定；原始行自报 COMPLETE、原始 visualReport 自报 SUCCESS，而 Runtime 只读复核投影为 PARTIAL，两者区分保留。独立数值检查读取原始归档，不以会附加布局/引用信息的投影 hash 冒充原件 hash。
但要求五表仅生成两表，S6 应有12候选行仅有2行，出现明确禁止的持仓股数指标并假定金额标度为元；明确要求的方法均未使用（检查的七项可用配方函数都缺失，不要求调用不适用的方法）。
实际模型回执首轮直接 Python、没有任何 skill_read；完整1465字用户要求已存在于冻结目标，因此不能归因于需求丢失或正文复用失败。
该场景没有通过质量验收。原样保留报告，不修改数值或追加模型规则；旧成功用例是 deepseek-v4-flash，新例为当前默认 qwen3.7，不能作等模型耗时对照或证明退化因果。
见 [独立复核](evidence/cline-precode-20261106/account-intermediate-quality-review.json)、[方法源码证明](evidence/cline-precode-20261106/account-intermediate-code-proof.json) 和 [实际工具时序](evidence/cline-precode-20261106/account-intermediate-skills-trace.json)。
原件绑定见 [不可变归档证明](evidence/cline-precode-20261106/account-intermediate-archive-proof.json)。

最终 Java+Worker 版本受控账户任务已终止为 FAILED，耗时367.2秒，9次模型调用、352376 Tokens、4次实际 Python 执行。首次提案163.2秒；实际异常依次 SyntaxError、KeyError、NameError、TypeError，另有一次 PATCH_REQUIRED 拒绝整段替换。无有效结果、无最终报告；原有修复治理保持，未重提任务或改变模型美化结果。两次成功 Skill 正文读取共6376字节，另两次参考读取选择了不对应的 Skill ID，真实工具错误为 Invalid Coding callback binding；没有成功取得所要求的金融参考方法。完整用户要求仍在目标中，不能把失败解释为需求丢失或重复正文复用问题。语法无效版本的 AST 不可解析，不能把空 AST 的函数缺失误认为源码中确实不存在函数。其他版本可解析源码经所有者 API 校验 hash 后，三项同名函数存在但 AST 与交付配方不同，其余所需方法缺失。详见[终态证据](evidence/cline-precode-20261106/account-final.json)、[源码证明](evidence/cline-precode-20261106/account-final-code-proof.json)及[真实工具时序](evidence/cline-precode-20261106/account-final-skills-trace.json)。业务验收未通过；不以追加 Prompt 规则处理该失败。[真实 Skill 工具错误](evidence/cline-precode-20261106/account-final-skill-results.json)另列。线上浏览器显示失败节点、零报告图形、无页面错误；只触发工作区偏好 PUT，没有重放执行。私有截图和客户正文不发布，[浏览器元数据](evidence/cline-precode-20261106/account-final-browser.json)可复核。
上一轮华是科技前置模型超时的失败证据继续保留，不用本次合成任务替代其结果。

## 最终判定

源码收敛、工具契约、权限边界和监控测试已验证并部署。40条真实基准观测中最终10条全部技术完成，但首次编码未证明加速；合成报告仍有完成范围问题，受控财经任务失败。此 PR 保持草稿，不宣称专项全部达标。

公开测试日志采用 UTF-8，并统一换行与行尾空白以便代码审查；测试内容与结论未改，原始输出保留在私有运行目录。注册源文件和执行产物的完整性证明始终针对原始字节，未作此转换。
