# Temporal 正式业务入口切换记录

2026-11-13（Asia/Shanghai），根据用户“帮我部署切换”授权，将服务器
`192.168.195.221` 的原 18100 业务入口接入已验收的 Temporal 集成镜像。
镜像源码仍固定于 `d1d706f72bdb78a95cc254df8aa29344f9f65b50`，未重新构建。
镜像 ID、版本及上轮扩容验证见 [验收报告](native-temporal-20261113.md)。

## 运行配置

- 业务入口：`http://192.168.195.221:18100/`。
- 监控入口：`http://192.168.195.221:18100/monitor/`；19100 保留维护访问。
- 新 API 接管原 `api` 网络别名及本机 18180 端口；原 API 停止、断开网络并禁用自动重启，保留用于回滚。
- 新 API 使用原 `/opt/chatchat-1.0.0-SNAPSHOT/data`，保留原文档索引、日志及共享配置；未使用候选空数据目录替代正式数据。
- 原业务后台任务队列保持启用，Coding 调度采用 Temporal；两个 Java Adapter、Native 节点及独立停止控制继续工作。
- 原应用 Compose 的旧 API 限定为 `rollback-local` profile；其他服务对该旧 API 的启动依赖已移除，避免常规启动时恢复第二个本地调度器。
- 当前运行 Compose：`/opt/runtime-os/native-temporal/rc-20261112/compose.private.json`。重启整套服务时先启动该栈，再启动应用网关。

## 实际验证

通过 18100 登录、上传明确标注的合成 Parquet 并提交任务；由真实 Cline 模型、Shell 和 Python 执行。
两次任务分别在 `cline-worker-1`、`cline-worker-2` 完成，均为 Temporal COMPLETED；独立确认
4 行、合计 40、均值 10，并从 Java 下载动态 Python 源码、报告及结果，复核大小与 SHA-256。

现有 Agent 授权的 `document_search` 真实检索、事实核对及 Silo 交付通过，未授权文档未返回。
正式入口浏览器验证通过：Workflow/节点/时间线可见，时间线与交付默认折叠，刷新和断线恢复后历史可查。
无 Token 和无效 Token 的监控访问均被拒绝。Silo 健康检查及 API/MCP/News/数据库运行状态通过。

证据：[业务验算](evidence/native-temporal-cutover-20261113/business-validation.json)、
[最终分析](evidence/native-temporal-cutover-20261113/final-analysis.json)、
[文档检索](evidence/native-temporal-cutover-20261113/document-search.json)、
[浏览器](evidence/native-temporal-cutover-20261113/browser.json)、
[最终健康检查](evidence/native-temporal-cutover-20261113/final-health.json)。

## 保留的故障与资源处理

切换前发现 `/opt` 字节与 inode 耗尽。将本专项离线镜像包复制至
`/root/runtime-os-release-archives/`，核对原 SHA-256 后保留原路径软链接；
删除两个保留有完好源码 ZIP、未被运行容器挂载的旧解压构建目录。

首轮分析期间 Silo 仍因最低剩余磁盘阈值拒绝写入（HTTP 507），随后发生短暂网络连接超时；
该轮客户端验证失败记录保留，未当成成功轮次。连接恢复后确认 Silo 存储阈值错误，按精确缓存 ID
清理 21 条本项目、非共享、可回收的旧 Maven 构建缓存，保留最新两条。
未执行全局 prune，未删除镜像、数据卷或业务对象。最终 `/opt` 可用空间约 12.04 GiB。

首轮任务随后自行恢复并完成，独立产物验算通过；对其发起的取消检查遇到任务已完成，
不将该次检查计为取消成功。另行新建的完整分析任务通过。所有失败日志和浏览器原始截图保存在服务器受限证据包。

证据：[初轮失败](evidence/native-temporal-cutover-20261113/initial-probe-network-failure.json)、
[限定缓存清理](evidence/native-temporal-cutover-20261113/cache-recovery.json)、
[镜像包迁移](evidence/native-temporal-cutover-20261113/archive-relocation.json)、
[原始证据包](evidence/native-temporal-cutover-20261113/raw-evidence.json)。

## 回滚

备份与脚本：`/opt/runtime-os/native-temporal/rc-20261112/evidence/cutover-20261113-093044/`。
保留原 API 容器、镜像及私有 Compose/网络信息。回滚前停止新提交，并确认所有排队和运行中的
Temporal 任务已结束、Native 分配已停止，随后执行该目录：

```sh
python3 rollback.py --confirm-drained
```

脚本再次检查 Native 活跃记录，停止新栈并恢复旧 Compose、API 网络和重启策略。
恢复后核验 18100 登录及业务功能再开放提交。禁止同时启动两个挂载原 RocksDB 数据目录的 API。
原有跨主机高可用与模型联网策略边界保持不变。
