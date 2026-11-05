# Cline SDK 原生能力回归与部署记录

日期：2026-11-05。审查 main `0693c15fd9184f112d0111f3085b16943841a72d`，
生产实现 `3e28607b9`，分支 `refactor/cline-sdk-reuse-20261105`。
本机、锁文件及候选镜像的五个 Cline 包均为 **0.0.93**。
保持 `@cline/agents`；没有升级到 0.2.0 或强制迁移 ClineCore。

## 变更与复现

涉及 `sdk-adapter.editProgram`、`python-project.proposeProject`、`CodeHistory`、
`runtime-model.stream`、`runner.forward/completionGuard`。
可复现问题为：SDK Editor 外的自研预演对 LF 锚点/CRLF 文件处理不同；多文件另有一份替换算法；
授权截断被映射为 stop；过时检查点片段状态重复保存；普通进度故障会中断执行。
上游固定版本源码、Issue/PR 状态及退出条件见
[上游调查](../audits/cline-upstream-issues-prs.md)和[台账](../audits/cline-redundancy-removal-matrix.md)。
这些是本地集成问题，没有确认 SDK 缺陷，也未采纳未合并 PR。

## 已执行的命令

Windows 仓库根目录构建、测试：

```powershell
# 修改前、修改后分别执行，pretest 包括 TypeScript 构建
npm.cmd --prefix coding-agent-node test
Push-Location coding-agent-node
node --test --test-concurrency=4 test/*.test.js
Pop-Location
mvn.cmd -pl chatchat-api -am test '-Dtest=CodingExecutionFeedbackServiceTest,ClineCodingAdapterRecoveryTest,ClineCodingAdapterDispatchTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true'
git diff --check
```

Linux 发布目录（先按仓库相对路径打包 dist/src/test/fixtures/integration/skills/scripts/web 及 Python project adapter，
与准备工具一同传入私有发布目录）：

```sh
python3 prepare-cline-sdk-reuse.py \
  --release-dir /opt/chatchat-releases/cline-sdk-reuse-20261105-r1 \
  --source-commit 3e28607b9 \
  --archive-sha256 0c3df0df7875662a703ffa9834349aa17e13bb26b176cd0d0024f4648e13f158
# 上述工具在离线候选镜像内执行全部 Node 测试，并运行：
python3 test-cline-sdk-sandbox.py \
  --source-dir /opt/chatchat-releases/cline-sdk-reuse-20261105-r1/verify \
  --node-image runtime-os-coding-agent:sdk-reuse-20261105-r1
```

| 验证层级 | 结果 | 实际证明范围 |
|---|---|---|
| main Node 基线 | 210/210 | 当前已有行为和安全边界 |
| 整改后 Windows Node | 216/216，0 skip | 公开 SDK 编辑、截断恢复、预算、旧检查点、取消、Skills、Workspace 等现有完整 Node suite |
| Linux 候选镜像 Node | 216/216，0 skip | 与实际安装包一起导入；离线只读隔离容器 |
| Java 定向回归 | 29/29，0 skip，BUILD SUCCESS | Dispatch 5、Recovery 16、Feedback 8；技术错误分类、修复额度、未决操作和恢复 |
| 真实 Docker Python + SDK | 10/10，0 skip | 下表的真实 Python 异常、输出文件及数值验证；模型和 Java gateway 为受控 fixture |
| 真实平台/模型/MCP | 基线与部署后各 1/1 完成 | 同一 CRLF 两模块源码，真实模型生成修复、Java 准入结算、MCP Sandbox 及结果证据 |
| 部署门禁 | PASS | healthy、签名回调成功、未签名拒绝、原挂载/权限保留、其他服务容器 ID 未变 |

日志：[Linux Node](evidence/cline-sdk-reuse-20261105/worker-tests.log)、
[Windows Node](evidence/cline-sdk-reuse-20261105/windows-node-tests.log)、
[Java](evidence/cline-sdk-reuse-20261105/java-tests.log)、
[Docker Python](evidence/cline-sdk-reuse-20261105/sandbox-tests.log)。

一次本机默认并发全套运行发生已有 `recovery.test.js` 子进程 10 秒超时，当时并行执行 Java reactor 编译。
没有放宽测试断言或计为成功；控制 Node 并发为 4 后完整重跑通过，Linux 同样通过。
第一次 Linux 打包遗漏测试引用的 scripts/web，门禁以模块缺失失败；补齐 bundle 后全套重跑通过，
失败日志保留于主机 `initial-packaging-*`，该次失败没有切换流量。

## 自动修复、大文件、压缩和恢复

`integration/sdk-sandbox.mjs` 的前八项分别执行 SyntaxError、IndentationError、NameError、KeyError、
TypeError、ModuleNotFoundError（ImportError 子类）、OUTPUT_MISSING、OUTPUT_SCHEMA。
每项先在真实 Python 容器失败，再由真实 SDK loop 消费 `isError`，应用预设模型补丁后再次执行，
断言输出单行整数 6、版本/hash、模型 admission/settlement。
第九项执行 `SyntaxError → TypeError → KeyError → 成功`，保存每次修复并 restore 后不重放模型/Python。
第十项执行 10,000 行程序的局部 SDK 修复。模型 fixture 不是外部供应商；输出和异常未伪造。

