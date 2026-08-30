# ChatChat License Center

`chatchat-license` 同时提供可复用的离线验签核心，以及仅供企业内部部署的 License
Center 管理服务。客户的 `chatchat-mcp-server` 只依赖验签核心，不包含私钥、签发接口或
授权文件生成页面。

## 授权流程

1. 客户在 MCP 管理端的“License 授权信息”页面复制目标服务器 MAC 地址。
2. 企业授权人员登录独立 License Center，填写客户、MAC、模块、功能和有效期。
3. License Center 使用 RSA 私钥生成 ZIP 授权交付包，内含 `license.dat`、
   `license-public.pem` 和安装说明。
4. 将交付包提供给客户，把授权文件和验签公钥分别部署到 MCP Server 配置的
   `CHATCHAT_LICENSE_FILE`、`CHATCHAT_LICENSE_PUBLIC_KEY_PATH` 路径。
5. MCP Server 使用公钥验签，并检查本机网卡是否包含 License 绑定的 MAC。

## 生成密钥

```shell
openssl genpkey -algorithm RSA -out license-private.pem -pkeyopt rsa_keygen_bits:3072
openssl pkey -in license-private.pem -pubout -out license-public.pem
```

私钥只部署在企业内部 License Center。客户环境只交付公钥。每次签发时，License Center
会先使用待交付的 `license-public.pem` 验证刚生成的 `license.dat`；公钥不匹配时授权包
生成会立即失败，避免把无法验签的文件交付客户。

## 启动企业内部 License Center

```text
CHATCHAT_LICENSE_PRIVATE_KEY_PATH=/secure/license-private.pem
CHATCHAT_LICENSE_PUBLIC_KEY_PATH=/secure/license-public.pem
CHATCHAT_LICENSE_AUTO_GENERATE_KEYS=true
CHATCHAT_LICENSE_KEY_ID=prod-2026
CHATCHAT_LICENSE_CENTER_USERNAME=license-admin
CHATCHAT_LICENSE_CENTER_PASSWORD=<strong-password>
CHATCHAT_LICENSE_CENTER_PORT=8092
```

```shell
java -jar chatchat-license-1.0.0-SNAPSHOT-server.jar
```

访问 `http://localhost:8092/`，登录后即可根据客户 MAC 生成授权文件。服务未配置管理密码
时会拒绝启动。

### Linux 部署启动脚本

可执行部署包必须使用带 `server` 分类器的 JAR。建议部署时将其重命名为
`chatchat-license.jar`，目录结构如下：

可在项目根目录直接生成完整发布包：

```shell
mvn -pl chatchat-license -am clean package -DskipTests
```

产物为 `chatchat-license/target/chatchat-license-<version>-release.zip` 和同名
`tar.gz`，解压后的目录结构如下：

```text
/opt/chatchat-license/
├── chatchat-license.jar
├── bin/chatchat-license.sh
├── config/license-center.env
├── data/license-center/
├── logs/
└── run/
```

将 `src/main/scripts/chatchat-license.sh` 放入 `bin/`，将
`license-center.env.example` 复制到 `config/license-center.env`，修改管理密码后启动：

```shell
chmod 750 bin/chatchat-license.sh
chmod 600 config/license-center.env
bin/chatchat-license.sh start
bin/chatchat-license.sh status
bin/chatchat-license.sh restart
bin/chatchat-license.sh stop
```

脚本要求 Java 17 及以上，默认将 PID 写入 `run/chatchat-license.pid`，标准输出写入
`logs/chatchat-license.out`。可通过 `JAVA_OPTS` 调整 JVM 参数，通过
`CHATCHAT_LICENSE_JAR` 指定其他 JAR 路径，也可以在启动命令末尾追加 Spring Boot 参数：

```shell
bin/chatchat-license.sh start --server.port=18092
```

### 授权审计数据库

License Center 使用独立 H2 文件数据库保存授权签发与下载审计，默认文件位于
`./data/license-center/license-audit.mv.db`。可通过以下环境变量修改连接信息：

```text
CHATCHAT_LICENSE_DB_URL=jdbc:h2:file:./data/license-center/license-audit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE
CHATCHAT_LICENSE_DB_USERNAME=sa
CHATCHAT_LICENSE_DB_PASSWORD=<generate-a-strong-secret>
```

系统不内置数据库密码。首次启动前必须通过 `CHATCHAT_LICENSE_DB_PASSWORD` 注入独立强密码。
生产部署必须通过 `CHATCHAT_LICENSE_DB_PASSWORD` 替换为当前环境唯一的强密码；密码至少 20 位，
且必须同时包含大小写字母、数字和特殊字符。若部署配置将密码显式留空，启动脚本会调用 OpenSSL
生成随机强密码，写入 `config/license-center.env` 并将权限收紧为 `600`。已经使用其他密码创建过
H2 数据库时，修改连接密码前应先通过 H2 的 `ALTER USER SA SET PASSWORD` 完成密码迁移。

审计记录包含 License 编号、授权对象编码、产品版本、模块权益、用户和 Agent 配额、绑定
MAC、授权周期、签发人、签发时间、下载次数、最后下载时间以及文件 SHA-256 摘要。数据库
不保存签发私钥。生产环境应将 `data/license-center` 纳入定期备份，并与私钥采用不同的备份
权限和保管策略。

## 客户 MCP Server 配置

```text
CHATCHAT_LICENSE_PUBLIC_KEY_PATH=/opt/livemcp/license-public.pem
CHATCHAT_LICENSE_FILE=/opt/livemcp/license.dat
CHATCHAT_LICENSE_FAIL_STARTUP_ON_INVALID=false
CHATCHAT_LICENSE_STATUS_CHECK_INTERVAL_MS=60000
```

客户管理端只提供 `GET /api/v1/license/status`，用于查看授权情况、本机机器码和 MAC 地址。
不存在上传、签发或下载 License 的客户侧接口。

## 授权到期行为

默认采用可运维的“受限模式”：

- 服务继续运行，License 状态页和管理端仍可访问，客户能够查看 MAC、过期原因并申请续期。
- 到期后的新 `tools/call` 请求立即返回 `403`，不会继续进入角色授权或工具执行阶段。
- 已经完成的历史结果和审计记录不会删除；正在执行且已通过入口校验的调用不会被强制中断。
- 服务每 60 秒检查一次 License 状态并记录状态变化；每次工具调用也会实时校验，因此不依赖定时任务才生效。
- 客户替换有效的 `license.dat` 后，后续调用可自动恢复，无需重启服务。

如果部署要求 License 无效时禁止服务启动，可设置：

```text
CHATCHAT_LICENSE_FAIL_STARTUP_ON_INVALID=true
```

该严格模式会使过期或无效 License 阻止 MCP Server 启动，因此续期时需要通过文件系统替换
`license.dat`，不能依赖客户管理页面。

## 安全模型

- 使用 `SHA256withRSA` 签名，修改授权内容会导致验签失败。
- License 绑定标准化 MAC，例如 `MAC-AABBCCDDEEFF`。
- 运行节点会比对当前非回环网卡，不信任可复制的本地 `server.id` 文件。
- 安装文件过期、尚未生效、签名错误或 MAC 不匹配时均不可用。
- License Center 使用独立登录认证，应部署在企业内网，不对客户网络开放。
