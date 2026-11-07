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
| `3f64576fa` | 8 | 2/8 | 7/8 |

这两轮代码版本不同，也包含模型随机性，不能当作同版本重复试验，更不能将 0→1 的观察解释为模型能力提升。逐任务模型调用、已知 Token、预留、停止及源码 Hash 见 [真实验收](native-coding-real-acceptance-20261107.md) 和结果投影。`validationStatus=INCONCLUSIVE` 保留。

首次有效代码时间采用终态后独立复核各 Silo 源码版本的方式：找到首个通过固定测试及额外约束的版本，以其原始归档创建时间减去原任务 startedAt。该指标表示正确源码首次归档时间，不表示模型当时已识别成功；历史探针不得把结果反馈给 Agent，且须验证任务 usage 不变。编辑版本数量单独记录，不能当作错误反馈修复轮次。

工具失败率使用已准入且有不可变回执的工具，检查 SDK `isError` 及输出条目的 `success=false`；未准入的预算拒绝另列。Token 计量不完整时同时报告预留，不填补未知消耗。尚未获得真实观测的指标使用未测量，不能用 SDK usage reset 后的数字替代 A2 累计值。

## 实验程序的组件检查

A-ref 工厂先在同镜像禁网容器内验证原生 Editor、Patch、相对及绝对文件读取，命令口只使用明确标识的 stub 检查转发。两种编辑方式均通过，容器已停止，日志 SHA256 `02330bc2970a7d76f0dad51faebc248bd58499409341cc3a8d7d8ff36c2cd06f`。这是实验程序自身的组件检查，未调用真实模型、Java 或 Sandbox，不计入 A-ref 成功率。

## 尚待测量

A-ref 真实模型批次、三组完整性能报告及同版本重复样本尚待完成。首次有效代码时间已有逐历史版本实际复核；修复轮次与工具失败率仍需结合原始执行回执判定。不得把 C 的独立源码通过、组件测试或实验 stub 当作完整任务验收的替代。

## B 组实际准入检查

从基准提交 `e9eda1b9afd86bb2cecb9ade833f3b3023b38929` 导出、编译原始 Node Worker，保持同版 SDK，在 Linux 容器实际调用原 `createCodingRun` 构造入口。未执行模型，三种合同的实际拒绝证据见 [B 准入证明](evidence/native-coding-20261107/baseline-b-capability.json)：原样通用合同缺旧 Skills 生命周期授权；改用已有 workspace 工具而保留代码执行权限被原 workspace profile 拒绝；改用原分析工具、JSON_LINES 和 rows.jsonl，仍因没有获授权业务输入文件而拒绝。后两项仅作为边界探测，明确记录了合同差异，不加入伪造业务数据绕过限制。

因此 B 对本组通用项目任务为 **N/A：合同不可比**，不是 0/8，也不是模型失败；原业务编码能力仍存在。模型及工具远程请求均为 0，容器真实停止、exitCode=0 只证明这项构造入口探测完成。不能用该结果得出 A/C 相对 B 的性能因果结论。

## 历史版本复核

前三批共 24 个原始任务，均通过既有 UserAsset API 重新读取所有归档源码版本，核对内容、清单 Hash、父子版本和只读测试；每个版本再用相同冻结镜像和固定 holdout 运行，容器停止后再次读取归档。任务状态与原累计预算均保持不变。[完整逐版本证明](evidence/native-coding-20261107/C-history-r1-r3.json) 同时保留首次有效版本时间及最终版本退化现象：第三批 A 曾产生通过独立复核的版本，最终源码却失败，不能只挑成功历史版本宣布交付。
