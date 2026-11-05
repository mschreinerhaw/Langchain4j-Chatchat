# Coding 首次代码产出整改验证

**验收状态：诊断、代码整改、监控与部署已交付；性能目标和财经端到端验证未通过，专项不能关闭。**

诊断和调用链见 [根因报告](../../docs/audits/coding-progress-root-cause.md)。
保留 `@cline/agents`，沿用已整改的 SDK Editor、Patch、Checkpoint，不引入第二个 Agent Loop。

## 固定回归

- Windows：TypeScript/前端构建成功，加入 SDK 计划响应完成保护后227/227 Node 测试通过。
- Linux 首个候选镜像：224/224 Node 测试通过；禁网、只读文件系统、移除 capabilities。
- Linux 第二轮候选镜像：225/225 Node 测试通过；最终 SDK guard 候选227/227，同样的隔离边界，真实 Docker Python 10/10 再次通过。
- 真实 Docker Python：10/10 隔离回归通过，包括语法、缩进、类型、键错误、缺依赖、缺结果、结果格式、多轮修复及一万行程序的局部 SDK 补丁。
- Monitor 浏览器：登录/只读会话、真实代码哈希、XSS 文本显示、模型/Skills/事件、离线恢复、搜索、移动端溢出、退出均通过；新增首提案/有效代码、派发/确认执行、Skills/发现指标可见。

新增 9 项测试覆盖无进展建议而非强制终止、新证据重置停滞、重复失败识别、未知执行不计成功、阈值关闭、
已授权 Python Schema 直接披露、首轮直接代码、同一真实 SDK 会话跨阈值继续探索及监控白名单脱敏。
恢复会话已有有效版本时不触发“尚无代码”提示；停滞反馈要求针对最新版本做最小修复，避免误导重建程序。
原模型截断测试同时确认供应商原始 finish reason 与 SDK 映射分别保留。
另有两项原生 SDK 回归覆盖计划文本不能提前完成，以及继续执行仍受原预算约束。

## 真实模型试验方法

使用同一环境、同一 `qwen3.8-flash` 配置及同一组不可变合成资产，前后各运行 5 类 × 2 次任务。
分别为简单 CSV、真实二进制 Parquet、客户/订单多对一关联、28 天两地区每周聚合及数量加权单位值、
含真实 SyntaxError 的用户代码项目修复。无模型 mock，不伪造 Python 输出，不预置成功回执。
最大并发为 2；前一批完整完成后，才在空闲时替换 Worker 并启动下一批。
每轮候选发现都保留；最终 SDK guard 版本使用独立 verified 批次运行同样10项，主对比为 baseline 与 verified，不能删除中间失败。

| 批次标识 | Worker 源码 | 内容 |
|---|---|---|
| baseline | 80ef89096（Worker SDK复用基线） | 已有 Editor/Patch/Checkpoint 整改 |
| candidate | a64c8be51 | 首轮准备路径收敛与进展监控 |
| final（第二轮候选，非最终交付） | 6f494d918 | 两种分析模式的完整 Python 格式说明与恢复指导 |
| verified | 2813524e8 | 使用原生 SDK completionGuard 补上计划 STOP 边界 |

顺序试验与小样本仅用于描述性对比，不能证明模型稳定性或生产 p95。

使用 `scripts/benchmark-coding-progress.py` 提交已有平台 API；
`scripts/collect-coding-progress.py` 只读汇集 Node 事件及 Java owner partition 中经 hash 校验的 journal/不可变模型回执。
丢失响应的 requestId 保留，不自动重新提交。全部失败、超时、未知用量均保留；缺失时间为 null，不记为 0。
完整私有回执留在主机发布目录，仓库仅收录脱敏时序、性能指标和复核结论。

时间指标从 Node 接受 Coding 任务计算：首次有效代码为 CODE_READY（版本/提案被接纳，不承诺语法或业务正确），
另外统计 firstNewCodeMs：新建分析取首个 CODE_READY，用户已有代码修复取首次 parentCodeVersion>=1 的补丁，避免把原代码复用冒充新增代码。
首次 Python 为 TOOL_STARTED，首次有效结果为真实 Python TOOL_FINISHED 且 resultAvailable=true。
另列 HTTP 总耗时，包括输入捕获、代码项目准备及结果归档。
Python 实际次数取 Java journal 中 kind=CODE、具有 stopProofRef 的 actual.codeExecutions，另列工具回执数及未确认停止的操作数；不从模型请求或派发次数推断执行成功。
Tokens 合计仅使用取得的供应商 usage；未返回调用的成本和用量仍待 Runtime 核对。

基础期望独立复核：CSV/Parquet 为 East=32、West=14、总和46；关联为 East=38、West=14、总和52且行数4；
复杂任务全期 amount=1218、quantity=140、加权值8.7，ISO 周 East 为10/56/105/154/81、West恰为两倍，
全周 quantity 为20/38/32/34/16；修复保留 `sum([1,2,3])` 并得到6，且先前真实失败与后续成功均有回执。
执行成功率与报告复核分开记录：Runtime 的 INCONCLUSIVE / NOT_EVALUATED 不等于业务正确性认证。

