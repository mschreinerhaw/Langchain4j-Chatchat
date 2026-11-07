# 通用 Coding Agent 整改审查入口

分支：`feature/runtime-native-coding-agent-20261107`。基准：`fix/runtime-data-exploration-delivery-20261106` 的 `e9eda1b9afd86bb2cecb9ade833f3b3023b38929`。本轮不修改金融分析修复决策，不重复处理 `5ba60b2ac` 的旧资源工具授权。

运行期间远端基准推进至 `167dfedf5`，已用合并提交 `23c077f6d` 保留其新增工作，并完成兼容回归；本轮未继续新增金融专用修复。实际模型任务按原冻结镜像与提交报告，未把合并后的组件测试冒充这些批次的整体验收。

**整体状态：未通过最终验收，不进入业务集成。** 最新固定通用批次完整任务 5/8、最终源码独立复核 7/8；失败任务及原预算、停止和源码证据完整保留。原生 SDK 参考对照和恢复/隔离专项结果分别记录，不能用局部成功替代全部门槛。

## 交付索引

| 交付 | 文件与审查重点 |
| --- | --- |
| 官方源码与 SDK 能力矩阵、工具/执行器清单 | [源码基线](cline-native-source-baseline-20261107.md)、[安装包证据](cline-sdk-0093-package-baseline-20261107.json)：锁文件、五个 npm 包、官方提交、源码路径和调用链 |
| Runtime 差异、缺陷及最小修复 | [代码级根因矩阵](native-coding-root-cause-matrix-20261107.md)：先证据、再修复；区分 Adapter、执行语义、上下文与模型行为 |
| 独立工作区及治理设计 | [架构设计](../design/native-coding-agent-20261107.md)：原生 Agent Loop、原生编辑算法、Silo 持久版本、任务隔离及受控 Sandbox |
| 代码与组件回归 | 当前独立分支提交；[修复前后证据](../testing/native-coding-component-regression-20261107.md)：Node、Java、HTTP、SDK 和真实执行器分别验收 |
| 通用编码真实验收 | [真实任务报告](../testing/native-coding-real-acceptance-20261107.md)：固定 A–E、逐任务文件 Hash、原始停止证据及历史版本复核 |
| 原生/现有/改进对照 | [对照与性能报告](../testing/native-coding-comparison-20261107.md)：A-ref 是同版 SDK 参考，B 合同不可比；严禁混称完整原生 CLI 或推导模型因果结论 |
| 安全、兼容及恢复 | [专项报告](../testing/native-coding-security-recovery-20261107.md)：真实 API 拒绝、Worker 故障、权限与未通过项；旧业务入口保留 |
| 后续插件与业务接入 | [接口说明](../design/native-coding-integration-contract-20261107.md)：独立项目 API、归档读取、发布 Skills、MCP 尚未接通的边界 |

## 代码审查重点

1. `native-workspace.js` 使用同版 SDK 的读、搜、Editor 和 Patch；Runtime 先校验路径、任务和文件权限，以持久提交 ACK 发布代码；缓存丢失从 Silo 重建。
2. `native-runner.js` 仅装配一个原生 Agent。错误、执行结果、编辑和再次测试仍由同一模型自主决定；Java 不提供固定修复步骤。
3. Java 原生任务入口和命令 Adapter 冻结请求、模型连接、预算、环境及不可变代码树；原 `python_execute` 兼容路径保留，不开放 Worker 宿主 Shell。
4. 执行经现有 MCP Python Sandbox，返回真实输出和独立停止证明；缺结果、不同版本或未确认停止均不认证。新 pipefail 策略默认关闭，三端能力及 helper Hash 必须精确协商，旧任务语义不变。
5. A2 工具准入时点、父子执行核对、恢复 fence、已停止回执回放和过期停止通道均有独立修复前后测试。取得新 fence 不表示旧执行停止；SDK Restore 不重置 Runtime 累计预算。

## 当前不能批准的范围

不能批准“全部原生能力已完成”或推广到金融等业务。仍有真实任务失败；B 无法在不变更任务合同的情况下直接比较；MCP 动态 Schema 的 Java 治理绑定、发布 Skills 的完整长任务/撤回验证、所有服务同时重启及原执行面的精确 CPU/磁盘/输出硬限额均有未通过项。具体观察与测量缺口以各专项报告为准。

## 回滚

所有新原生入口默认关闭。先关闭新准入，保持已有任务可取消、恢复、结算与读取归档；逐个取得 MODEL/TOOL/CODE 的独立停止证据后再回滚 Worker/API/MCP。新策略任务排空前不得换回不认识 v2 profile 的旧 Worker。保留 Silo 历史、原始模型和执行回执及旧业务路径，不删除未知执行或清零预算。
