# 通用 Coding Agent 真实验收记录

状态：**未通过整体验收**。本文件记录原任务结果，独立测试不回填 Agent、不修改交付代码、不增加原任务预算。

## 固定条件与原始证据

隔离部署，不替换原有服务。SDK 0.0.93，固定模型 `qwen3.8-flash`，每个任务最多 8 次模型调用、262144 Token、16 次工具调用、4 次代码执行、300 秒；单次输出上限 8192。Python 3.11.18 / Node 24.21.0；Sandbox 镜像 `sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828`，512 MiB、1 CPU、禁网、非 root、只读源码及根目录。真实计量不完整时保留 reservation，不能将已知 Token 或 CPU=0 当作完整消耗。

固定样本生成器 SHA256 `b73033fb163696fb4f778e495a19584787d1c6c348cff724ea5d246ff2e6a92f`。命令、只读测试与原始输入不因运行失败修改。复测创建新任务，保留旧任务及其原预算。

改进 Runtime（实验组 C）首次批次代码 `a4a7fbcd8`，cohort `C-general-20261107`。8 个任务均已停止，`executorStopped=true`、`reconciliationRequired=false`。完整任务 ID、预算、源码及 manifest Hash、独立执行容器及输出 Hash 见 [原始结果投影](evidence/native-coding-20261107/C-general-r1-summary.json)，文件 SHA256 `cb076510ba8be46bc91017abefe08d3eae6bd845af8638c1938b375013c32d58`。投影不含私有提示词、凭据或业务原始数据。

| 场景 | 原任务结果 | 独立代码复核 | 模型调用 | 已知 Token |
| --- | --- | --- | ---: | ---: |
| A 新建多文件项目 | FAILED | 21 项自建测试有 2 项失败 | 8 | 41364 |
| B 修复现有项目 | FAILED | 通过；只读测试未变 | 8 | 24693 |
| C 长源码局部修改 | FAILED | 通过；180 个无关函数原文保留 | 8 | 30951 |
| D 多文件重构 | FAILED | 通过；包含重构结构复核 | 8 | 见原始投影 |
| E SyntaxError | FAILED | 通过；只读测试未变 | 8 | 见原始投影 |
| E KeyError | FAILED | 通过；只读测试未变 | 8 | 见原始投影 |
| E TypeError / Decimal | FAILED | 通过；只读测试未变 | 8 | 见原始投影 |
| E NameError | FAILED | 通过；只读测试未变 | 8 | 见原始投影 |

任务完整率 **0/8**，独立代码复核 **7/8**。这两个分母和指标不可混用。所有独立复核均在原任务终止后执行；复核前后原任务 usage 及归档文件相同。Runtime 的 `validationStatus=INCONCLUSIVE` 原样保留。

## 根因与复测门槛

A 的模型自行扩展了规格及测试，涉及重音字符和 bytes 的两个自建测试未通过，不能删除这些测试制造成功。B 的实际原生回执中，三次命令已运行并通过全部测试，但 SDK 对象命令 `{command: ...}` 的 canonical Hash 未被完成门槛识别。C 及错误修复场景也必须逐项检查，不能仅凭相同失败状态认定同因。每轮授权清单、实际披露清单、Schema Hash、工具调用身份及停止证明已从原始回执投影保存在隔离证据目录，原始模型回执保留于现有证据库。

对象命令修复须保留原执行 Hash、冻结版本及独立 stop proof，只认可有限的完整声明命令形态；带额外参数、环境、管道或掩盖失败后缀不能认证。修复后使用相同模型、预算、样本和镜像复测。此前单个加法预检虽已完成且独立通过，不能抵消本批次失败。

## Worker 中断的真实故障注入

代码 `4bf74f649`，E-key 原始样本、相同模型及预算。实验专用 bootstrap 在 Silo 代码持久化成功、SDK 尚未收到 Editor ACK 时挂起响应，由外部控制器 kill 隔离 Worker。此 bootstrap 不被产品入口导入，不修改代码、不返回伪造工具结果或停止证明。第一次准备因 API 重启后旧登录令牌失效而收到 401，没有创建模型任务；保留该回执，重新认证后才开始实际实验。