## 部署与可观察性

最终 Worker 镜像 `runtime-os-coding-agent:coding-progress-20261105-r3`，源码 `2813524e8a6c0a1cc1326f46d1e1cf406e1ca22b`。
镜像、模块 hash、隔离回归见 `evidence/coding-progress-20261105/final-candidate.json`；
实际切换及签名回调验收见 `final-deployment.json`，构建记录中的 `trafficSwitched=false` 仅表示构建阶段未切换流量。
只替换 Coding Worker，API、MCP、数据库等其它容器 ID 保持不变；原容器保留用于回滚。

第一次最终切换因 Docker inspect 返回的 Mounts 顺序变化触发安全回滚，原版本恢复运行。
另建不启动的临时容器复现：三项挂载全部字段相同，只有列表顺序不同。
部署脚本现按 Destination 排序后逐字段比较，仍验证 source、RW、propagation 及全部 HostConfig 边界；再次切换成功。
没有以忽略挂载权限或跳过检查绕过失败。

最终版本已完成的真实 CSV 任务 `analysis-coding-2061a3da9a8bdb18633777120d0ecefa362370f8c2e8d675ec24f8820802451e`
经实际 Monitor 浏览器复核：构建 hash 一致，提案、确认执行、Skills/发现、逐轮工具及两种 finish reason 可见，
终态等待显示“已结束”，无浏览器错误，也未触发任务执行请求。见 `monitor-live.json` 与两张 Monitor 截图。

真实无进展观察见 `advisory-live.json`：两个最终版本样本出现无新结果/重复参数失败后，
MODEL_STARTED 记录 progressFeedback=true，随后仍在同一会话继续到第6轮；没有按提醒阈值终止。
真实复杂任务 `analysis-coding-fd501235021b7fb3fd9d32f940c9dd59b4b7bc57a2bec1c1fada85d4055e6d8d`
第二轮返回仅计划的 STOP，canonical messages 保留 SDK guard 提醒，第三轮继续提交 Python 代码。
跨轮保持同一 SDK session、同一 fence，详见 `completion-guard-live.json`；后续技术修复与最终验收另按实际回执统计。

## 结果正确性与性能口径限制

执行完成率指 Node COMPLETED、结果真实可用且有 Python 回执；它不是全部业务叙述正确率。
baseline 的 CSV/Parquet/关联目标值以及两个修复任务的数值6均复核一致。
两个 baseline 复杂任务的核心周聚合表与期望值一致，但各自含有已确认的叙述错误：
complex-0 的摘要把 West quantity 占比写成0%，正文表实际为50%；
complex-1 把2026-10-01写成周日（实际周四），并声称4.421053不等于4.421053。
因此即使 baseline 执行完成为10/10，也不能报告完整业务正确率100%；已确认叙述失败至少2/10。

第二轮候选 complex-1 的目标聚合数值正确，但“周单位值不受边界影响”的概括缺少依据：改变周内日期集合会改变该比值。
最终 verified/complex-0 的目标周聚合数值正确，但“分组合计”行在“天数”列写56（明细行数），正确覆盖天数为28。
这属于表头/数值含义错位，不能因为全部执行回执完成就称整份报告正确。
这些模型输出均保留原始回执，没有为美化成功率改写报告。
独立复核仅覆盖明确的目标计算、关键表格与要点叙述，不构成对每个环境/hash/审计附录声明的全面认证。

## 全部40项真实试验结果：性能验收未通过

以下耗时单位为秒，除模型调用总数和 Tokens 合计外均为任务中位数。括号内为观测数，缺失不补零。
详见 [40项逐任务指标](evidence/coding-progress-20261105/performance.json)。

| 批次 | 执行完成 | 首次接纳代码 | 首次新代码 | 首次 Python 派发 | 首次可用结果 | HTTP 完整耗时 | 模型调用总数 / 中位数 | 已知 Tokens 合计 | 缺失 usage 调用 | 修复成功 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 10/10 | 95.6 (10) | 107.2 (10) | 95.7 (10) | 155.6 (10) | 166.8 (10) | 56 / 5 | 1,495,513 | 0 | 2/2 |
| candidate | 8/10 | 98.8 (9) | 170.1 (9) | 98.9 (9) | 268.5 (8) | 317.2 (10) | 52 / 5.5 | 1,225,634 | 1 | 2/2 |
| final | 8/10 | 97.5 (9) | 102.5 (9) | 97.5 (9) | 109.3 (9) | 152.9 (10) | 54 / 4 | 1,201,631 | 1 | 2/2 |
| verified | 10/10 | 109.4 (10) | 142.0 (10) | 109.4 (10) | 429.0 (10) | 444.4 (10) | 59 / 6 | 1,434,863 | 1 | 2/2 |

