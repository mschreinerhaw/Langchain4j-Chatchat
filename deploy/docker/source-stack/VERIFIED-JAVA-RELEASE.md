# Java 源码构建与验收

历史发布把差量源码的修改时间设为 Unix epoch。Maven 资源复制默认用源文件和目标文件的时间戳决定是否复制。目标尚未创建时，Java 文件 API 返回的目标时间戳也是 0，因此即使执行 `clean package`，部分资源仍可能漏包。父 POM 对资源复制显式启用覆盖。JAR 的条目时间使用固定值，服务器构建仍保留源码、基础镜像和产物的独立哈希。

在准备发布的工作区执行以下命令，使用工作区外的输出路径。源码清单包含 Git SHA、分支、工作区是否有改动以及每个纳入文件的大小和 SHA-256。

```bash
python3 scripts/package-source-stack.py --output /path/to/runtime-source.zip
```

将源码包与 `scripts/build-source-stack.py`、`scripts/source_release_verification.py` 一起传到服务器。服务器应预先准备 Docker、脚本所声明的基础镜像和 Maven 工具。为每次构建指定唯一标签和一个全新的发布目录。

```bash
python3 build-source-stack.py \
  --root /path/to/new-build \
  --archive /path/to/runtime-source.zip \
  --maven-home /path/to/apache-maven \
  --tag isolated-release-unique \
  --services mcp api
```

构建日志与 `evidence/build-result.json` 一同保留。构建将前端生成后执行 Maven `clean package`，从同一个构建镜像复制出应用 JAR 执行检查。检查通过后，回执记录镜像 ID、JAR SHA-256 和每个资源的哈希。资源缺失、与源码字节不一致或前端入口为空均使构建退出失败。结果中的资源清单涵盖各服务的自有主资源及生成的前端入口。

对已有健康基线的隔离项目，使用同一回执部署。项目名称须明确包含独立的 `isolated` 字段，以区分生产项目。Compose 文件和可选的环境文件从指定隔离目录读取。

```bash
python3 scripts/activate-verified-isolation.py \
  --stack-root /path/to/isolated-stack \
  --project runtime-os-isolated-acceptance \
  --compose compose.yaml \
  --receipt /path/to/new-build/evidence/build-result.json \
  --services mcp api \
  --evidence-dir /path/to/new-build/activation
```

脚本记录上一版镜像 ID，核对 Compose 配置差异只涉及本次镜像，启动时禁止构建或拉取。镜像身份或健康检查失败时，脚本恢复上一版并核对其健康状态。保留 `activation-result.json`、上线覆盖文件和回滚覆盖文件。后续针对同一孤立环境执行 Compose 管理命令时带上当前生效的覆盖文件，并继续验证网关和应用端到端任务。
