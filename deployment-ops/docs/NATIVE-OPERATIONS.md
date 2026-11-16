# Deployment Ops 原生安装与运维

本文件适用于原生候选发行包。候选包不代表 V1.0 全部验收已经通过；以随包验收报告中的实际结果为准。

## 安装前提

Linux x86_64、systemd、Python 3.11+、Java 17+，以及真实挂载在 `/mnt/disk01` 的独立数据文件系统。源码编译还需 JDK 17 的 `javac`。平台自身不需要 Docker、Runtime OS 或业务数据库。管理容器和部署业务时才需要本机 Docker Engine 及足够资源。

准备完整平台离线 ZIP、配套 SHA-256 文件，以及 Runtime OS 签名离线应用包和校验和。平台 ZIP 内含 Spring/H2 运行依赖和源码编译依赖种子，不需要 Maven、npm、公网仓库或开发机目录。发行包不含客户许可证、模型服务或生产凭据。

```sh
findmnt -M /mnt/disk01
sha256sum -c deployment-ops-1.0.0-rc.1.zip.sha256
mkdir -p /mnt/disk01/deployment-ops-releases/1.0.0-rc.1
python3 -m zipfile -e deployment-ops-1.0.0-rc.1.zip /mnt/disk01/deployment-ops-releases/1.0.0-rc.1
cd /mnt/disk01/deployment-ops-releases/1.0.0-rc.1
```

## 服务器源码编译与首次安装

预编译 JAR 可直接安装；按源码部署时先在服务器运行以下编译。编译器只使用包内依赖。Vue 已包含离线构建后的静态文件；修改 Vue 源码需要另备 Node/npm 构建环境和依赖。

```sh
export JAVA_HOME=/opt/jdk-17.0.5
export TMPDIR=/mnt/disk01/deployment-ops-build-tmp
mkdir -p "$TMPDIR"
python3 scripts/build-native-offline.py --runtime build/boot-runtime.zip --output lib/deployment-ops.jar
read -rsp 'Initial administrator password: ' ADMIN_PASSWORD; echo
export ADMIN_PASSWORD
python3 deploy/native/install.py --java "$JAVA_HOME/bin/java" --bind 0.0.0.0 --enable-writes
unset ADMIN_PASSWORD
```

默认平台端口 19240，执行器仅监听本机 19242。使用防火墙限制管理网访问。打开 `http://服务器:19240`，初始账号 `admin`，密码为安装时输入的值。密码不会在重启或重装脚本时覆盖现有用户。省略 `--enable-writes` 将执行器设为只读。

安装器创建 `deployment-ops` 系统用户、两个原生服务及配置文件 `/mnt/disk01/deployment-ops/manager.env`，不会容器化平台。Web 服务以普通用户运行，执行器为限定范围的本机 Docker 管理进程。不要公开执行器端口或泄漏令牌。

```sh
sh deploy/native/status.sh
sh deploy/native/stop.sh
sh deploy/native/start.sh
sh deploy/native/restart.sh
```

这些命令只操作平台和执行器两个原生服务。业务容器独立运行。自定义实例需在每条命令前设置 `OPS_SERVICE=deployment-ops-实例名`，安装时相应传入 `--name`、`--state` 和不同端口。平台发行目录必须保留，systemd 的启动脚本引用它。

## 配置与存储

`manager.env` 使用 systemd EnvironmentFile 格式，请保持 root 所有、组 `deployment-ops`、权限 0640。禁止将其当作任意 shell 脚本执行。

| 配置 | 用途 |
| --- | --- |
| OPS_HOME / JAVA_BIN | 当前发行目录与 Java 17 路径 |
| OPS_DATA_DIR | H2 所在目录，必须在真实 `/mnt/disk01` 下 |
| OPS_STATE / OPS_PACKAGES / OPS_TRUST | 执行器任务、离线包、可信公钥 |
| OPS_APP_DATA / OPS_RUNTIME_ROOT | 业务持久化目录；原生 Runtime 使用短目录避免 Unix socket 长度限制 |
| OPS_PORT / OPS_BIND | 平台监听地址与端口 |
| OPS_EXECUTOR_PORT / EXECUTOR_TOKEN | 本机执行器连接；令牌应保持保密 |
| OPS_DB_PASSWORD | H2 加密连接凭据；备份后恢复仍需保留 |
| OPS_READ_ONLY | 1 为只读，0 允许经确认的管理操作 |
| OPS_INSTANCE_ID | 实例所有权标识，禁止随意更改或跨实例复制 |

