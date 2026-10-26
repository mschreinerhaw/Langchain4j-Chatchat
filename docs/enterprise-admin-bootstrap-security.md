# EnterpriseAdminService 初始化配置

首次创建 `admin`，或修复无密码及旧明文 `admin` 密码的管理员账号时，必须通过受保护的外部配置提供 `chatchat.enterprise.bootstrap.admin-password`，至少 12 个字符。未提供时初始化失败，不再回退为固定密码。可通过环境变量 `CHATCHAT_ENTERPRISE_BOOTSTRAP_ADMINPASSWORD` 注入；Spring Boot 环境变量规则会去除属性中的连字符。也可使用外部配置文件或 config tree，避免把凭据提交到仓库、写入日志或放入命令行参数。初始化完成后可撤除该配置；已有有效密码不会因启动配置变化而被覆盖。

创建普通用户必须显式提供密码；更新用户时不传密码则保留原密码。外部用户同步保留来源提供的密码；来源没有密码时，每个新用户生成独立的 256 位随机凭据，只保存密码哈希，不记录或返回明文。此类用户需要通过已有用户管理接口设置密码后才能使用本地密码登录，再次同步不会重置密码。

默认租户仍为 `default`。当不存在该租户时，先按平台租户编号查找；不再根据任何企业名称自动选取并改名。若旧部署的平台租户没有正确编号，需要显式配置 `chatchat.enterprise.migration.legacy-tenant-code`（环境变量 `CHATCHAT_ENTERPRISE_MIGRATION_LEGACYTENANTCODE`），仅迁移所指定的旧租户并保留其 ID。迁移完成后可撤除该配置。

本修改不会主动重置数据库中已有的弱密码；应通过已有修改密码接口完成轮换，避免在启动时静默改变现有用户的凭据。
