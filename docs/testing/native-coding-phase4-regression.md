# Phase 4 P0 回归记录

任务一先行根因提交：`0be922534`。本次只修复原生 v2 的验证事实及原预算投影，并将 lost-ACK 后工作区重载前移到 SDK prepareTurn 之前。没有修改 SDK、Java、模型、预算、Sandbox 或固定任务数据；没有自动结束任务或自动回滚源码。

## 组件复现与修复

| 检查 | 修复前 | 修复后 |
| --- | --- | --- |
| 声明验证后继续检查及原生压缩、编辑后旧验证失效 | 2 项失败，见 `phase4-verification-before-r2.log` | 完整套件通过 |
| 编辑持久提交但 ACK 丢失，下一轮先重载再投影 | 1 项失败，见 `phase4-reload-before.log` | 完整套件通过 |
| 恢复 SDK 会话后读回原验证、失败/无输出/无停止证明、诊断截断及原余额 | 新增专用回归 | 完整套件通过 |

使用实际安装 SDK 0.0.93 的 Agent/Compaction/Restore 与可控模型和 Host 验证执行顺序。断言模型确实观察到状态，避免 SDK 将测试断言异常转为 failed 状态后造成假通过。这些是组件测试，不是真实模型验收。

完整 Node 套件：`NODE_OPTIONS=--experimental-strip-types node --test --test-concurrency=2 test/*.test.js`，319 项：318 通过、0 失败、1 项 Windows 链接权限跳过，123189.7818ms。构建 `npm run build` 通过。首次默认并发运行产生 Node 内存分配失败及子进程启动异常，保留失败日志；降低的是本机测试并发，未改变任务/Sandbox 预算。完整本机日志保留于忽略目录 `target/phase4-*.log`；仓库同时保存 [两项失败原始日志](evidence/native-coding-phase4/component-logs/phase4-verification-before-r2.log)、[重载失败原始日志](evidence/native-coding-phase4/component-logs/phase4-reload-before.log) 及 [完整套件 TAP 名称/结果投影](evidence/native-coding-phase4/component-logs/phase4-node-full-r2.tap-summary.txt)，均不含私有提示词或凭据。完整日志原始字节 Hash 仍保留于组件 JSON，结果投影不冒充完整原日志。

## 真实任务门槛

P0 生产代码提交 `e107dffff`。模型固定 `qwen3.8-flash`，最大输出 8192；每任务原上限 8 次模型、262144 Token、16 次工具、4 次 CODE、300 秒；原 Sandbox 上限 75 秒，镜像 `sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828`，NONE 网络、非 root、源码只读。原固定 fixture Hash `b73033fb163696fb4f778e495a19584787d1c6c348cff724ea5d246ff2e6a92f`，harness Hash `ce7a7a28e175de21d4138c351bdad6fbd9184421d88d8a2440a87d959356dcfd` 均未改变。

| 批次 | 实际完整交付 | 独立源码复核 | 范围 |
| --- | --- | --- | --- |
| 修复前第四批 | 5/8 | 7/8 | 原八项；其中 A、C、E NameError 均失败 |
| P0 r1 | 3/3 | 3/3 | 只运行 A、C、E NameError，不能充作八项完整回归 |
| P0 r2 | 8/8 | 8/8 | 原八项完整固定任务 |
| P0 r3 | 8/8 | 8/8 | 第二次原八项完整重复 |

首轮三项详细结果与历史回放见 [r1 证据](evidence/native-coding-phase4/after-r1/evidence-hashes.json)；完整八项的原任务、源码及每文件 Hash、停止回执、授权/披露、归档及原用量重读见 [r2 证据](evidence/native-coding-phase4/after-r2/summary.json)、[r2 测量](evidence/native-coding-phase4/after-r2/metrics.json)、[r3 证据](evidence/native-coding-phase4/after-r3/summary.json) 和 [r3 测量](evidence/native-coding-phase4/after-r3/metrics.json)。组件日志原始 Hash 与失败重现见 [组件证据](evidence/native-coding-phase4/component-tests.json)。A、C、E NameError 各三次真实完整通过；两轮完整八项各 8/8，仍不宣称统计意义的性能改善。

所有成功均要求：原 Agent 自主产生最终回答、Runtime COMPLETED、独立固定测试及 holdout 成功、只读测试未修改、180 个无关函数保持（C）、全部执行独立停止、文件归档重新读取相同、独立复核不改变原任务用量。Runtime 自身 `validationStatus=INCONCLUSIVE` / `independentProjectAcceptance=NOT_EVALUATED` 保持原值；外部独立复核不回填、不冒充 Runtime 的验证状态。

r1 Worker 镜像只替换原 Node dist，API/MCP/Sandbox 未变。其 health build 字符串继承旧基础镜像，不能作为源码版本证明；[dist 原始字节核对](evidence/native-coding-phase4/after-r1/phase4-dist-provenance-public.json) 与 OCI revision、部署档案共同证明实际运行 `e107dffff`。r2 新增隔离观察入口，透传原模型请求，仅记录白名单验证/预算身份、工具名、Schema 和请求 Hash，不写提示词正文、源代码、stdout/stderr 或凭据；没有改变工具、模型、准入、预算或原生 Loop。观察入口与产品入口分离，不能作为新的产品执行器。

实际模型请求中已观察到 FAILED、STALE、PASSED 和原额度变化，逐任务 `*-model-facts.json` 为原请求白名单投影；这补充了新任务证据，不能用于伪称已经恢复旧失败任务缺失的逐轮私有请求正文。

执行命令（远端私有 token 文件不提交；下列为隔离根目录内的相对路径）：

```sh
python3 runtime-generic-acceptance.py --fixtures generic-cases-fixed.json --token-file api-auth-private.json --output runs --cohort C-phase4-p0-20261107 --source-commit e107dffff --repeat 1 --cases A C E-name
python3 runtime-generic-acceptance.py --fixtures generic-cases-fixed.json --token-file api-auth-private.json --output runs --cohort C-phase4-p0-20261107 --source-commit e107dffff --repeat 2
python3 runtime-generic-acceptance.py --fixtures generic-cases-fixed.json --token-file api-auth-private.json --output runs --cohort C-phase4-p0-20261107 --source-commit e107dffff --repeat 3
python3 project-evidence-projection.py --runs runs --token-file api-auth-private.json --cohort C-phase4-p0-20261107 --repeat 1 2
python3 project-history-probe.py --runs runs --fixtures generic-cases-fixed.json --token-file api-auth-private.json --cohort C-phase4-p0-20261107 --repeat 1 2
```

上述投影与历史脚本必须在新输出目录只执行一次；已存在的原证据不覆盖。MCP、Skills、语言环境及业务扩展本次未修改。

## 回滚

本提交保留原生 v1 及 `python_execute` 兼容路径。部署只替换隔离验收 Worker，保留旧容器及镜像；停止新准入并核实活动会话与 Sandbox 全部停止后，恢复旧 Worker。原 Java/Silo 版本、回执和失败任务不删除、不重新结算。本改动仅披露已有事实，不增加授权；最终完成仍由原 Agent 与 Runtime 原准入分别判定。