挂载丢失或路径逃逸会拒绝启动及状态写入，禁止通过创建同名系统盘目录规避检查。不要在生产机卸载数据盘做验收。

## Runtime OS 离线部署

1. 登录“镜像与发行库 / 离线导入”，选择签名应用 ZIP 并填写发行方 SHA-256。大包以 4 MiB 分片上传，可以重选相同文件续传。
2. 公钥必须经过管理员审核并安装在 `OPS_TRUST`。原生 Runtime 安装器需要专用签名能力 `runtime-os-native/v1`；普通应用签名不能获得该权限。只分发公钥，私钥由发行方保管。
3. 在“部署发布”选择主机、新环境名、已验证版本，配置访问地址、入口端口、MCP 端口及 Runtime 管理员密码。
4. 执行预检，核对独立项目、短数据目录、资源预算、不可变镜像和端口；输入完整环境名后提交。
5. 在任务中心查看真实步骤、安装器日志及失败原因。只有产品安装器完成 Native Core/Python 验证后才会报告成功。

已有生产容器只读展示，不会因名称相似而被接管。新部署使用独立 `ops-env-环境名` 项目。不可变版本不允许换包覆盖。应用升级须声明兼容版本并保持已验证的数据结构与基础组件；不兼容数据库迁移需要独立验证。

失败任务保留容器、日志及持久化目录。明确中断状态后可从任务中心恢复；不要另建同名环境绕过所有权检查。升级失败会在安全允许的情况下执行原版本恢复；若安装器运行结果未知则保留待处理状态，禁止同时运行另一个安装器。任何恢复均不删除业务数据卷。

## H2 备份与恢复

在“系统管理 / H2 备份恢复”创建一致性备份，可下载 ZIP。备份包含管理用户、配置、审计与管理记录；**不包含**业务数据库、数据卷、执行器部署目录及私有凭据。生产级灾备还应按业务自身流程备份上述内容，并离线保管 `manager.env` 与执行器状态，防止跨时间点错配。

恢复前停止发起新任务，选择备份并准确输入要求的确认文本。平台会校验封装、校验和、H2 数据库及模式版本，保存当前数据库后只停止 Web 服务进行替换，再启动并检查健康。恢复后历史会话失效，重新登录。恢复失败保留当前数据库副本及维护日志，按任务中心状态处理，不要手工删除 `maintenance/active.lock` 来绕过正在进行的任务。

## 平台自身升级

在“系统管理 / 平台升级”上传完整原生发行 ZIP 与 SHA-256，核对版本及作用范围，按要求确认。执行器验证全部文件清单并保存当前配置和数据库，切换平台发行路径，仅重启平台两个服务。业务容器保持运行。健康检查失败会恢复原配置与 H2，并记录恢复健康状态。

升级后的旧发行目录、原始引导脚本与维护快照请保留，直到完成回归及备份。不要使用 Docker Compose down 或停止业务数据库完成平台升级。

## 故障定位与验收

```sh
systemctl status deployment-ops deployment-ops-executor
journalctl -u deployment-ops -u deployment-ops-executor --since '-15 minutes'
tail -100 /mnt/disk01/deployment-ops/logs/platform.log
curl -f http://127.0.0.1:19240/api/health
```

登录后的系统健康页显示真实 H2、挂载和节点状态。Docker 不可用时平台应保持可登录并明确显示不可达。健康探测支持 Runtime OS、Coding Agent、MCP、Python 的 HTTP/TCP 地址及 DolphinScheduler 多地址分组；需填写真实服务地址。未配置或不可达时不会伪造健康数据。

`tests/native-product-browser.cjs` 验证真实管理页面和隔离容器生命周期；`tests/native-runtime-browser.cjs` 验证大包导入及向导部署；`tests/native-server-acceptance.py` 使用受控私有视图验证独立运行与挂载缺失。部署在同一主机的验收不能代替所有操作系统发行版的适配认证。
