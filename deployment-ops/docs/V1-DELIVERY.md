# Deployment Ops V1.0 最终交付

必需的 12 个验收场景、正式包安装、自升级隔离和完整离线介质校验均已实际执行通过。
执行环境为用户指定的服务器与该服务器上的隔离新状态实例，没有重装或清空生产操作系统。
真实 DolphinScheduler 集群未提供接入地址，其生产集群验证仍未进行。

## 访问与运行

- 平台：<http://192.168.195.221:19240>，账号 `admin`，使用用户提供的初始密码。
- Runtime OS：<http://192.168.195.221:19400>；MCP：<http://192.168.195.221:19401>。
- Runtime 初始凭据：服务器 `/mnt/disk01/deployment-ops-v1-release/state/delivery-access.env`，root 所有、0600；不在 Git 或通用发行包中。
- 原生服务：`deployment-ops-v1-release` / `deployment-ops-v1-release-executor`。
- 状态目录：`/mnt/disk01/deployment-ops-v1-release/state`。不要删除旧引导发行目录，systemd 启动脚本仍引用它。

## 源码与制品

分支 `feature/deployment-ops-offline-20261115`。发行源码提交
`209781c4ee6a93123e75b0bc651f05a3c7b2c07c`，最终业务修复检查点
`3b0987cbd367be49cdede72ec9c0e8694be65b41`。其后的交付报告提交不改变发行二进制。
正式标签 `deployment-ops-v1.0.0` 仅在全部最终核对通过后创建，指向包含本报告的提交。

服务器制品目录：`/mnt/disk01/deployment-ops-releases/1.0.0/`。

| 制品 | SHA-256 |
| --- | --- |
| deployment-ops-1.0.0.jar | e04c78d52fd8423699abeff78c2cdb2c912f9cfb93f92c3ffba41b0231800303 |
| deployment-ops-1.0.0.zip | 56fc75ea09a084b90f3b05266270b73d4e7164e6ab6e1be6e487d5767e8cfc20 |
| deployment-ops-1.0.0-offline-linux-amd64.tar | da856d34e84ed89e54a96d86829bbd73b84ba0f6e916a0db5eaca1ee0ca9b991 |

完整 TAR 为 6,705,438,720 字节，包含平台 ZIP、完整 Runtime 签名镜像包、安装文档、
源构建证明和校验和。平台 ZIP 包含 225 项受校验清单文件及 release.json。
本地平台 ZIP 在 `target/releases/deployment-ops-1.0.0.zip`；服务器上保留完整大介质。

## 构建与验收

- Java 12 项、Python 51 项通过，Vue 生产构建与格式检查通过。
- 真实页面：容器完整操作 17 项、离线镜像操作 3 项、监控/告警/权限 6 项通过。
- 从真实 Vue 向导完成全新 Runtime 部署；24 个业务组件运行，三个 Unix socket 执行器实时健康且资源策略生效。
- Native Core/Python、Temporal、OS/MCP 登录由源码产品安装器真实执行验证。
- 完整 13.4 GB 原始 TAR 在验收 Runtime 的独立 Docker daemon 实际加载，13 个唯一镜像 ID 全部核对。
- 实际业务镜像升级、失败自动回滚、页面手动回滚通过，自己的测试业务记录在各次维护后保持一致，验收完成后仅清理该测试表。
- 部署中重启平台并从页面恢复，保留同一安装器；自升级前后 44 个运行中业务容器 ID/StartedAt 不变。
- H2 实际恢复：备份前配置恢复、备份后记录消失、旧会话失效。升级故障 JAR 后恢复旧配置/H2并重新健康。
- 独立运行在隔离网络且 Docker socket 不可访问的原生服务中验证。缺盘在私有挂载视图验证，实际拒启；没有停生产 Docker 或卸载真实数据盘。
- 正式 ZIP 空状态安装、服务器离线 javac 编译、管理员初始化、H2 重启持久化、status/stop/start/restart 全部通过。21 个 Java class 与发布 JAR 相同。
- 全程原有 20 个生产容器 ID/StartedAt 保持不变；最终平台/H2/挂载健康，Runtime 24/24 运行，浏览器无 JavaScript 错误。

关键追加证据在 `evidence/native-product-20261117/`：
`formal-fresh-install.json`、`formal-platform-upgrade.json`、`formal-offline-bundle.json`、
`formal-final-production-isolation.json`、`formal-console-smoke.json`。
早期失败与后续复测记录均保留。完整实施与场景映射见 `RELEASE-1.0.0.md`。

## 全新服务器安装

前提：Linux x86_64/systemd、Python3.11+、JDK17+、真实独立挂载的 `/mnt/disk01`。
平台无需 Docker 启动；部署 Runtime 时需本机 Docker Engine 和足够数据盘/内存。

1. 搬运 TAR 及同名 `.sha256`，执行 `sha256sum -c deployment-ops-1.0.0-offline-linux-amd64.tar.sha256` 后解压。
2. 进入解压目录，执行 `sha256sum -c SHA256SUMS`。将平台 ZIP 解压至 `/mnt/disk01/deployment-ops-releases/1.0.0`。
3. 进入平台目录，设置 `JAVA_HOME` 和位于数据盘的 `TMPDIR`，执行 `python3 scripts/build-native-offline.py --runtime build/boot-runtime.zip --output lib/deployment-ops.jar`。
4. 设置 `ADMIN_PASSWORD`，执行 `python3 deploy/native/install.py --java "$JAVA_HOME/bin/java" --bind 0.0.0.0 --enable-writes`，随后清除密码环境变量。
5. 登录 19240，导入同介质的签名 Runtime ZIP；选择主机、新环境、端口、管理员密码，预检并确认部署。在任务中心与应用详情查看结果。

安装完整命令、服务控制、升级、H2 备份恢复及故障处理见 `NATIVE-OPERATIONS.md`。
业务许可证、模型端点和客户网络配置由部署方提供。H2 ZIP 仅恢复平台管理数据；
业务数据库、卷、执行器目录和私有配置需要按业务灾备流程另行备份，不能以 H2 ZIP 代替。
