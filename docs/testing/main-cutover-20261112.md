# 主环境切换与清理记录（2026-11-12）

部署源码提交：`0d3163eb8697d6ef4ce525322664ee56abc60408`。四个服务的源码镜像标签为 `source-0d3163eb-20261112`。

18100 主 API 已更新为 `sha256:ba5db1757c16bd71e3e52e2d5131fd0f48ac80ecfba1cc9f329b95c623e9a3a6`，原有 18360 Native Node/执行器更新为 `sha256:63a961c2be45e0a611f1ffb523f70e3d54f0c2ee507d843504e0497a0f8ca39a`。18353 监控直接代理主 Java。保留主库、账号、模型配置和实时授权；测试数据库未并入主库。

通过 18100 提交了真实 SYNTHETIC Parquet 任务，由 Cline 自主生成并执行 Python，交付源码、Markdown 和结构化结果。独立验证 3 行、总计 47.5、均值 47.5/3，并核对 Silo SHA-256。主入口及监控返回 200，未登录任务查询返回 401，见 [运行验证](evidence/main-cutover-20261112/verification.json) 和 [最终检查](evidence/main-cutover-20261112/final-report.json)。

遵照用户要求，报告语义问题交由 Skills 和模型处理，没有增加 Java 业务校验硬编码，也没有更改共享模型的联网授权配置。

清理 92 个候选测试或已停止的 Coding 执行容器、38 个旧镜像标签。删除 ID、名称和镜像见 [清理明细](evidence/main-cutover-20261112/cleanup-report.json)。实际净回收 139784192 字节，约 133 MiB；不将镜像逻辑大小当作磁盘回收量。运行镜像、上一版回滚镜像、基础构建镜像、数据卷、工作区及执行证据保留。未全局 prune，未删除构建缓存。旧的七个 Coding 服务已设为 `legacy-coding-retired` profile，排除出默认 Compose 启动。

部署路径为 `/opt/runtime-os-docker-stage/compose.yaml` 和 `/opt/runtime-os/cn2/compose.json`。备份和日志保存在 `/opt/runtime-os/releases/20261112-0d3163eb/main-cutover/`。回滚入口为服务器上的 `python3 /opt/runtime-os/releases/20261112-0d3163eb/rollback-main.py`，恢复此次切换前的配置快照及 API/Node 镜像；执行前应确认没有后续配置变更需要保留。

本记录仅证明本次主环境切换及计算交付验证，不宣称此前未完成的全场景五轮稳定性、CLI 对照或正式 1.0.0 发布门槛已全部完成。
