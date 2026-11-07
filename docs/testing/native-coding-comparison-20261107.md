# Coding Agent 原生对照与性能测量

状态：测量进行中；**不能宣布性能改善**。源码审计固定 SDK 0.0.93，官方提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`。完整官方 CLI 主线不在本轮 SDK 参考执行器测量范围。

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

这两轮代码版本不同，也包含模型随机性，不能当作同版本重复试验，更不能将 0→1 的观察解释为模型能力提升。逐任务模型调用、已知 Token、预留、停止及源码 Hash 见 [真实验收](native-coding-real-acceptance-20261107.md) 和结果投影。`validationStatus=INCONCLUSIVE` 保留。

首次有效代码时间采用终态后独立复核各 Silo 源码版本的方式：找到首个通过固定测试及额外约束的版本，以其原始归档创建时间减去原任务 startedAt。该指标表示正确源码首次归档时间，不表示模型当时已识别成功；历史探针不得把结果反馈给 Agent，且须验证任务 usage 不变。编辑版本数量单独记录，不能当作错误反馈修复轮次。

工具失败率使用已准入且有不可变回执的工具，检查 SDK `isError` 及输出条目的 `success=false`；未准入的预算拒绝另列。Token 计量不完整时同时报告预留，不填补未知消耗。尚未获得真实观测的指标使用未测量，不能用 SDK usage reset 后的数字替代 A2 累计值。

## 实验程序的组件检查

A-ref 工厂先在同镜像禁网容器内验证原生 Editor、Patch、相对及绝对文件读取，命令口只使用明确标识的 stub 检查转发。两种编辑方式均通过，容器已停止，日志 SHA256 `02330bc2970a7d76f0dad51faebc248bd58499409341cc3a8d7d8ff36c2cd06f`。这是实验程序自身的组件检查，未调用真实模型、Java 或 Sandbox，不计入 A-ref 成功率。

## 尚待测量

A-ref 真实模型批次及 B 的实际通用合同准入检查尚待完成。三组完整性能报告、同版本重复样本、首次有效代码时间、修复轮次与工具失败率仍需独立证据。不得把 C 的独立源码通过、组件测试或实验 stub 当作这些缺失测量的替代。
