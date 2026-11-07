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

## 剩余验收门槛

真实 Worker 中断恢复、权限负向整链路、长任务压缩恢复、原生 Cline/旧 Runtime/改进 Runtime 三组完整对照尚未通过。当前旧 Runtime 通用项目入口及 MCP 动态 Schema 治理存在接口差异，不能以组件测试或模拟模型回放充当真实性能对照。尚未进入金融等业务集成。
