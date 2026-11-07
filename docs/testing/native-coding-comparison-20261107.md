# Coding Agent 原生对照与性能测量

状态：已取得 SDK 参考和改进 Runtime 的固定样本实际测量；**不能宣布整体性能或因果改善**。源码审计固定 SDK 0.0.93，官方提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`。完整官方 CLI 主线不在本轮 SDK 参考执行器测量范围。

## 冻结的组别及差异

| 组别 | 编码执行器与循环 | 治理和环境 | 可比性边界 |
| --- | --- | --- | --- |
| A-ref SDK 参考 | 原 SDK `createDefaultTools` / `createDefaultExecutors`，保留同一 SDK Agent | 同一 Java 模型入口、合同、Silo、预算与 Python Sandbox；独立单任务容器的真实 `/workspace/code` tmpfs | 参考实验模块，不替换正式路径；原生文件权限较宽，外围容器无宿主文件、Shell 工具、网络工具及 Docker Socket。代码快照、执行隔离和声明验证保持相同 |
| B 当前 Runtime | 基准分支的 `runner.js`、`code_read`、`python_execute` / `repair.edits` | 保持原 SQL/Python/分析合同及 Sandbox | 先验证通用任务合同能否准入；若必须加入业务数据、rows.jsonl 或改变权限，则标注不可比，不伪造业务输入强行测量 |
| C 改进 Runtime | 同版 SDK 工具与 Agent；受控持久 workspace、Editor/Patch staging | 同一 Java 模型入口、合同、Silo、预算与 Python Sandbox | 路径、fence、只读文件、模型参数及不可变版本受治理 |

A-ref 只替换实验镜像内 workspace executor 连接，不改产品 `native-runner` 或创建新循环；读取、搜索、Editor/Patch 算法均来自已安装 SDK。命令仅转发到原受控 Sandbox。Editor 默认 Diff 上限及物理 cwd 与 C 的 Adapter 配置存在差异，须连同镜像/代码 Hash 一起记录。A-ref 单任务串行，不能据此比较并发吞吐。

固定模型 `qwen3.8-flash`；任务样本生成器 SHA256 `b73033fb163696fb4f778e495a19584787d1c6c348cff724ea5d246ff2e6a92f`。每个任务 8 次模型调用、262144 Token、16 次工具、4 次代码执行、300 秒，输出上限 8192；Sandbox 镜像 `sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828`，512 MiB、1 CPU、NONE 网络。运行前固定这些条件，保留失败任务。

## 当前 C 组实际观察

| 代码 | 固定样本数 | 完整任务 | 最终归档源码独立复核 |
| --- | ---: | ---: | ---: |
| `a4a7fbcd8` | 8 | 0/8 | 7/8 |
| `4bf74f649` | 8 | 1/8 | 8/8 |
| `3f64576fa` | 8 | 2/8 | 7/8 |
| `0b62c544b`（版本化 pipefail） | 8 | 5/8 | 7/8 |

这些批次代码版本不同，也包含模型随机性，不能当作同版本重复试验，更不能将完成率变化解释为模型能力提升。逐任务模型调用、已知 Token、预留、停止及源码 Hash 见 [真实验收](native-coding-real-acceptance-20261107.md) 和结果投影。`validationStatus=INCONCLUSIVE` 保留。

首次有效代码时间采用终态后独立复核各 Silo 源码版本的方式：找到首个通过固定测试及额外约束的版本，以其原始归档创建时间减去原任务 startedAt。该指标表示正确源码首次归档时间，不表示模型当时已识别成功；历史探针不得把结果反馈给 Agent，且须验证任务 usage 不变。编辑版本数量单独记录，不能当作错误反馈修复轮次。

工具失败率使用已准入且有不可变回执的工具，检查 SDK `isError` 及输出条目的 `success=false`；未准入的预算拒绝另列。Token 计量不完整时同时报告预留，不填补未知消耗。尚未获得真实观测的指标使用未测量，不能用 SDK usage reset 后的数字替代 A2 累计值。

## 实验程序的组件检查

A-ref 工厂先在同镜像禁网容器内验证原生 Editor、Patch、相对及绝对文件读取，命令口只使用明确标识的 stub 检查转发。两种编辑方式均通过，容器已停止，日志 SHA256 `02330bc2970a7d76f0dad51faebc248bd58499409341cc3a8d7d8ff36c2cd06f`。这是实验程序自身的组件检查，未调用真实模型、Java 或 Sandbox，不计入 A-ref 成功率。

## 尚待测量

A-ref 真实模型批次现已完成，结果见下表。完整 CLI、可直接比较的 B 合同及同版本重复样本仍缺失。不得把独立源码通过、组件测试或实验 stub 当作完整任务验收的替代。

## B 组实际准入检查

从基准提交 `e9eda1b9afd86bb2cecb9ade833f3b3023b38929` 导出、编译原始 Node Worker，保持同版 SDK，在 Linux 容器实际调用原 `createCodingRun` 构造入口。未执行模型，三种合同的实际拒绝证据见 [B 准入证明](evidence/native-coding-20261107/baseline-b-capability.json)：原样通用合同缺旧 Skills 生命周期授权；改用已有 workspace 工具而保留代码执行权限被原 workspace profile 拒绝；改用原分析工具、JSON_LINES 和 rows.jsonl，仍因没有获授权业务输入文件而拒绝。后两项仅作为边界探测，明确记录了合同差异，不加入伪造业务数据绕过限制。

因此 B 对本组通用项目任务为 **N/A：合同不可比**，不是 0/8，也不是模型失败；原业务编码能力仍存在。模型及工具远程请求均为 0，容器真实停止、exitCode=0 只证明这项构造入口探测完成。不能用该结果得出 A/C 相对 B 的性能因果结论。

## 历史版本复核

前三批共 24 个原始任务，均通过既有 UserAsset API 重新读取所有归档源码版本，核对内容、清单 Hash、父子版本和只读测试；每个版本再用相同冻结镜像和固定 holdout 运行，容器停止后再次读取归档。任务状态与原累计预算均保持不变。[完整逐版本证明](evidence/native-coding-20261107/C-history-r1-r3.json) 同时保留首次有效版本时间及最终版本退化现象：第三批 A 曾产生通过独立复核的版本，最终源码却失败，不能只挑成功历史版本宣布交付。

## 实际 A-ref / B / C 观察

A-ref 只替换隔离实验镜像的 workspace 工厂导入，仍使用同一原生 Agent、模型入口、预算、版本治理和 Sandbox。API/MCP 为 `0b62c544b`，参考工厂为 `67ebce71c` 中的实验模块；[实际镜像和代码 Hash](evidence/native-coding-20261107/reference-deployment-public.json) 记录该差异。首次准备在新 Worker 就绪前发生连接失败，**0 个权威任务、0 次模型调用**，见 [失败证明](evidence/native-coding-20261107/reference-startup-failure-public.json)。原失败目录保留；r2 使用新编号及完整原预算，表示第一批有效模型样本，不表示两批重复成功。

| 指标（各八项） | A-ref SDK 参考 | B 基准 Runtime | C 改进 Runtime 第四批 |
| --- | ---: | --- | ---: |
| 完整任务 | 2/8 | N/A，合同不可比 | 5/8 |
| 最终源码独立复核 | 7/8 | N/A | 7/8 |
| 既有错误/重构项目 B、C、D、四项 E 最终源码 | 7/7 | N/A | 7/7 |
| Runtime 模型调用累计 | 58 | 0，未进入模型测量 | 52 |
| 已知 Token 累计 | 277715 | N/A | 257427 |
| 已准入工具失败回执 / 全部已准入回执 | 25/73（34.25%） | N/A | 15/59（25.42%） |

以上是单批不同任务的观察，样本及模型存在随机性，执行器文件边界和默认 Diff 配置也有差异。不能把 5/8 对 2/8 宣称为 Runtime 稳定优于原生 Cline，更不能外推为完整 CLI 性能。两组新建项目 A 均失败；整体门槛均未通过。

| 场景 | A-ref 首个有效归档版本耗时 / 已观察错误反馈修复轮次 | C 首个有效归档版本耗时 / 已观察错误反馈修复轮次 |
| --- | --- | --- |
| A | 无有效版本 / 0 | 无有效版本 / 0 |
| B | 36.279 秒 / 0 | 7.800 秒 / 0 |
| C | 61.320 秒 / 0 | 39.592 秒 / 0 |
| D | 35.358 秒 / 0 | 35.399 秒 / 0 |
| E KeyError | 23.451 秒 / 1 | 19.229 秒 / 1 |
| E NameError | 46.344 秒 / 0 | 18.173 秒 / 1 |
| E SyntaxError | 41.056 秒 / 1 | 26.620 秒 / 1 |
| E TypeError | 20.679 秒 / 1 | 16.760 秒 / 1 |

修复轮次仅计入“实际错误反馈 + 独立确认旧版本不正确 + 修改后的不同版本独立通过 + 后续真实执行停止且退出0”的闭环。读取源码后直接修正但未执行错误版本记 0，不表示没有修复能力；编辑次数不能代替该指标。C 的四项 E 均观察到此闭环，但 NameError 原始任务仍失败。

[A-ref 逐项指标](evidence/native-coding-20261107/A-ref-r2-metrics.json)、[C 逐项指标](evidence/native-coding-20261107/C-general-r4-metrics.json) 保留完整预算和预留。[A-ref 回执](evidence/native-coding-20261107/A-ref-r2-receipts.json)、[C 四批回执最终投影](evidence/native-coding-20261107/C-general-r4-receipts.json) 包含每轮授权/披露、Schema Hash、工具结果归档再读取、不可变源码与实际 CODE 停止证明。模型调用按 Runtime 准入统计；供应商内部重试不等于额外 Runtime 模型调用，不能据此推算精确计费。未完整计量的 CPU/成本继续保留预留。

累计 **40 个原始 A/C 任务、121 个归档版本** 经独立重放和再次读取，原状态与 usage 不变；新补充的 [C 第四批历史](evidence/native-coding-20261107/C-general-r4-history.json) 和 [A-ref 历史](evidence/native-coding-20261107/A-ref-r2-history.json) 与前三批证据并列保留。它们证明归档代码可再读，不表示长期业务报告来源撤权或不可用问题已解决。

另对原始 history 响应逐一检查 version 从 0 连续递增、首版本 parent 为空、后继 parent 精确等于前一 manifest Hash 且 taskId 不变：[40 项/121 版本父子链证明](evidence/native-coding-20261107/archive-lineage-check.json)。导出的原始回执只包含白名单字段，24 份公共投影的字节数及 Hash 见 [导出清单](evidence/native-coding-20261107/manifest.json)；已核对全部条目，无私有提示词或凭据字段。
