# Coding 首次代码产出整改验证

诊断和调用链见 [根因报告](../../docs/audits/coding-progress-root-cause.md)。
保留 `@cline/agents`，沿用已整改的 SDK Editor、Patch、Checkpoint，不引入第二个 Agent Loop。

## 固定回归

- Windows：TypeScript/前端构建成功，225/225 Node 测试通过。
- Linux 首个候选镜像：224/224 Node 测试通过；禁网、只读文件系统、移除 capabilities。
- 真实 Docker Python：10/10 隔离回归通过，包括语法、缩进、类型、键错误、缺依赖、缺结果、结果格式、多轮修复及一万行程序的局部 SDK 补丁。
- Monitor 浏览器：登录/只读会话、真实代码哈希、XSS 文本显示、模型/Skills/事件、离线恢复、搜索、移动端溢出、退出均通过；新增首提案/有效代码、派发/确认执行、Skills/发现指标可见。

新增 9 项测试覆盖无进展建议而非强制终止、新证据重置停滞、重复失败识别、未知执行不计成功、阈值关闭、
已授权 Python Schema 直接披露、首轮直接代码、同一真实 SDK 会话跨阈值继续探索及监控白名单脱敏。
恢复会话已有有效版本时不触发“尚无代码”提示；停滞反馈要求针对最新版本做最小修复，避免误导重建程序。
原模型截断测试同时确认供应商原始 finish reason 与 SDK 映射分别保留。

## 真实模型试验方法

使用同一环境、同一 `qwen3.8-flash` 配置及同一组不可变合成资产，前后各运行 5 类 × 2 次任务。
分别为简单 CSV、真实二进制 Parquet、客户/订单多对一关联、28 天两地区每周聚合及数量加权单位值、
含真实 SyntaxError 的用户代码项目修复。无模型 mock，不伪造 Python 输出，不预置成功回执。
最大并发为 2；先完整完成 baseline，再在空闲时替换 Worker 并运行 candidate。
首轮 candidate 的发现另行保留；最终修订版本使用独立 final 批次再运行同样10项，主对比为 baseline 与 final，不能把中间版本的失败删除或并入最终版本。
顺序试验与小样本仅用于描述性对比，不能证明模型稳定性或生产 p95。

使用 `scripts/benchmark-coding-progress.py` 提交已有平台 API；
`scripts/collect-coding-progress.py` 只读汇集 Node 事件及 Java owner partition 中经 hash 校验的 journal/不可变模型回执。
丢失响应的 requestId 保留，不自动重新提交。全部失败、超时、未知用量均保留；缺失时间为 null，不记为 0。
完整私有回执留在主机发布目录，仓库仅收录脱敏时序、性能指标和复核结论。

时间指标从 Node 接受 Coding 任务计算：首次有效代码为 CODE_READY（版本/提案被接纳，不承诺语法或业务正确），
首次 Python 为 TOOL_STARTED，首次有效结果为真实 Python TOOL_FINISHED 且 resultAvailable=true。
另列 HTTP 总耗时，包括输入捕获、代码项目准备及结果归档。
Tokens 合计仅使用取得的供应商 usage；未返回调用的成本和用量仍待 Runtime 核对。

基础期望独立复核：CSV/Parquet 为 East=32、West=14、总和46；关联为 East=38、West=14、总和52且行数4；
复杂任务全期 amount=1218、quantity=140、加权值8.7，ISO 周 East 为10/56/105/154/81、West恰为两倍，
全周 quantity 为20/38/32/34/16；修复保留 `sum([1,2,3])` 并得到6，且先前真实失败与后续成功均有回执。
执行成功率与报告复核分开记录：Runtime 的 INCONCLUSIVE / NOT_EVALUATED 不等于业务正确性认证。
