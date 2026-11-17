# 单 JAR 部署与维护

本入口仅依赖 Linux 基础系统和 JDK 17+，不需要预装 Python、Node 或业务数据库。
平台本地运行 Spring Boot 与嵌入式 H2。Docker Engine 用于部署业务应用；Docker 不可用时，
平台仍可启动、初始化、登录和显示主机监控。发行候选的验收状态见 `JDK-ONLY-REMAINING.md`，
不能将候选包当成已经通过全部验收的正式发行版。

## 从离线源码编译

将完整发行包解压到真实挂载的 `/mnt/disk01` 下。发行包携带构建所需的 Boot 依赖归档和
已构建的模块化 Vue 静态资源；Java 编译不连接在线仓库，也不运行 Python。

```sh
cd /mnt/disk01/deployment-ops-source
/opt/jdk-17/bin/java scripts/BuildNativeOffline.java \
  --runtime build/boot-runtime.zip --output lib/deployment-ops.jar \
  --version VERSION --source-commit SOURCE_COMMIT
```

`SOURCE_COMMIT` 为发行包 `release.json` 中的40位源码提交号；`VERSION` 为对应发行版本。
直接使用 `lib/deployment-ops.jar` 也可运行。开发时修改 Vue 源码后，需要重新构建前端；
终端服务器使用发行包中经过验证的静态资源，不需要前端运行时。

## 直接启动并完成首次安装

```sh
export OPS_DATA_DIR=/mnt/disk01/deployment-ops/data
export OPS_BIND=0.0.0.0
/opt/jdk-17/bin/java -jar lib/deployment-ops.jar
```

访问 `http://服务器:19240`。在首次安装页面填写管理员密码和本地启动日志中的安装令牌。
密码由用户设置，不内置通用默认密码。初始化完成后禁止通过安装接口重置管理员。
私有实例配置保存在数据目录上级的 `standalone/instance.properties`，不得公开或随发行包分发。

`OPS_PORT` 默认为19240，内部节点 `OPS_EXECUTOR_PORT` 默认为19242，仅监听本机回环地址。
数据盘必须为真实挂载；程序拒绝系统盘回退。临时上传、Tomcat、H2、包缓存均位于数据盘。

可选注册为系统服务（需 root 和 systemd，不覆盖已有同名服务）：

```sh
export OPS_DATA_DIR=/mnt/disk01/deployment-ops/data
/opt/jdk-17/bin/java -jar lib/deployment-ops.jar --install-service deployment-ops
```

安装器生成实际 JDK 路径和带 SHA-256 的不可变 JAR 路径，并启动服务。可使用
`sh deploy/jdk/start.sh`、`stop.sh`、`restart.sh`、`status.sh`；多实例设置 `OPS_SERVICE`。
`deploy/native` 保留旧外部执行器兼容源码；新单 JAR 安装使用 `deploy/jdk`。

## 安装协议与离线业务部署

平台启动后，从镜像页面导入 `.ops.zip` 并填写发行方提供的 SHA-256。协议规范见
`INSTALLATION-PROTOCOL.md`。签名清单必须声明固定镜像 ID、版本、依赖顺序、argv 启动命令、
健康门禁、端口及数据卷。密码参数必须声明 `secret`，不允许在协议里提供默认密码。
用户在部署向导填写凭据。预检通过后，输入环境名称确认执行，查看真实部署步骤和日志。

大包已在服务器数据盘时，可选使用同一运行实例的本地导入入口：

```sh
java -jar lib/deployment-ops.jar --import-package /mnt/disk01/media/runtime.ops.zip PUBLISHER_SHA256
```

同盘已有 Docker-save 归档时可追加一个或多个 `--image-cache /mnt/disk01/media/images.tar`。
缓存必须与包内清单精确匹配，仍会进行完整签名及内容校验。平台不自动删除镜像腾空间。
完整验证成功后自动回收本次上传的暂存 ZIP；签名、清单、配置资源及内容寻址镜像归档继续持久化。
重新提交已验证上传时，平台重新验证持久化签名及负载，直接补齐缺失镜像，不要求暂存 ZIP 仍存在。
失败或中断上传不参与此回收。用户原始介质、业务容器和数据卷均不受影响。
中断后保留上传编号，执行 `java -jar ... --resume-import UPLOAD_ID`，无需重传完整介质。
已有校验包中的镜像被清理后，可重新提交完整上传，平台验证归档后补齐缺失镜像。

## 备份与恢复

管理员在系统管理中创建和下载备份。单 JAR v2 备份包含管理 H2、执行 H2 和实例配置，
部署任务和配置中的秘密也在备份内，必须按敏感文件保管。业务数据卷、离线包及自定义
发布者信任公钥须另行备份。恢复目录必须与备份原数据目录一致。

直接运行模式先停止本平台进程，再运行：

```sh
export OPS_DATA_DIR=/mnt/disk01/deployment-ops/data
java -jar lib/deployment-ops.jar --restore-backup /mnt/disk01/backups/backup-UUID.zip BACKUP_SHA256
java -jar lib/deployment-ops.jar
```

恢复先检查完整归档、嵌套 H2、校验和及管理员记录，再替换数据，保留原文件到
`maintenance/restore-*`。运行中恢复被拒绝，旧会话失效。整个过程不停止业务容器。

## 平台升级与维护恢复

Java 安装器注册的服务模式可在系统管理页面上传离线发行 ZIP、核对校验和并输入精确
确认文本。独立 Java 维护进程只停止指定平台服务，保存旧 JAR、两个 H2 和实例配置，
切换后检查健康；失败则恢复旧快照。维护期间拒绝新的业务变更，业务容器继续运行。

直接启动模式保留旧 JAR 及备份，停止平台进程后改用新 JAR 启动，按健康检查决定是否回退。
不要用业务环境的 `docker compose down` 操作管理平台。

服务维护被操作系统重启等中断时，先查看 `maintenance/jobs/JOB_ID.json` 和快照。
需要恢复上一版本时设置原实例环境变量后执行：

```sh
export OPS_DATA_DIR=/mnt/disk01/deployment-ops/data
export OPS_PLATFORM_SERVICE=deployment-ops
java -jar OLD_VERIFIED_JAR --recover-maintenance JOB_ID
```

只允许恢复当前实例匹配且校验通过的维护快照；不会接管其他服务、删除业务卷或目录。
