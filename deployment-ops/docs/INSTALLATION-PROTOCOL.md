# 安装协议 deployment-ops-install/v1

入口为 `java -jar deployment-ops.jar`（不是 `jar -jar`）。首次访问页面设置管理员，
安装令牌由启动终端提供，防止未初始化平台被他人抢占。无需先执行 install.sh
或先安装 Runtime。Linux 数据盘必须真实挂载 `/mnt/disk01`；平台及嵌入执行器
只要求 JDK17+，不需要宿主 Python。部署业务容器时需要 Docker。默认仅监听本机19240。
使用 `OPS_BIND=0.0.0.0` 才开放管理网。停止 JVM 只停止自己的执行器，不停止业务。

## 协议载体

延续已有签名离线包，避免产生第二套不兼容部署引擎：

```
application.ops.zip
  manifest.json       # 安装协议，schemaVersion=1
  checksums.json      # 每个负载文件 SHA-256，包含 manifest.json
  signature.json      # 受信公钥标识及 checksums 的签名
  images/*.tar        # 离线 Docker-save 镜像，不在线拉取
  assets/*            # 已审核的配置/初始化资源
```

`manifest.json` 是最终执行协议。发行方配方可以使用 `images.*.source` 指定明确
版本的本地镜像；`scripts/build-package.py` 将其解析为不可变镜像 ID、实际大小、
归档路径并签名。最终协议只接受 `id/archive/size`，不能用可变 tag 替代镜像 ID。
参考 `recipes/protocol-example/manifest.json`；该配方需提供对应本地业务镜像后打包。

## 字段和执行语义

| 字段 | 含义与约束 |
| --- | --- |
| schemaVersion | 固定为1，不认识的字段/版本拒绝导入 |
| name / version / platform | 应用名、发行版本、linux/amd64 等目标平台 |
| images | 镜像角色→不可变ID、归档、大小；逐个验证离线镜像 |
| parameters | 安装页面表单，包括类型、说明、必填、枚举、范围及格式 |
| volumes | 归属于该环境的命名卷；物理路径由平台约束在数据盘，不接受任意宿主路径 |
| components | 服务、一次性任务或只验证的外部依赖 |
| dependsOn | 依赖组件ID；拒绝未知依赖及循环，满足健康门禁/任务成功后启动下游 |
| entrypoint / command | 容器 argv 字符串数组；不执行平台宿主机 shell |
| environment | 环境变量模板，用 `${参数名}` 引用页面输入 |
| mounts / files / ports | 卷、已签名配置文件和端口映射；支持只读挂载 |
| health | 每个服务必须声明 http/tcp/docker 健康门禁，容器启动不等于应用就绪 |
| memoryBytes / cpus | 组件资源限制 |
| upgradeFrom / dataCompatibility | 允许升级来源与数据兼容承诺；未知迁移不能盲目自动回滚 |
| links | 安装后业务入口；禁止包含秘密参数 |

需要原样传递容器自身 `${HOME}` 等变量时，协议写为 `$${HOME}`。该转义仅处理
协议模板，不会再次解释用户填写的密码或其他参数，参数中的美元符号保持原样。

`kind=job` 组件用 `runOn=install/always` 表示运行时机，必须退出成功才能继续；
`retrySafe` 表明是否允许明确恢复时重试。失败记录状态、组件和日志；结果未知时
要求人工检查并从任务页面恢复，不因平台重启重放创建命令。停止、升级和回滚
始终限定到确认的管理环境，不能接管同名生产组件或删除数据卷。

## 用户名与密码

用户名也是参数；密码必须 `secret:true`，不得包含 `default`，最终包导入校验
会拒绝秘密默认值。业务需要的账号/密码由管理员在页面填写，页面确认密码等
校验通过后才提交部署。配方、签名包、草稿、URL、公开任务JSON与日志不能包含密码。
执行器为安装/升级而保存的私有参数仅落于受控实例目录；不宣称完全不落盘。
协议更新不覆盖已有业务密码，升级页面不回显旧密码；空值保留的语义由适配器明确处理。

Runtime Native 协议仍使用 `runtimeInstaller.protocol=runtime-os-native/v1` 的专用
公钥能力执行已有产品安装器；该适配器的命令和服务清单由源码发行方审核，普通
协议包不能借此获得 Docker socket 权限。现有 Runtime 管理员名由产品固定为admin，
密码由页面填写；需要可变用户名时必须产品自身支持，不能伪造一个无效表单。
新的 `runtimeInstaller.contractVersion=1` 要求24组件逐项声明启动依赖和显式
ENTRYPOINT/CMD，缺项即拒绝导入。发布工具在无网络、无DockerSocket、无客户目录的
检查容器中调用受信源码的纯配置渲染逻辑，结合固定镜像的默认参数导出合同；不读取运行实例。
原生安装器继续执行产品的初始化及分阶段健康门禁，平台最后核对每个实际容器的
镜像ID和启动参数，不能仅凭容器存在或历史成功文件确认部署成功。

## 单 JAR 与原生服务模式

单 JAR 含模块化 Vue、H2/JDBC 和纯Java执行器，首次启动生成本地实例凭据及发布者公钥。
实例锁防止两个 JVM 同时管理同一目录。`--install-service` 由同一JAR生成systemd服务，
服务模式使用独立Java维护进程升级、恢复和故障回退；显式配置外部执行器时不启动内嵌节点。
单 JAR 的进程部署应由宿主运维负责替换 JAR/重启；现有 systemd 维护 API
不得假装能替换一个没有 systemd 单元的进程。
