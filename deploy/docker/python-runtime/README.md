# ChatChat Python Runtime

构建平台受控的固定版本镜像：

```bash
docker build -t chatchat-python-runtime:3.11-v1 deploy/docker/python-runtime
```

镜像默认以 UID/GID `10001:10001` 运行，依赖全部固定版本。修改依赖时应更新镜像版本标签并在 MCP Python 管理中创建新的环境记录；不要覆盖已发布标签。