新增 `sdk-reuse-regression.test.js` 验证 1,000/10,000 行 CRLF 程序连续两次局部编辑，
逐字节比较无关代码；跨两模块修改、受控 adapter 不变、越界拒绝及取消；普通遥测失败隔离；
旧私有状态单向转换；原生截断恢复的每次 admission/settle 和工具仅执行一次。
既有 sdk-migration 测试继续验证歧义、陈旧父 hash 和部分无效 patch 原子拒绝，不能放宽为猜测匹配。

既有 `request-context`、`completion-context`、`sdk-migration`、`sdk-native-capabilities` 测试验证 SDK basic/stateful
compaction、完整工具对、原始诊断、目标/授权绑定、最新代码、canonical transcript 和 prefix-hash 恢复。
真实配对任务都实际发生两次 SDK_BASIC compaction，结果均完成；不将这一小任务称为接近供应商极限的压力测试。
`recovery.test.js` 使用真实 Node 子进程异常退出 73，再使用 SDK checkpoint 恢复，断言没有重放 Python、模型 ordinal 延续。
Java 16 项恢复回归覆盖 session 缺失、停止与未知结果；没有在生产执行中强杀 Worker 或制造真实供应商超时。

## 真实联机结果

模型 `qwen3.7-plus`，无业务数据，原始 `main.py` 调用 `calc.total()`；`calc.py` 中 `if True` 缺冒号。
基线与候选均使用同一不可变 ASSET 的 hash、相同指令和平台预算。执行显式授权，不 apply 到 Studio。
请求与结果证据见 [baseline](evidence/cline-sdk-reuse-20261105/baseline-verification.json) 和
[candidate](evidence/cline-sdk-reuse-20261105/candidate-verification.json)。

部署后首次反馈 `SYNTAX_ERROR / REPAIR_IN_CURRENT_CONTEXT / attemptsUsed=0`，
最终 `RESULT_VERIFIED / COMPLETE / attemptsUsed=1`，任务 COMPLETED，存在 result/validation/bundle 三个证据引用。
归档的 `calc.py` 与原文件相比只有冒号修复，CRLF 和计算表达式保持；报告标明演示结果 6，无业务结论。
模型为满足 Runtime 输出合同，在 main.py 加入报告写入逻辑，因此不宣称整个项目只改一个字符。
候选中有一次 `CODE_PROPOSAL_INVALID` 拒绝，随后 SDK 继续并成功；未因此发生额外 Python 执行。
脱敏 monitor 没有具体拒绝原因，不能把所有模型提案失配问题说成已经消失。

## 部署与回滚

环境入口 `http://192.168.195.221:18100`，MCP 入口 18101，SSH 端口 5221。
只替换 Coding Worker，API、MCP、数据库、前端和模型配置未发布。
候选镜像及五个模块 before/after hash 在
[镜像清单](evidence/cline-sdk-reuse-20261105/image-manifest.json)，
切换结果在 [部署记录](evidence/cline-sdk-reuse-20261105/deployment.json)。
候选镜像 ID `sha256:35ab26f1ec27649838acbc02f038cdf3744692a1af48a891506538cef97e3696`。
原 worker 镜像 `sha256:92fe80ab5c2df5442e91231035b6e64f37882c5d60d084ed2f1f5f5e0d485f89`。

`scripts/deploy-cline-sdk-reuse.py` 切换前要求零 active session、零 parent task、无运行中的 Python Sandbox，
检查候选门禁和源容器身份；复用原 Config/HostConfig/mount/network，验证模块 hash 和健康后切换。
保留原容器 `runtime-os-app-stage-coding-agent-1-before-sdk-reuse-cfdc8d74bec5`，
原 compose、deployment.env 和 inspect 存于私有发布目录 `backup/`；失败会恢复容器、网络和配置。
最终 Worker healthy，站点 HTTP 200，回调签名检查通过。无 Docker socket 挂载，原非 root、cap-drop 和网络限制保留。

后续手动回滚应先确认任务静止，恢复 backup 中 compose/env，停止候选、恢复原容器名称及原 network endpoint，
运行现有 `restrict-coding-worker.sh`，再 reload node-proxy nginx 并重跑 `verify-coding-callback.py`。
不能在存在未决执行时直接重放任务。本轮没有为了演示回滚而再次中断已验证的环境。

## 未完成的扩展验收与风险

当前已验证范围支持本次五个模块的有限整改部署；不等于任务书所有场景都完成了新的外部模型验收。
SQL/Python Studio 浏览器交互、SQL 实际连接、全部图文报告、每一错误种类的真实模型修复、生产故障注入、
接近供应商最大上下文的长任务及多样本性能分位数，本轮未完整重测。
这些能力主要依赖已有 Node/Java 回归；不得以本次测试数量替代全部业务验收。
此外仍有大型 canonical checkpoint 容量、语义级回归、旧协议兼容、遥测超时等待和未知用量保留风险。
完整 Core/0.2.0 升级需独立 PoC；本轮不新增第二入口或摘要 Agent。
