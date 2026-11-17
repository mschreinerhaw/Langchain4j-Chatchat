# Deployment Ops 1.1.0 纯 JDK 交付报告

本发行覆盖 V1.0 任务书的 P0/P1，并实现后续“平台仅依赖 JDK”的约束。
平台是本地 Spring Boot fat JAR、嵌入式 H2 和模块化 Vue，不需要主机 Python。
仅注册平台自己的 systemd 服务，Docker、Runtime 和业务数据库不可达时仍能启动。
历史 `deployment-ops-v1.0.0` 为外置执行器版本，不作为此次交付。

## 实现与运行入口

安装/启动/停止/重启/状态、首次管理员初始化、真实挂载检查、H2迁移和一致性备份恢复；
离线RSA签名清单、断点分片、镜像冷导入/标签/删除/导出；容器创建、启停、日志和指标；
应用依赖编排、参数/秘密/目录/端口/命名卷、版本升级、失败回滚、显式恢复和部署历史；
主机与应用总览、24组件及三个Unix执行器健康、告警、入口、用户角色和审计均连接真实服务。
平台维护使用独立Java进程，失败恢复两个H2、实例配置和原JAR，不停止业务容器。

安装协议写明固定镜像ID和版本、组件依赖、ENTRYPOINT/CMD、数据卷和健康门禁，
禁止秘密默认值。密码由用户在网页填写；不在公有任务、日志或通用发行包中保存明文。
完整协议规范见 [INSTALLATION-PROTOCOL.md](INSTALLATION-PROTOCOL.md)。

实际交接实例：

| 对象 | 当前值 |
| --- | --- |
| 平台入口 | http://192.168.195.221:19240 |
| 平台服务 | deployment-ops-jdk |
| 管理数据 | /mnt/disk01/deployment-ops-jdk/data |
| Runtime / MCP | http://192.168.195.221:19410 / 19411 |
| 环境 / 项目 | runtime-v1 / ops-env-runtime-v1 |
| 业务目录 | /mnt/disk01/ops/r/6462-ee1b43f41b32 |
| 凭据 | /mnt/disk01/deployment-ops-jdk/delivery-access.env，root/0600 |

管理员在首次初始化页面创建，发行包没有通用默认密码。旧19240 Python控制面已停止
并禁用，文件保留。此前QA管理实例和业务目录保留，不删除客户数据卷或持久化目录。

## 源码与构建

分支 `feature/deployment-ops-offline-20261115`，基线 `8dbd6b915`。
发行Java代码提交 `2f460f1a114855ca153d41b8a8a0fb4cead221cb`。
其后的文档和执行证据提交不改变发行二进制。
正式标签 `deployment-ops-v1.1.0` 标记包含最终文档和证据的交付提交；JAR构建源提交仍为上述2f460f1a1。
源码在服务器以 `PATH=/nonexistent /opt/jdk-17.0.5/bin/java scripts/BuildNativeOffline.java`
构建，未使用服务器Python、Node、Maven或在线仓库。JAR内含45个依赖，无Python运行资源。
完整源码包同时携带已构建Vue资源，服务器重新编译Java不需要开发环境。

自动检查：Java17项、Python兼容51项全部通过；Vue格式检查和生产构建通过，
资源构建hash `712261fb736088a4`。Python仅用于开发端兼容测试和SSH自动化，不是平台运行依赖。

## 真实验收及证据边界

机器报告：[acceptance.json](evidence/final-jdk-20261117/acceptance.json)，12个必需场景；
报告逐项绑定证据SHA-256，不将Mock或代码审查当作实际运行验收。

- 最终JAR：全新管理员初始化、Docker Socket不可访问且业务网络隔离时独立运行、
  H2配置重启持久化、真实主机指标、隔离挂载视图缺盘退出1、网页平台升级和H2恢复通过。
