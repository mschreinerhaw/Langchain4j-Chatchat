# Deployment Ops V1.0 实施与验收记录

## 实现范围

本地 Java 17 Spring Boot 与嵌入式 H2，执行器为独立本机服务，平台不容器化。
H2 模式版本 2；启动及写入前检查真实 `/mnt/disk01` 挂载和路径，无系统盘回退。
Web、执行器和业务生命周期独立。

Vue 使用 47 个单文件组件，包含懒加载路由、工作台布局、领域 API、
会话/集群状态、轮询组合函数、公共表格/状态/图表/确认组件和独立业务页面。
页面覆盖总览、主机、镜像库、容器、应用组件/配置/监控/历史、五步部署向导、
任务恢复、告警、审计及配置/用户/探针/H2/平台升级。静态资源离线提供，无 CDN。
主要管理操作均连接真实服务。

签名包支持镜像哈希、公钥能力校验、依赖编排、端口/环境变量/持久化映射、
预检、精确对象确认、审计和明确失败路径。原生 Runtime 适配已有 Native/Temporal
安装器，保留业务架构；普通签名包无原生安装器权限。外部生产容器只读展示。

## 实际执行

服务器 `192.168.195.221`，Linux x86_64，JDK17，`/dev/sdc` XFS 数据盘。
平台用包内依赖和 javac 在服务器源码编译，无 Maven/在线仓库。
业务代码检查点 `3b0987cbd367be49cdede72ec9c0e8694be65b41`；后续发行报告
和验收脚本提交见 `release.json`。证据均在 `evidence/native-product-20261117/`：

| 场景 | 真实证据文件 |
| --- | --- |
| 空状态离线安装、初始化登录 | final-cold-install.json |
| H2 持久化、依赖不可达独立运行、缺盘拒启 | final-alert-jar-isolation.json |
| 主要页面及真实容器启停/日志/资源/删除保留卷 | final-vue-container-regression.json（17 项） |
| TAR 导入、SHA、删除确认、版本标签 | final-vue-offline-images.json（3 项） |
| 13.4 GB 完整归档实际导入，13 个唯一镜像 | final-full-media-load.json，仅验收 Runtime 独立 Docker daemon |
| Vue 向导部署与中途平台重启恢复 | final-runtime-browser.json、final-inflight-restart.json、final-runtime-deployment.json |
| 实际替换业务镜像、失败自动回滚 | final-image-upgrade.json、final-image-failure-rollback.json |
| 页面手动回滚、实际 OS/MCP 登录 | gui-manual-image-rollback.json、final-runtime-login.json |
| 平台升级、故障 JAR 恢复 | final-rc6-platform-upgrade.json、platform-upgrade.json |
| H2 实际恢复、配置恢复、旧会话失效 | final-release-h2-restore.json |
| 原生执行器、探针、告警、只读权限 | final-vue-admin-monitoring.json |
| 构建与自动化测试 | local-build-tests.json：Java 12、Python 51，Vue 构建/格式检查通过 |

Runtime 服务组 24/24 运行。Native Core、Python、Temporal 持久化和管理登录
由源码产品安装器真实验证；实时 Unix socket 探测覆盖三个执行器。
完整导入首次被 Docker 拒绝的失败记录保留在 full-media-first-failure.json；
校验只读重复归档并去重后，同一归档复测通过，不改写失败记录。

原有 20 个生产容器在受控操作前后 ID/StartedAt 不变；最终平台维护核对全部
44 个运行中业务容器，均不停止、不重建。部署中平台重启保留同一安装器，
由页面明确恢复。新验收数据库的唯一测试记录在升级、自动回滚及手动回滚后保留。
只移除过验收创建且已停止、通过项目/镜像/目录核对的旧 QA 容器；所有数据卷和
持久化目录保留。未停止主机 Docker，未卸载真实挂载，未递归修改业务属主。

## 发行与安装

平台包 `deployment-ops-1.0.0.zip` 包含 JAR、Java/Vue/执行器源码、编译依赖种子、
install/start/stop/restart/status、systemd、配置、公钥、配方及验收脚本。
包内 `docs/acceptance/report.json` 绑定源提交、服务器 JAR SHA 和全部必需场景证据。
完整介质 `deployment-ops-1.0.0-offline-linux-amd64.tar` 另含 Runtime 签名镜像 ZIP、
操作文档与校验和；不含私钥、客户数据或实例密码。位置和摘要见最终交付记录。

干净服务器需 Linux x86_64/systemd、Python3.11+、JDK17+ 和真实数据盘。
平台启动不依赖 Docker，部署 Runtime 时需本机 Docker Engine。
先校验 SHA，将 ZIP 解压到 `/mnt/disk01`，用包内依赖源码编译，再执行
`deploy/native/install.py` 初始化管理员。详见 `NATIVE-OPERATIONS.md`。
导入签名媒体后，从管理页面执行预检、确认和部署，不需要修改源码。

## 适用边界

实际验证指定服务器和隔离实例，未认证所有 Linux 发行版。未提供真实
DolphinScheduler 集群，其生产接入未验证；多地址分组与真实 HTTP/TCP 已验证。
许可证和外部模型由客户提供。H2 备份不包含业务数据库、卷、执行器目录和私有凭据；
全业务灾备需另备这些数据并校验恢复时间点，不能以 H2 ZIP 代替。
