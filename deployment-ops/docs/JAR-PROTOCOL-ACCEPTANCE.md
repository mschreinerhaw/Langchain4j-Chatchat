# 单 JAR 入口与安装协议验收

新增候选 `1.1.0-jar-rc.4`；不替换原 V1.0 正式标签，不声明新的完整正式发行完成。

## 已实际执行

- 服务器仅执行 `java -jar deployment-ops-1.1.0-jar-rc.4.jar`，启动入口不依赖 install 脚本或 systemd 安装；测试初期的 transient unit 仅用作测试进程管理，最终进程直接由 Java 启动。
- 启动前保持真实挂载检查；JAR 内含 Vue、H2/JDBC、执行器源码和公开信任材料。
- 页面凭启动终端令牌创建管理员，密码由页面输入，无默认管理员密码；初始化完成后重放拒绝。
- 页面导入真实签名协议包并校验离线镜像，展示固定镜像ID、启动参数、组件依赖和健康门禁。
- 页面填写业务用户名及密码，初始化任务完成后才启动服务，真实认证返回成功。
- 密码未出现在公开部署任务中；自定义可信签名、H2、用户及任务记录重启后保留。
- JVM 停止期间及重启之后，原有业务容器 ID/StartedAt 不变。
- Java 13 项、Python 51 项、Vue 格式/构建通过。证据在 `evidence/standalone-protocol-20261117/`。

服务器候选 JAR：`/mnt/disk01/deployment-ops-v1-validation/deployment-ops-1.1.0-jar-rc.4.jar`。
SHA-256：`2bac965d643bb5e7a0ff33380b5620e53f4374fb32a82e1cab47e0096c00b341`。
独立测试页面使用19260，执行器19262；原19240正式服务未替换。
实例数据只在 `/mnt/disk01/deployment-ops-v1-validation/jar-entry-state/`。
QA 业务是用于验证协议的独立认证服务，不冒充完整 Native Runtime。

## 明确边界与待确认项

当前执行器沿用已经验证的 Python 实现，故运行环境仍需要 Python3.11，尚不是仅依赖 JDK 的发行物；是否要求迁入 Java 正向用户确认。
单 JAR 可在线备份下载；自动数据库恢复使用原生服务模式。单 JAR 自升级为停止 JVM、保留旧 JAR/数据并替换再启动，未伪装成 systemd 自动切换。
Native Runtime 仍保留专用受信产品适配器；未声明在组件清单的依赖/命令由该适配器管理，页面明确标注，不将缺失项显示为“没有依赖”。
本次完整协议部署验收使用通用组件引擎，未重复声称已在本候选进程中完成24组件 Native Runtime 冷安装。

协议字段、包结构及凭据约定见 `INSTALLATION-PROTOCOL.md`；通用 Java 应用配方见 `recipes/protocol-example/manifest.json`。
