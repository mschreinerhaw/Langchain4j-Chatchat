# Deployment Ops

Native Spring Boot, embedded H2 and Vue 3 run in a single local JAR. The
required data mount is `/mnt/disk01`. The console starts independently of
Docker and business workloads. Business deployments use the local Docker Engine.

```sh
java -jar deployment-ops.jar
```

With Java 17, open `http://127.0.0.1:19240` on the target Linux host. Use the
installation token printed at startup to create the administrator on first use.
Select **部署应用** to import a signed application ZIP and its publisher
SHA-256 file, configure the release, review preflight and follow the deployment
result. The single JAR contains the Web assets and Java executor.

Status and remaining acceptance: [V1 acceptance ledger](docs/V1-REMAINING-WORK.md).
A candidate build is not a formal V1.0 release.

## Install, deploy and operate

Read the complete [native operations guide](docs/NATIVE-OPERATIONS.md) for offline
installation, target-server source compilation, administrator initialization,
systemd controls, Runtime OS media import, deployment, upgrade and backup/restore.
The native offline distribution includes the JAR, source, dependency-only compiler
seed, trusted public keys, scripts and compiled Vue assets. No Maven/npm repository
is needed on the target server. Java 17 and Python 3.11+ are prerequisites.
Docker is needed only for business container operations.

## Product modules

- `src/main/java`: Spring Boot entry point, storage guard, authenticated native API,
  H2 backup/version migration, maintenance and persistent service monitoring.
- `backend/src`: preserved authentication/audit/resource APIs hosted by Spring Boot;
  signed-package publication helpers. The legacy standalone Web server is not
  started by the native distribution.
- `executor`: local Docker execution, immutable operation plans, ownership checks,
  signed offline media, persistent deployment jobs and Native/Temporal Runtime OS
  adapter. Application data is retained through stop, upgrade and rollback.
- `web-ui/src/router`, `layouts`, `views`: lazy domain routes and management console.
- `web-ui/src/api`, `stores`, `composables`: real services, session/resource state,
  polling and operation approval flows.
- `web-ui/src/components`: separate dashboard, applications, containers, deployment,
  metrics, tasks and system management components. There are no mock resources.
- `deploy/native`: installation, start/stop/restart/status and systemd definitions.
- `scripts`: offline source build, native packaging and authenticated local import.

## Build and validate

```sh
cd web-ui
npm ci --offline
npm run build
cd ..
mvn -o package
python -m unittest discover -s tests -p 'test_*.py'
```

Offline dependency caches are required on a development machine when rebuilding
Vue. The delivered native source compiler uses only the bundled dependency seed:

```sh
JAVA_HOME=/path/to/jdk17 python3 scripts/build-native-offline.py \
  --runtime build/boot-runtime.zip --output lib/deployment-ops.jar
```

Real browser and server runners live in `tests/native-*.py` and
`tests/native-*.cjs`. They operate on explicitly scoped acceptance instances, not
production data. Execution evidence is in `docs/evidence/native-product-20261117`.
The formal packager requires every mandatory real scenario and matching evidence
checksums before it accepts a non-candidate version. It never creates a Git tag.

Historical verified P0 instructions are preserved in
[the archived P0 README](docs/HISTORICAL-P0-README.md). They do not define the native
platform installation procedure.

## 使用发行镜像内的脚本部署 Runtime OS

本地构建的 Runtime OS 安装镜像把 `deployment-ops/scripts/runtime-os.sh` 和
`runtime-os-scope.py` 放入镜像的 `/opt/product/scripts/`，并通过
`deployment-ops.runtime-script` 标签声明入口。版本迭代时更新脚本、重建镜像，
再使用 `scripts/prepare-native-runtime.py --installer-image … --version … --output …`
生成发布定义，用 `scripts/build-package.py` 按现有方式签名并打包。发布工具会从镜像读取
入口并写入签名清单。上传验签时，节点会核对清单与实际导入的安装镜像。

在部署向导选择已验证的发行包和目标环境。页面显示镜像声明的检查命令，例如
`sh scripts/runtime-os.sh check`。照页面输入并提交预检。通过后确认开始安装或升级。
平台按独立参数执行入口脚本的 `check`、`install` 和运维动作，不解析自由 Shell 文本。
脚本运行在签名绑定的镜像中，并沿用已有环境目录、升级兼容性及回滚流程。

安装页的实时状态由同一镜像的 `status` 返回。平台根据当前环境的 Compose 项目和服务
清单显示容器及实际运行镜像。全局容器列表、日志、事件和镜像列表按当前节点的归属记录
筛选。已验签发行包及手工导入的镜像属于该节点的镜像清单。

`runtime-os-1.3.2` 的维护包可以继续在现场独立执行。普通 Docker 镜像 TAR 通过镜像页
导入；通过向导执行安装脚本使用受信签名 ZIP。未声明脚本的历史发行包继续沿用原部署流程。
