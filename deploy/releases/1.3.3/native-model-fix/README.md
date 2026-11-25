# Runtime OS Native 模型调用补丁

修复 Native 控制服务重新编码任务凭据导致 Runtime 拒绝模型绑定的情况。原签发凭据用于 Runtime 模型、技能和 MCP 回调。专用执行器继续使用其独立签名。

适用于已安装的 Linux amd64 Runtime OS 1.3.3 Compose 实例。依赖 Docker 和 Python 3.8 或更新版本，含离线 Compose。解压后在维护窗口执行。

```bash
sudo python3 apply.py --root /opt/ros133
```

原实例在 `/opt/ros21` 时，将参数改为 `--root /opt/ros21`。加 `--check` 可查看将更新的服务。脚本离线复用本机镜像，仅替换 Native 模型控制服务，保存原 Compose 配置，启动失败时恢复原镜像配置。

完成后新建一个使用 `qwen-3.6-plus` 的任务，在任务监控与网关请求日志中核对模型调用。
