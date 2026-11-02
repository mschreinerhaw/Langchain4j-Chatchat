# 从源码构建完整 Docker 离线部署包

## 本地构建

构建机需要 Git、Python 3.10+、JDK 17（含 javac）、Maven 3.8+、Node.js 22 LTS、npm，以及启用 Linux containers 的 Docker Engine/Desktop 与 Compose v2。构建时需要访问 Maven、npm、Docker、apt 和 pip 仓库，或使用组织内的对应镜像源。目标平台固定为 Linux amd64。

Windows 在仓库根目录执行：

```powershell
.\build-docker.ps1 -Tag 20261102-r1
# 同时运行 Maven reactor 测试：
.\build-docker.ps1 -Tag 20261102-r2 -RunTests
# 只查看计划，不执行构建或创建文件：
.\build-docker.ps1 -Tag 20261102-r1 -Plan
```

Linux/macOS 也可直接运行同一 Python 入口：

```bash
python3 scripts/build-docker-release.py --tag 20261102-r1
```

流程包括两个 Vue 前端、完整 Maven reactor clean/package、可执行 JAR 与前端资源校验、API/MCP/News/Coding Agent/Python 镜像构建，以及 PostgreSQL、Redis、OpenSearch、Temporal、Nginx 镜像收集。MCP 使用的 Linux Docker CLI 自动从指定官方镜像提取，也可通过 `-DockerCliPath` / `--docker-cli` 指定现有 Linux amd64 二进制；Windows 的 docker.exe 不能放入 Linux 镜像。

默认只执行构建检查，跳过 Maven 测试；`-RunTests` 开启测试。构建成功不等同于已完成客户现场业务验收，发行清单将其记录为 `NOT_RUN`。任何步骤失败均返回非零退出码，保留 build.log 和 FAILED 结果，不生成成功交付包。

常用参数：

```bash
python3 scripts/build-docker-release.py --help
python3 scripts/build-docker-release.py --tag site-r1 --output-dir D:/releases/site-r1
# 已有完整 Python 运行时可复用；仍验证镜像平台及 numpy/pandas/matplotlib 导入：
python3 scripts/build-docker-release.py --tag site-r2 \
  --python-image chatchat-python-runtime:3.11-approved --reuse-python-image
```

`--java-image` 指定干净 Java 底座，不能使用已经包含 app.jar 的历史业务镜像。基础设施镜像版本可通过 `--postgresql-image`、`--redis-image`、`--opensearch-image`、`--temporal-image`、`--nginx-image` 调整，实际镜像 ID 会写入发行清单。接入已有 MySQL 时可增加 `--extra-image mysql:8.4`，将数据库检查所需的客户端镜像一并离线交付；该参数可重复使用。`--maven-offline` 只约束 Maven 使用本地缓存，不表示整套构建无需网络。

默认输出位于 `dist/docker-release/<tag>/`，已有目录不覆盖。不要指定 Maven 的 target 目录，因为 reactor clean 会清理它。

```text
<tag>/
  build.log / build-plan.json / build-result.json
  runtime-os-<tag>-linux-amd64.tar.gz
  runtime-os-<tag>-linux-amd64.tar.gz.sha256
  release/
    deploy.py
    images.tar
    release-manifest.json
    SHA256SUMS
    README.zh-CN.md
    deploy/docker/... / deploy/temporal/... / database/init/...
```

整个构建先复制 Git 可见的源码（包括尚未提交的源码改动）到独立的 `work/source`，再在副本中运行 npm 和 Maven clean；不清理原仓库的 target、工作树或已有发布记录。源文件内容摘要写入发行清单。API/MCP 前端输出同样隔离，不覆盖仓库中已跟踪的 static 文件。打包内容采用部署资产目录白名单，不读取运行服务器的配置、数据库、驱动目录或备份。私钥、License 和环境秘密须在部署机器独立配置，JAR 检查也会拒绝匹配的秘密文件名；这不是通用秘密内容扫描。

## Linux 服务器部署

目标机需要 Docker Engine、Compose v2、Python 3.10+。校验并解压交付包，保留目录结构：

```bash
sha256sum -c runtime-os-20261102-r1-linux-amd64.tar.gz.sha256
tar -xzf runtime-os-20261102-r1-linux-amd64.tar.gz
cd runtime-os-20261102-r1
python3 deploy.py load
```

`load` 先检查全部包内 SHA256SUMS，再导入镜像并核对每个标签的镜像 ID 和平台。部署期间复用现有 runtime-deploy.py，使用 `--no-build --pull never`。

全新安装在一个空安装目录生成现场模板：

```bash
python3 deploy.py init --root /opt/runtime-os --mode full
python3 deploy.py prepare --manifest /opt/runtime-os/config/site.json --output /opt/runtime-os/prepared
```

随后填写 `/opt/runtime-os/config/deployment.env`、`site.json` 和独立秘密文件：主机 NIC、数据库/OpenSearch/Redis 密码、OpenSearch 证书及保留公共 CA 的 truststore、内部通信凭据、WSA 凭据、License、模型配置。模板不内置测试账号、客户模型或客户数据。OpenSearch 节点证书名称应覆盖 `opensearch`，宿主探测地址也应匹配 SAN。详细证书、秘密权限、备份回执和业务契约见包内 `deploy/docker/RUNTIME-DEPLOYMENT.zh-CN.md`。

驱动和私有搜索证书继续使用主机持久化目录：

```dotenv
JDBC_DRIVERS_DIR=/opt/runtime-os/lib/drivers
NATIVE_SEARCH_TLS_DIR=/opt/runtime-os/config/native-search-tls
```

将原驱动和伴随依赖完整放入 `<驱动目录>/oracle/`、`dm/` 等类型子目录。两个应用只读挂载整个目录，容器重建不会丢失。私有搜索 CA 配置可参考 `native-search-tls.example.yml`，无需定制时保持空配置目录。不要将厂商驱动打入镜像。Python 沙箱镜像已离线导入，使用 deployment.env 中的 `PYTHON_RUNTIME_IMAGE` 在 MCP 发布对应 Python 环境，业务模板、模型和数据授权沿用产品现有管理流程。

准备完成后：

```bash
python3 deploy.py plan --manifest /opt/runtime-os/config/site.json --output /opt/runtime-os/rendered
python3 deploy.py check --manifest /opt/runtime-os/config/site.json --output /opt/runtime-os/rendered
python3 deploy.py up --manifest /opt/runtime-os/config/site.json --output /opt/runtime-os/rendered
# 配置现场 acceptance 契约后执行真实业务验收：
python3 deploy.py verify --manifest /opt/runtime-os/config/site.json --output /opt/runtime-os/verification --write-probe
```

新安装提供 PostgreSQL、Redis、TLS OpenSearch、独立持久化 Temporal 与全部应用服务。缺少配置会阻止启动或验收，不会以关闭 TLS、替换空业务库或固定成功值绕过。新装空库的真实模型、采集源和业务数据仍需配置；默认 acceptance 为空时不会报告业务验收通过。

已有环境使用 `--mode existing` 生成模板，或保留现有 site.json，指向原数据、驱动、License、密钥、模型和数据库地址。先完成 snapshot、备份与任务排空，再执行 check/up；不要在已有业务目录使用 full 初始化。此脚本不会自动连接或更新当前部署服务器。
