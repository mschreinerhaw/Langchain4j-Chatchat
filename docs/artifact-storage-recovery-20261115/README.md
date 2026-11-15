# 报告归档失败修复与历史工作区归集

截图原任务为 `4d6e3f23-bdae-4fda-882f-80d7ccdc0821`，财经 Agent 的报告在写入 `resources/answer.json` 时失败。

## 根因与修复

`/opt` 180 GB 分区已满，初查仅约 20 KB 可用。PostgreSQL 没有 Docker 日志轮转，单个日志累计约 74.5 GB。监控投影反复插入已经存在的 Coding 事件，虽然 Java 捕获重复主键异常，PostgreSQL 仍逐次输出错误和 SQL，最终挤占报告、日志及 Node 状态的写入空间。两个 Cline Node 也因 ENOSPC 退出。

- 投影按任务一次读取已有事件；相同事件不再重复 INSERT，内容不同的同序号事件继续拒绝，不能以覆盖数据消除冲突。
- 文件系统容量/配额错误显示 `ARTIFACT_STORAGE_FULL` 和恢复动作；清理失败作为 suppressed 异常保留，不掩盖原始归档失败；元数据写入完整处理部分写入。
- 原 PostgreSQL 日志完整压缩到另一分区，完整解压的长度和 SHA-256 校验通过。没有删除数据库数据或业务产物。
- PostgreSQL 容器采用同一镜像、相同挂载源、环境及发布端口重新创建，日志限制为 `100m × 3`；保留停止状态的回滚容器。API 与 Java Worker 同样设置日志上限。
- 恢复两个 Cline Node；不更换专业报告 Skills、资源、成果路径或授权合同。

## 发布及完整链路检查

服务器以 JDK 17 从 Git 源码编译改动类，针对 API 与两个 Worker 各自的基线增量生成镜像，保留原基线中的其他代码与前端。发布目录保留源码摘要、编译日志、逐项 JAR 差异、镜像摘要及私密回滚配置。

首轮发布没有重建共享 API 网络命名空间的依赖服务，回归出现 Temporal 连接失败。发布脚本已补齐：先重建 Temporal，验证新命名空间中 7233 端口，再重建全部共享网络服务，核对实际 namespace owner，并等待 Runtime 能力初始化完成。这个失败记录没有改成成功。

真实回归还发现：禁用数据库任务队列的 Worker 在启动时会恢复 API 正在处理的任务，引发重复分析及 `Immutable task input conflict`。现通过数据库队列来源标记和租约/版本过滤阻止越权恢复，保留旧本地队列兼容逻辑，未放松输入不可变性检查。

最后以原问题、同一财经 Agent、新任务身份执行回归，原历史失败记录保持原状。最终结果和归档字节核验见本目录验证材料。

最终回归任务为 `2ddf619e-d87d-3dc0-a853-415df47f108e`，通过对外 `18100` 入口提交，状态为 **SUCCEEDED**。程序核对原问题和 Agent 一致，`resources/answer.json` 实际文件、资源 API、Manifest 及对象元数据长度与 SHA-256 一致，见[发布核验](verification/publication.json)。这是报告交付完整性检查，不是对金融分析判断的认证。

应用源码部署提交为 `97c838416fbe1b6554bdc484db90f7091b2c3d1a`，见[服务器编译与部署记录](verification/deployment.json)。[最终健康检查](verification/final-health.json)确认两个 Node 健康、无遗留活动 Worker，监控投影无积压；分区约有 **64 GiB** 可用。[运行检查](verification/runtime-verification.json)记录最终发布后该投影重复主键错误为 0。[完整日志归档校验](verification/log-archive.json)保留原始长度与 SHA-256，实际压缩日志留在服务器私密备份目录，不进入 Git。

## 历史工作区

原生工作区的 17 项遗留改动主要是被历史误跟踪的构建产物。按现有 `.gitignore` 停止跟踪 20 个生成文件，保留源码、public 资源及本地构建结果，避免每次构建再次产生脏工作区，详见[历史构建产物说明](../workspace-history-reconciliation-20261115.md)。

Temporal 工作区的 24 个遗留源码/测试改动已审查、测试、独立提交并推送；随后和构建产物清理一起合入本修复分支。没有覆盖原工作区源码，没有强推、删除历史分支或自动修改 main。

SQL 前端测试在 Node 24 下关闭实验性全局 Web Storage，使用项目锁定的依赖；SQLGlot 使用锁定版本及哈希，在独立测试 venv 中运行。测试日志和验收材料不包含密码、令牌或私密部署配置。

[最终测试记录](tests.json)：后端 **199** 项、前端 **221** 项、发布 Python 检查 **2** 项，合计 **422 项通过，0 失败、0 跳过**；API 和 MCP 前端源码构建通过。历史改动分别推送到 `feature/runtime-cline-cli-native-20261107`（`bf185a8da`）与 `fix/sql-task-cancellation-20261114`（`35c1412aa`、`926b55b73`），并合入 `fix/artifact-storage-recovery-20261115`。
