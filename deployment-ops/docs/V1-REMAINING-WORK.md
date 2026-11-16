# Deployment Ops V1.0 交付台账

基线 `8dbd6b915`，分支 `feature/deployment-ops-offline-20261115`。
本台账替代早期候选版的阶段清单。详细执行记录见
`evidence/native-product-20261117/` 和 `RELEASE-1.0.0.md`。

已实施并实际验证：原生 Spring Boot/H2、模块化 Vue、管理员/只读角色、
配置/入口/审计、真实主机/镜像/容器监控和操作、离线签名镜像导入、
Native/Temporal Runtime OS 24 组件部署、平台中途重启与显式任务恢复、
真实业务镜像升级、部署失败自动回滚、页面手动回滚、业务数据保留、
平台升级及失败恢复、H2 一致性备份恢复、会话失效、依赖不可达时独立启动、
隔离挂载视图中缺盘拒启。未卸载真实数据盘，未停止主机 Docker。

最终修复：首次探针连接失败与初始 UNKNOWN 同名时漏记告警历史。
新增真实端点集成测试，验证首条记录存在且相同状态不重复刷屏。
最新 Java 自动测试 12 项、Python 51 项；Vue 构建、格式检查及真实页面回归。

发行前最后核对：完整证据聚合门禁、正式 ZIP 和完整离线介质的清单/SHA、
正式包新状态安装与服务器源码编译、正式包自升级隔离验证、提交推送。
这些操作结果单独存入最终交付记录；不得用本台账代替执行结果。
正式标签只能在上述结果全部通过后创建。

当前实例：`http://192.168.195.221:19240`，服务
`deployment-ops-v1-release` / `deployment-ops-v1-release-executor`，
状态目录 `/mnt/disk01/deployment-ops-v1-release/state`。
Runtime 环境 `runtime-release-v1`，入口 19400，MCP 入口 19401，项目
`ops-env-runtime-release-v1`，数据目录 `/mnt/disk01/ops/r/99f2-076e271c2551`。
凭据单独保存在服务器受保护文件中，不进入 Git、报告或通用离线包。

没有提供真实 DolphinScheduler 集群地址，多地址 HTTP/TCP 能力已验证，
真实 DS 集群接入仍未验证。业务许可证、外部模型和企业网络配置由部署方提供。
H2 备份仅覆盖平台管理数据，业务数据库/数据卷和执行器目录按业务方案备份。