真实任务 `native-f3486cb9e3d5d1346a147b6fcdd8941dc0659fc46edc13a3`：旧 Worker 独立观察退出 137；新 Worker 使用相同镜像，原任务 resume 返回 RUNNING。宕机前已用 2 次模型调用、2 次工具、1 次执行，恢复时结算中断工具，没有重置预算。事件记录 `SDK_CHECKPOINT_RESTORED`、SDK session `9cfd1a69-2909-4892-8f28-b82df22427af`。本轮没有另外捕获宕机前 session ID，故不声称已独立比对前后 ID；还需补齐此项观察。

原编辑 manifest `4e177009035e96356b4b0f278c467df5f3dd5550189da287e1aa2c2d7bb15136` 在恢复后源码及历史中保留。任务继续到累计 8 次模型调用、4 次执行后 FAILED；最后源码独立测试通过。恢复后的模型继续使用带管道的检查命令，没有完成声明验证，后续执行准入回报 BUDGET_EXCEEDED。**技术恢复有实际证据，完整恢复交付未通过**，不能把独立源码通过改写为任务成功。见 [原始故障结果](evidence/native-coding-20261107/worker-fault-r2-result.json)。

## 支持版本 Linux 组件检查

同 Sandbox 镜像、512 MiB、禁网、非 root、只读根目录，顺序执行安装版 SDK native 与 capability 测试：**68/68 通过、零跳过**。容器已停止，OOMKilled=false。日志 SHA256 `6e2a0e9a0c16a0f2f912a45fb77760136f65234b760187d35a216f043601be93`，见 [独立容器证明](evidence/native-coding-20261107/linux-component-4bf74f649.json)。这是组件验证，未使用真实模型，不能记为真实性能样本。

## 版本化执行治理后的第四批

代码 `0b62c544b`，同一八项样本、模型、8 次调用/4 次执行/300 秒预算及固定 Sandbox 镜像。仅在隔离验收三端启用新任务 `BASH_PIPEFAIL_V1`，生产及旧任务语义未改。启动前实际取得 Worker v2 profile 与 MCP 精确 wrapper/base Hash 能力，见 [部署证明](evidence/native-coding-20261107/pipefail-deployment-public.json) 和 [能力协商](evidence/native-coding-20261107/pipefail-capability-public.json)。

| 场景 | 原始任务状态 | 独立最终源码复核 | 模型次数 | 已知 Token | 实际执行次数 |
| --- | --- | --- | ---: | ---: | ---: |
| A 新建多文件项目 | FAILED | 失败 | 8 | 49532 | 4 |
| B 现有项目修复 | COMPLETED | 通过 | 4 | 10107 | 1 |
| C 长源码局部修改 | FAILED | 通过，180 个无关函数保留 | 8 | 38118 | 4 |
| D 多文件重构 | COMPLETED | 通过 | 6 | 33418 | 4 |
| E SyntaxError | COMPLETED | 通过 | 6 | 28505 | 4 |
| E KeyError | COMPLETED | 通过 | 7 | 35344 | 4 |
| E TypeError / Decimal | COMPLETED | 通过 | 5 | 21902 | 3 |
| E NameError | FAILED | 通过 | 8 | 40501 | 4 |

完整任务 **5/8**，最终源码 **7/8**。所有八个原始任务 executorStopped=true、reconciliationRequired=false；终态后再读取归档相同，独立复核不改变原 usage。[逐任务结果、文件 Hash、停止与预留](evidence/native-coding-20261107/C-general-r4-summary.json) 均保留。CPU、峰值内存和成本尚未完整计量；表中 Token 为已知回执累计，不能将 consumed CPU=0 当作真实零资源消耗。

C 的第三个真实执行已经通过固定测试，回执精确绑定声明命令、新策略及当前 manifest；模型后续继续检查，执行预算耗尽后仍请求测试，最终未结束原生 Loop。不能再把该失败统一归因于管道授权，也不能由 Runtime 改写为成功。压缩后只保留最近检查事实而未展示较早验证事实，是需要后续独立验证的上下文风险，尚不能断言其造成模型决策。A 的最终源码复核失败，必须保留失败代码与测试，不允许手工修补后计入原任务。

这些版本间批次不是同版本重复试验；5/8 不是整体通过，也不能用来宣布性能因果改善。SDK 参考组及恢复、权限专项继续单独测量。

## 尚未通过的最终门槛

真实 Worker 中断恢复、权限负向整链路、长任务压缩恢复、原生 Cline/旧 Runtime/改进 Runtime 三组完整对照尚未通过。当前旧 Runtime 通用项目入口及 MCP 动态 Schema 治理存在接口差异，不能以组件测试或模拟模型回放充当真实性能对照。尚未进入金融等业务集成。