基线与最终版本均为执行完成10/10、修复2/2，但首次新代码107.2→142.0秒，首次可用结果155.6→429.0秒，HTTP 总耗时166.8→444.4秒。
模型调用总数56→59，中位数5→6。首次接纳代码也从95.6升到109.4秒。**没有证据证明本轮整改缩短代码生成或整体等待；速度目标未达成。**
最终版本有一次供应商超时且 usage 未返回，已知 Tokens 合计只是下界，不能据此声称节省总 Tokens 或费用。
逐任务已知 Tokens 中位数为87,430.5→133,526，不能仅展示合计看似下降。
三批候选全部保留；中间批的较快部分指标伴随失败/缺失观测，不能挑选作最终性能结论。

下表每类均为2项任务的中位数，以“基线 → 最终”表示；修复首次新代码排除了原始代码复用。

| 类型 | 首次新代码秒 | 首次 Python 秒 | 首次可用结果秒 | HTTP 完整秒 | 模型调用中位数 | 执行完成 |
|---|---:|---:|---:|---:|---:|---:|
| csv | 68.7 → 125.8 | 68.7 → 125.9 | 91.9 → 145.9 | 108.6 → 161.3 | 3.5 → 3.5 | 2/2 → 2/2 |
| parquet | 117.1 → 96.1 | 117.1 → 96.2 | 121.2 → 396.5 | 127.8 → 416.6 | 2 → 5.5 | 2/2 → 2/2 |
| join | 95.6 → 96.4 | 95.7 → 96.5 | 134.9 → 238.1 | 149.7 → 246.5 | 5 → 5 | 2/2 → 2/2 |
| complex | 129.2 → 109.5 | 129.2 → 109.5 | 444.3 → 783.4 | 475.9 → 806.2 | 8 → 9 | 2/2 → 2/2 |
| repair | 327.4 → 597.0 | 73.4 → 109.4 | 606.6 → 658.7 | 784.1 → 826.5 | 9.5 → 6.5 | 2/2 → 2/2 |

独立目标数值复核两批各10/10，但已确认 baseline 有2份报告叙述错误，verified 有1份复杂报告天数错误。
不能把执行完成率或目标数值命中率称为整份报告成功率；在本次复核范围内，最终完整质量通过率至多9/10。
报告内容 hash 与具体问题见 [复核记录](evidence/coding-progress-20261105/result-review.json)。

## 华是科技财经 Agent 端到端验证：未通过

在最终镜像上通过数据探索空间新建节点，采用实际的 financial-news-agent（财经资讯分析 Agent）、默认 qwen3.8-flash，
自主选择已授权资料和 Skills；没有注入行情或替换成基准任务结果。
节点 [47fda311-45dc-4ad7-9fd7-a3b4abed4ca4](http://192.168.195.221:18100/#/w/data-exploration/dataExploration?exploration=85affabc-70b4-4543-b190-39578af5ffd2&node=47fda311-45dc-4ad7-9fd7-a3b4abed4ca4)。

2026-11-05 15:51:12.383 UTC 提交，15:56:14.737 UTC 终止；结果回执总耗时302,164ms，错误为 request timed out / AGENT_RUNTIME_FAILED。
Java 事件显示任务识别和工具发现已完成；随后约300秒未取得前置模型结果，尚未执行 web_search，Coding task 数为0。
供应商内部原因及该次未知用量未查明；不能据此认定是 Coding 循环、Skills 强制读取或 Schema 问题，也不能宣称财经任务修复成功。
未自动重新提交未知供应商调用，保留原节点和事件。没有可用行情报告、趋势图或最新价格结论。

实际浏览器重开节点：正确显示“分析未完成 / 本次分析未交付成果”，选中节点存在，图表0，浏览器错误0，
除工作区偏好 PUT 外没有发起新的任务执行 POST/DELETE。见 [Runtime 脱敏时序](evidence/coding-progress-20261105/finance-final.json)
及 [浏览器回执](evidence/coding-progress-20261105/finance-browser.json)。
最终 Worker 仍为 source 2813524e8、image sha256:02bca821bc7ab6b4b9061e7f4474c491c30def56bfec78127c30c02af4a2670e，running/healthy。

## 验收结论与剩余工作

已交付真实原任务调用链、准备路径与格式说明收敛、原生 SDK 完成保护、轻量进展反馈、Monitor 可解释性、源码和自动化/真实模型证据。
保留 SDK 自主工具与 Skills 选择，不新增 Agent Loop，不修改模型配置、不加预算、不削弱数据授权或结果合同。
原任务第五轮为何在供应商内部耗时300秒仍无响应无法由当前回执解释；重复执行、长代码生成、参数错误及后续修复仍造成显著等待。
本轮不能关闭性能专项：速度未达标、业务报告仍有错误、财经端到端验证失败。
后续需要结合供应商 request trace 排查响应超时，并基于实际失败程序和逐次提案继续改善遵循输出合同与局部修复的质量；
再按同一不可变资产和模型配置重复多样本验证，不以继续堆叠 Prompt 或放宽验证替代证据。
