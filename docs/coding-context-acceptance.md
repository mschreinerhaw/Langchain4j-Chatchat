# Coding 上下文整改验收记录

日期：2026-11-05。实现提交：`b7fbedf552bea300bcf98d992a909aa7fc69c3c2`。
分支：`feature/cline-context-performance-optimization`。

**状态：首批可观测性与安全投影实施完成；专项总体性能验收仍未通过。** 本次未发布生产环境、未切换Agent模型、未增加模型超时或减小Coding输出限额。

## 已执行检查

| 检查 | 命令/方式 | 实际结果 |
|---|---|---|
| 改造前Node基线 | `cd coding-agent-node; npm.cmd test` | 189/189通过 |
| 最终Node构建与回归 | 同上 | 198/198通过，0跳过 |
| Java模型桥/回调/用量回执 | optimization文档所列Maven命令 | 42/42通过，0跳过 |
| 监控浏览器 | `npm.cmd run test:browser` | 通过，桌面/移动、只读登录、代码hash、XSS、离线恢复、新诊断折叠区、未知值展示均检查 |
| 长上下文 | 实际SDK 0.0.93 prepareTurn/state-aware测试 | 压缩前缀复用、序列化恢复、新尾部保留、失效前缀回退、工具配对、完整报告保留通过 |
| 代码修复一致性 | 原生SDK同一循环，优化开关两种配置 | source读取、陈旧hash拒绝、精确补丁、2次执行后实际报告确认通过 |
| checkpoint上限 | 普通262144/文件分析750000字符的定向边界 | 超限仅去除可重算sidecar，规范消息/代码不删除，不提高Java上限 |
| HTTP阶段与失败 | 实际loopback HTTP头/体分段、截止/取消、非法JSON | 通过；不以callbackFirstEvent充当provider TTFT |
| 线上历史基线 | 只读导出到baseline.json | 256任务/1550调用，4次>300秒；供应商firstToken已知0次 |
| 真实模型隔离对照 | `CodingWorkspaceNodeIntegrationTest#authorizedRealModelComparison`，同模型、任务hash、profile与环境 | 冻结提交复验的最终数字见comparison.json；不以单次样本估P95 |
| Java+Node+H2+SQLGlot确定性集成 | 同类 `#sharedWorkerReadsContextRepairsSourceAndCommitsVersionedCandidatesWithoutExecution` | 开/关各4/5通过；同一个Hive用例工具次数断言失败，详见下文 |

日志路径（本地忽略目录）：`target/coding-context-node-final.log`、`coding-context-browser-final.log`、`coding-context-java-tests.log`、`coding-context-real-{before,after}.log`、`coding-context-deterministic-{before,after}.log`。

## 同样失败的既有计数断言

Hive自主探索样本的实际任务 **COMPLETED**，modelCalls=5，toolCalls=6，而测试要求toolCalls=5。关闭与开启优化都出现同一断言失败，不能报告“全部集成测试通过”。

源码中sqlExploration会预取一次workspace_read；这个确定性provider fixture的第一轮又固定调用workspace_read，之后两次metadata和两次proposal，共6次工具。该fixture未复用已预取上下文。本次保留原断言和失败结果，未把6改成5、未放宽通过条件，也未在生产工具层偷偷抑制模型请求。它不属于Token/P95目标的有效通过样本。

## 真实对照范围

测试保留 `qwen3.8-omni-flash`，Java运行于隔离H2库，Node是独立子进程，SQLGlot/Python parser是真实工具链。通过临时loopback与SSH relay调用服务器既有供应商端点，真实供应商凭据留在服务器内存中。未替换线上Worker、未修改已发布模型，生产业务数据不进入样本。

冻结实现提交后的最终复验：

