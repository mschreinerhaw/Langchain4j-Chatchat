# Cline + Python 直接执行环境

该镜像将已有 Python 科学计算镜像与官方 Cline CLI 原件合并。Cline 安装在 `/srv/cline`，直接在 `/srv/workspace` 读写数据、编写和执行程序。入口只装载显式挂载的官方配置文件，然后用 exec 启动 Cline；没有 Java、Runtime 数据转换、受管 Python 回调或业务完成门禁。

远程构建目录：`/srv/cline-python-runtime-20261110/build`。将远程已有 `@cline/cli-linux-x64` 发行目录完整复制为构建上下文中的 `cline/`，与本目录的 Dockerfile、entrypoint.py 一起构建：

```sh
docker build --pull=false --network=none \
  --build-arg PYTHON_BASE=chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022 \
  -t cline-python-direct:20261110-r2 .
```

每次任务使用独立工作目录和状态目录，将数据原文件直接放入工作目录。模型连接文件包含 provider、apiKey、model、baseUrl，从运行时只读挂载的文件加载，由该版本的官方 `cline auth` 写入容器私有配置；不能放入镜像或 Git：

```sh
docker run --rm --init --memory=3g --cpus=2 --pids-limit=256 \
  --cap-drop=ALL --security-opt=no-new-privileges \
  -v /srv/cline-python-runtime-20261110/workspace:/srv/workspace \
  -v /srv/cline-python-runtime-20261110/state:/srv/state \
  -v /srv/cline-python-runtime-20261110/private/connection.json:/run/secrets/connection.json:ro \
  -e CLINE_CONNECTION_FILE=/run/secrets/connection.json \
  cline-python-direct:20261110-r2 \
  cline --json --auto-approve true --provider openai \
  --model qwen3.7-plus --thinking low --timeout 1200 '用户原始任务'
```

工作与状态目录应授权镜像的 runner 用户写入。输入是原文件的独立副本，Cline 可以自主处理工作目录中的数据；验算程序在任务外比较输入 Hash、产物和真实统计。容器不挂载宿主 Docker socket、Java/MCP 凭据或其他业务目录。官方 CLI 自身的默认错误／循环保护保留并记录，未通过改造 Core 或大整数伪造无限执行。
