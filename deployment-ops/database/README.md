# 管理数据库初始化与迁移边界

- Web 使用现有独立 H2：`backend/schema.sql` 初始化用户、会话、节点、审计和安装缓存，不访问 Runtime OS 业务数据库。已有 H2 数据通过 `CREATE TABLE IF NOT EXISTS` 保留，不重建用户及节点。
- Agent 使用独立 SQLite：首次启动自动建立 `STATE/manager/ledger.sqlite`，保存审批计划、操作账本和采样。`001-node-ledger.sql` 为 v1 的显式初始化定义。
- 如需离线预建账本，先停止该 Agent（不要停止工作负载），执行 `python3 database/initialize-node-ledger.py --state /private/agent-state --agent-stopped`。脚本不连接 Docker，不执行重放或清理。原有数据不删除，检测到更高版本时拒绝降级。
- v1 不包含破坏性 ALTER/DROP。未来 schema 变更须新增版本迁移、备份及兼容性测试，不能修改旧 SQL 并宣称已迁移。
- 管理备份恢复要求镜像版本匹配，恢复 H2、SQLite、凭据和 trust 配置；生产业务数据库、镜像包缓存和远端节点证书不属于管理元数据恢复范围。