| 指标 | 关闭优化 | 开启优化 |
|---|---:|---:|
| 完成任务 | 4/4 | 4/4 |
| 实际输入Token | 35,378 | 35,642 |
| 实际输出Token | 4,558 | 5,089 |
| 模型调用次数 | 11 | 11 |
| 四任务累计墙钟时间 | 97,472 ms | 103,722 ms |
| P95改进结论 | 不估计 | 样本不足 |

输入增加约0.75%，累计耗时增加约6.41%。这四个短工作负载未触发长历史压缩，也不使用单脚本code_read，因此不能证明长上下文治理带来收益。先前探索轮曾出现输入39,503→32,473而耗时86,230→96,077 ms；冻结复验没有复现Token下降，故不能选择有利的一轮宣称达标。两轮都不构成足量匹配样本的性能结论。

环境：Windows本地隔离Java/Node，Corretto OpenJDK17.0.19、Node25.0.0、Python3.14、SQLGlot30.23.0、Cline0.0.93；同一服务器供应商relay与同一发布模型。来源文件和实际调用的细项在comparison.json中。

冻结提交的一个阶段定位样本：mysql-fast开启组调用#1，Node→Java连接2ms、响应头7294ms、响应体0ms（毫秒取整），Java模型调用7248ms、本地执行器等待6ms，provider firstToken为null。这次等待主要位于Java模型调用区间；不能据此进一步声称供应商内部排队或生成各耗时多少。

四个工作负载为SQL full/fast、Python full/fast源代码候选生成；每种profile在开/关之间单独匹配。候选文件通过版本、Schema、语法和证据协议校验，**未在Python Sandbox执行，因此不能宣称数值分析/图表报告业务正确性已验收**。成本未取得时保持null，不把预算账本的0当实际成本。

请求字节压缩另有 `node scripts/benchmark-context-projection.mjs`：确定性1200行代码样本中，历史源码完整保留，仅省略新注入的重复源码。该实验约50%请求字节减少，未调用供应商，不代表真实Token或延迟下降。

## 功能场景剩余矩阵

| 任务书场景 | 本批覆盖 | 尚缺的真实验收 |
|---|---|---|
| 简单CSV、多文件关联 | 现有固定回归的数值/交付门禁继续通过 | 同数据真实模型+实际Sandbox前后成对运行 |
| Python错误修复 | 原生SDK精确补丁与Java parser集成 | 真实模型在隔离Sandbox的执行失败恢复 |
| SQL自主元数据探索 | 实际Java/H2元数据、Node、SQLGlot；已披露fixture计数失败 | 去除fixture重复读的独立整改与真实长探索对照 |
| 图文报告 | 完整报告不被压缩伪造、artifact/layout/reference门禁继续通过 | 真实图表、证据、报告全文一致性对照 |
| 缺少业务定义 | 现有INPUT_REQUIRED固定样本门禁 | 真实模型同输入对照 |
| 长上下文 | 真SDK压缩和状态恢复，无额外LLM | 多个真实长任务的实际Token、P95和重复读取率 |
| Worker重启 | checkpoint跨Agent恢复、未闭合工具防护 | 杀进程/重启与未知模型响应、非幂等执行的完整线上恢复 |

## 后续验收门槛

1. 30%实际输入Token和30%P95、50%重复工具读取目标尚未证明。短任务的波动不能证明或推翻长上下文治理效果。
2. 尚未默认开启agentic阶段摘要；须将SDK summarizer接入同一Java预算/取消链后再试验，不能引入未计费的摘要调用。
3. 大型报告按章节/引用读取仍需保留完整取回及hash校验后独立实施；当前继续保留完整最新报告用于审核。
4. 供应商内部queue、连接/响应头、真实TTFT在现有非流式回执中未知；新增Node→Java阶段诊断不能冒充这些指标。
5. 只有代表性受控回归、权限/证据/恢复验收和足够匹配样本均满足后，才开启生产优化开关。

本批结论是“已建立可归因观测并验证原生安全投影能力”，不是“专项性能目标已全部达标”。
