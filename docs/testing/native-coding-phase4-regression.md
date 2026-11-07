# Phase 4 P0 回归记录

任务一先行根因提交：`0be922534`。本次只修复原生 v2 的验证事实及原预算投影，并将 lost-ACK 后工作区重载前移到 SDK prepareTurn 之前。没有修改 SDK、Java、模型、预算、Sandbox 或固定任务数据；没有自动结束任务或自动回滚源码。

## 组件复现与修复

| 检查 | 修复前 | 修复后 |
| --- | --- | --- |
| 声明验证后继续检查及原生压缩、编辑后旧验证失效 | 2 项失败，见 `phase4-verification-before-r2.log` | 完整套件通过 |
| 编辑持久提交但 ACK 丢失，下一轮先重载再投影 | 1 项失败，见 `phase4-reload-before.log` | 完整套件通过 |
| 恢复 SDK 会话后读回原验证、失败/无输出/无停止证明、诊断截断及原余额 | 新增专用回归 | 完整套件通过 |

使用实际安装 SDK 0.0.93 的 Agent/Compaction/Restore 与可控模型和 Host 验证执行顺序。断言模型确实观察到状态，避免 SDK 将测试断言异常转为 failed 状态后造成假通过。这些是组件测试，不是真实模型验收。

完整 Node 套件：`NODE_OPTIONS=--experimental-strip-types node --test --test-concurrency=2 test/*.test.js`，319 项：318 通过、0 失败、1 项 Windows 链接权限跳过，123189.7818ms。构建 `npm run build` 通过。首次默认并发运行产生 Node 内存分配失败及子进程启动异常，保留失败日志；降低的是本机测试并发，未改变任务/Sandbox 预算。日志位于工作树忽略目录 `target/phase4-*.log`，不包含私有提示词或凭据。

## 真实任务门槛

真实模型回归待执行；此记录不表示 A、C、E NameError 已通过。将另行附上每次失败及成功任务 ID、原始停止、源码 Hash、声明验证、独立复核和归档重读证据。三项全通过前不进入 MCP、Skills 或更多语言开发。

## 回滚

本提交保留原生 v1 及 `python_execute` 兼容路径。部署只替换隔离验收 Worker，保留旧容器及镜像；停止新准入并核实活动会话与 Sandbox 全部停止后，恢复旧 Worker。原 Java/Silo 版本、回执和失败任务不删除、不重新结算。本改动仅披露已有事实，不增加授权；最终完成仍由原 Agent 与 Runtime 原准入分别判定。
