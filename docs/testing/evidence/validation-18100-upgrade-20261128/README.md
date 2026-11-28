# 18100 验证环境升级记录

本轮源码为 `c1c019d20631cb292c4b7ff9d86fd0f5ca7fd60d`，位于 GitHub 专项分支 `fix/model-governance-stability-313f35664`，包含渐进式交付及逐接口 Agent API 文档。对该分支执行了 Java、前端、业务硬编码及凭证特征检查。

## 构建和切换状态

验证服务器以完整 Git 源码归档构建了 API、MCP、Native 和模型 Gateway 候选镜像，编译、缓存和镜像写入均使用独立盘。API 和 MCP 包内的 Web 入口已核验，API 内置的文档与提交一致。Native 固定为 Cline 4.1.27 和 Python 3.11，复用 Python 基础镜像前比对了依赖锁文件。候选 Image ID 和源码标签见 [候选镜像清单](candidate-images.json)。

18100 继续运行原有镜像，候选尚未切换，实际部署 Commit 为空。原环境首页、管理员登录、登录态查询和匿名拦截已复核。九项真实业务尚未提交，保留为待切换的验收项。

候选 Gateway 的协议测试及超时边界回归通过，覆盖 59/60/61 秒、心跳、默认 5 分钟、不限本层时限和取消。本轮这些测试使用协议夹具，现网 LiteLLM 兼容性仍需合并后的端到端验证。 [测试、健康与清理证据](verification.json)

## 现有环境和回滚基线

18100 由主机 Docker 的 Nginx 服务发布。Java API、MCP、Native Broker、Executor、Recovery、Temporal、PDF Worker 和模型 Gateway 的运行镜像见 [运行基线](inventory.json)。配置、容器检查结果、API/MCP 数据库 Schema 与数据备份，以及已退出测试容器日志保存在服务器独立盘 `/mnt/disk01/validation-18100-c1c019d20/rollback`。两份数据备份已经通过 `pg_restore --list` 和 SHA-256 验证，整个目录限制为服务器管理员访问。

API 和 MCP 连接原有验证数据库 `runtime_os_acceptance_api` 与 `runtime_os_acceptance_mcp`。数据库中的 Runtime 视图有 70 条历史记录显示 RUNNING，最近一条更新距本轮核对超过一天。当前主机 Native Executor 的 Docker 守护进程无运行中的 Worker。切换前仍须复核 Temporal、队列与恢复状态，保持历史记录原样。

## 模型与切换条件

18100 的 Java 模型目录有 15 个已启用的聊天模型，YARN Agent 的 `hy3` 已发布。该 Java 模型配置指向腾讯模型服务，当前 Native 使用的模型 Gateway 配置中没有 `hy3` 路由。两个层级的模型发布记录不能互相替代。

现有模型 Gateway 的 `f2f9b6beb` 版本包含独立的 LiteLLM、工具协议和故障监控改动。稳定性分支的逐模型超时改动与这条历史分支在 Gateway 和 Native 回执代码存在合并冲突。直接替换会改变仍在使用的模型协议，因此切换前需把兼容修复与超时治理合并验证，并核实实际模型路由。

## 回滚操作边界

正式切换前需复核数据备份的新鲜度，记录实际 Compose 文件顺序、组件健康检查及业务入口验证。应用回滚按备份的 Compose 层顺序恢复原镜像，通过原有 18100 入口重新核对登录、权限、MCP、模型转发和产物访问。只有确认数据库迁移需要逆转时，才按单独的数据库恢复流程处理数据。

## 清理记录

归档配置与日志后，下架了 23 个确认已结束的隔离专项容器。定向删除了两个无引用旧镜像及一个旧镜像标签，清理了三个已比对的重复构建目录。共享 `/opt` 保持 92%，实际运行镜像、回滚镜像和使用中的数据卷保留。尚有专项服务留待进一步核对依赖与任务状态。

## 业务测试要求

正式验收只认 18100 正常 Java 入口创建的任务。以业务完整成功、基础报告交付、澄清、失败和取消分别计数。先验证 InnoDB、Docker、CDH YARN，再开展同一 Session 的证券分析，以及 LiveBOS 和市场研究。隔离镜像的构建和单组件测试不计入真实业务验收。
