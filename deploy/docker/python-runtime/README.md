# ChatChat Python Runtime

构建平台受控的固定版本镜像：

```bash
docker build -t chatchat-python-runtime:3.11-v1.2 deploy/docker/python-runtime
```

镜像默认以 UID/GID `10001:10001` 运行，依赖全部固定版本。修改依赖时应更新镜像版本标签并在 MCP Python 管理中创建新的环境记录；不要覆盖已发布标签。

镜像包含 Noto CJK 中文字体，并通过独立于临时缓存的 `MATPLOTLIBRC` 设置 Matplotlib 默认中文字体。SVG 使用字形路径、PDF 嵌入字体，导出后无需依赖用户电脑上的字体。脚本可直接使用中文标题、坐标轴和图例，无需自行下载字体。

发布前在与线上一致的非 root、只读、无网络环境中运行 `verify_chart_fonts.py`，将脚本只读挂载到 `/verify_chart_fonts.py`，将可写输出目录挂载到 `/workspace/output`，并为 `/tmp` 挂载 tmpfs。测试校验三只股票名称、中文标题、坐标轴、负号的字形覆盖，以及 PNG、SVG、PDF 导出的缺字警告。
