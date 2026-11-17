# Deployment Ops 1.1.0 完整离线安装

本发行实现 V1.0 任务范围及后续“平台仅依赖 JDK”的要求。历史 1.0.0 的外置
Python 执行器不作为本次交付入口。平台源码提交为
`2f460f1a114855ca153d41b8a8a0fb4cead221cb`。

平台前提：Linux x86_64、JDK 17+、真实挂载 `/mnt/disk01`。注册服务还需要
root 与 systemd。部署 Runtime OS 需要可连接的 Docker Engine；Docker 未启动时平台仍可运行。
完整业务介质约 6.2 GiB，展开镜像、上传暂存、Docker 和业务数据还需要额外空间，
首次部署建议预留至少 60 GiB，并按业务数据增长扩容。不需要预装 Python、Node 或 Maven。

## 校验、解包和服务器源码编译

将 TAR 和同名 `.sha256` 一起复制到 `/mnt/disk01/media`，运行：

```sh
cd /mnt/disk01/media
sha256sum -c deployment-ops-1.1.0-offline-linux-x64.tar.sha256
tar -xf deployment-ops-1.1.0-offline-linux-x64.tar
cd deployment-ops-1.1.0-offline
sha256sum -c SHA256SUMS
mkdir platform
cd platform
"$JAVA_HOME/bin/jar" xf ../deployment-ops-1.1.0.zip
PATH=/nonexistent "$JAVA_HOME/bin/java" scripts/BuildNativeOffline.java \
  --runtime build/boot-runtime.zip --output lib/deployment-ops.jar \
  --version 1.1.0 --source-commit 2f460f1a114855ca153d41b8a8a0fb4cead221cb
```

发行包携带完整 Java 源码、45 个嵌入依赖和预构建的模块化 Vue 资源。
服务器源码构建无需网络、Maven 或 Node。自行修改 Vue 后需在构建机重新构建前端。
服务器重编译的 ZIP 时间戳可能导致整 JAR 校验和不同；预编译发行 JAR 的 SHA-256 为
`ba2d3cc7a51536fbd97f3e6cd661d2f0f726bb0b7b6b2792e3dfe8df3a2b1bfa`。

## 启动、初始化及安装协议

```sh
export OPS_DATA_DIR=/mnt/disk01/deployment-ops/data
export OPS_BIND=0.0.0.0
"$JAVA_HOME/bin/java" -jar lib/deployment-ops.jar
```

浏览器打开 `http://服务器:19240`，使用本机启动日志中的一次性安装令牌，由用户设置
管理员密码。没有发行包通用默认密码。需要系统服务时，先停止前台进程，再运行：

```sh
"$JAVA_HOME/bin/java" -jar lib/deployment-ops.jar --install-service deployment-ops
sh deploy/jdk/status.sh
```

同目录提供 install/start/stop/restart/status 脚本和 systemd 模板；Java 安装器实际生成
包含 JDK 绝对路径、数据盘约束和不可变 JAR 路径的服务文件。

在“镜像管理”导入 `runtime-os-1.0.3-protocol-20261117.ops.zip`，使用 `SHA256SUMS`
中的 SHA-256。也可在当前平台实例环境变量下运行本地导入：

```sh
"$JAVA_HOME/bin/java" -jar lib/deployment-ops.jar --import-package \
  /mnt/disk01/media/deployment-ops-1.1.0-offline/runtime-os-1.0.3-protocol-20261117.ops.zip \
  296d3b1757755df1a3f1acb12488aa64be1a634649a4a25132511542f37138c9
```

“部署发布”选择已验证离线包，查看签名安装协议：镜像/版本、24 个组件、启动命令、
依赖关系、端口、目录和健康检查。填写服务器地址、空闲端口和用户自己选择的密码。
预检通过后输入环境名称确认。任务页展示真实进度、日志和失败原因；失败后按页面恢复或回滚。
管理员密码不内置到协议，部署日志对秘密脱敏；不要将包含秘密的实例配置和备份公开。

## 升级、备份和恢复

平台升级：系统管理上传完整平台 ZIP，核对 SHA，输入精确确认文本。独立 Java 维护
进程只停止平台服务，保存原 JAR、两个 H2 和实例配置，健康失败时恢复旧版本。
中断恢复、前台运行方式及命令见平台包 `docs/JDK-OPERATIONS.md`。

应用升级：导入声明兼容升级路径的新签名应用包，部署到原环境；失败自动回滚。
部署历史支持显式回滚；数据兼容性必须由应用发行方声明。升级、回滚不删除业务数据卷。

系统管理创建一致性备份，包含管理 H2、执行 H2、实例配置；恢复须使用原数据目录，
输入 `RESTORE:备份文件名`。恢复后旧会话失效，需要重新登录。业务卷及离线包需另外备份。
直接运行模式必须先停平台，再使用文档中的 `--restore-backup`。禁止对业务执行 compose down
来停止平台，禁止在真实服务器上卸载数据盘验证缺盘。

## 本次服务器交接

平台：`http://192.168.195.221:19240`，服务 `deployment-ops-jdk`，数据
`/mnt/disk01/deployment-ops-jdk/data`；Runtime OS：`http://192.168.195.221:19410`，
MCP：19411，环境 `runtime-v1`，业务目录 `/mnt/disk01/ops/r/6462-ee1b43f41b32`。
账户保存在服务器 root 可读文件 `/mnt/disk01/deployment-ops-jdk/delivery-access.env`，
不进入 Git 或通用发行包。旧 19240 Python 控制面已停用，原文件保留。

没有提供真实 DolphinScheduler 集群地址，不能将多地址 HTTP/TCP 探针测试宣称为真实
DS 集群验收。MCP 许可证状态为 NOT_INSTALLED；许可证、外部模型和客户网络配置由部署方提供。