- 网页从空管理状态安装Runtime，任务 `acd787e6cb7d4a35b6cad3b20e8a240c` 成功，
  24组件固定镜像及argv核对、Temporal、OS/MCP登录、Core、Python依赖验证通过。
  完成后仅修改ServiceInstaller的正常JVM退出码声明，其余Docker/协议/部署/Vue字节相同，
  对比证据为 `server-build.json`。最终JAR又验证三个实际Unix执行器HEALTHY及资源策略生效。
- 最初因历史空网络耗尽默认Docker地址池而失败。只移除5个无容器引用且标签明确的历史
  QA网络后，通过页面恢复原任务成功；失败记录保留，不改写。未删除生产网络或数据卷。
- 真实Runtime镜像升级、就绪失败自动回滚及手动回滚，通用协议配置注入/密码认证、
  应用启停、镜像删除后冷导入已在前序JDK候选实测；报告保留其执行版本，未伪称在最终
  JAR重新执行所有历史场景。完整24组件合同的实际兼容升级也已通过。
- 平台停机/重新启动：逐项对比53个运行容器的ID、状态、StartedAt，全部相同。
  原用户保留基础容器为4个，未将已经被用户清理的历史45容器计为当前基线。
- 平台错误JAR自动回退、维护进程被定向SIGKILL后的显式恢复均实际执行过；没有向业务进程发信号。
- GUI资源17项、监控/权限6项及专用镜像3项实际通过；包括真实SSE日志、CPU/内存指标、
  不可达探针告警、只读用户403和删除测试容器仍保留数据卷。

服务器是指定服务器上的新状态和隔离实例，未重装生产操作系统，未停止主机Docker，
未卸载真实 `/mnt/disk01`。磁盘仍为 `/dev/sdc` XFS。

## 离线制品与全新安装

服务器目录 `/mnt/disk01/deployment-ops-releases/1.1.0/`：

| 制品 | SHA-256 |
| --- | --- |
| deployment-ops-1.1.0.jar | ba2d3cc7a51536fbd97f3e6cd661d2f0f726bb0b7b6b2792e3dfe8df3a2b1bfa |
| deployment-ops-1.1.0.zip | ac889ac3e522630e34818daeb6704aa8a2ce3aae2d1bdc5950a5cb488da9c536 |
| deployment-ops-1.1.0-offline-linux-x64.tar | bce95d9342d4fe9d6e5a32713e4572aa11ec6a1e5234782593428f21f1b746a6 |

完整离线TAR为6,705,674,240字节，镜像归档入口和整个TAR的SHA均已实际检查通过。
正式包新实例安装结果，见同目录 `formal-*` 执行证据。
正式ZIP已在19320/19322的新状态实例完成首次安装、网页初始化和真实Docker清单验证；
该验收服务随后正常停止并禁用，Result=success。最终主实例仍为19240，24个Runtime组件
保持原冷部署容器ID，53个运行容器的状态和StartedAt再次核对一致，见 `final-ready.json`。
TAR携带平台ZIP、完整6.2GiB签名Runtime镜像包、安装说明、验收报告和SHA256SUMS。
本地JAR/ZIP在 `target/releases/`；大镜像介质在服务器，不放入Git。

全新服务器准备JDK17和真实数据盘，校验TAR、解包、校验内部SHA256SUMS，使用JDK `jar xf`
解开平台ZIP。可直接 `java -jar lib/deployment-ops.jar`；若按源码部署，先执行离线Java构建。
网页初始化后导入离线应用包，检查协议、填写密码、预检并确认部署。
完整命令见 [INSTALL.md](evidence/final-jdk-20261117/INSTALL.md)，升级/恢复见
[JDK-OPERATIONS.md](JDK-OPERATIONS.md)。

## 外部条件

没有提供真实DolphinScheduler集群地址或凭据，不能宣称真实DS集群接入验证通过；
多地址HTTP/TCP监控、真实故障和告警已验证。业务许可证状态为NOT_INSTALLED，MCP认证和
工具发现已通过，受许可证保护的目录、外部模型及客户网络需由部署方配置。
H2备份覆盖管理与执行记录，不替代业务数据库、数据卷、离线介质和发布信任公钥的备份。
