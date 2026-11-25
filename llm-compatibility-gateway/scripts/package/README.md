# LLM Compatibility Gateway 1.1.1 离线部署

适用于 Linux amd64。部署机需要 Docker、Bash、OpenSSL 和 SHA-256 校验工具。系统未提供 `docker compose` 时，安装脚本会调用包内的 Compose，安装过程支持断网执行。

```bash
sudo bash install-config/install.sh install 192.168.195.221
sudo bash install-config/install.sh status
sudo bash install-config/install.sh password
```

把示例 IP 换成客户端访问的内网域名或 IP。安装器首次启动时生成自签 TLS 证书和随机凭据。模型 API 默认提供 HTTP 18080 和 HTTPS 8443，管理台使用 HTTPS 18081。管理员可通过 `password` 查看初始密码。客户端协议可在「接入配置」选择，上游地址可独立配置为 HTTP 或 HTTPS。

安装目录、端口和监听地址可在 [manual-install.env](manual-install.env) 设置。HTTP 默认绑定 `0.0.0.0`，可用 `GATEWAY_HTTP_BIND` 指定绑定地址。只部署 HTTPS 时，在首次安装前设置 `GATEWAY_ENABLE_HTTP=false`。

已用旧包安装的网关可直接启用 HTTP。执行时会重建网关容器并保留现有密码、证书和数据卷。

```bash
sudo bash install-config/install.sh enable-http
```

重复安装同一版本会使用已有的密码、加密密钥和数据卷。现有非本包创建的安装继续使用其原有部署目录和 Compose 配置。需备份安装目录的 `.env` 及 Docker 中的配置、证书和日志卷，并限制备份访问权限。

此发行版包含模型映射 `上游模型 ID → 支持模型`、可带基础路径的上游地址、HTTP/HTTPS 接入配置、请求趋势与请求日志。镜像身份和归档校验值在 `offline-images/`。
