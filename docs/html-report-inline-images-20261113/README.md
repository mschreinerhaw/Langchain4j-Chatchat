# 内嵌图片报告显示修复

已归档的 HTML 报告使用两张 `data:image/png;base64` 内嵌图。加载器此前把所有 `src` 都按交付文件路径解析，因此将合法内嵌图片误报为“报告包含未交付的外部资源”。

修复后，在校验整个报告及资源的 SHA-256 后，允许 IMG 使用受支持的 base64 图片，校验媒体类型、编码、非空内容和大小。真正的外部地址、脚本 data URL 及错误图片格式继续拒绝；iframe 的网络限制保持有效。报告原件及 Silo 对象无需修改或重新生成。

验证：6 项加载器测试通过，前端生产构建通过。使用实际失败报告分别验证源码和正式部署组件，两张图片均解码成功，尺寸为 1406×810、1415×521。在 `http://192.168.195.221:18100/` 登录并加载已授权的原始报告资源，HTTP 环境下的哈希校验和沙箱渲染均通过。原始报告内容及认证令牌未提交到仓库。

前端通过网关镜像 `runtime-public-gateway:inline-images-20261113` 发布，后端镜像未改动。静态资源在镜像中设置为可读，网关根文件系统继续只读。网关所属项目为 `runtime-os-stage-maintenance`，定向部署清单为服务器 `/opt/runtime-os-docker-stage/compose.gateway-ui.json`。部署摘要和浏览器验证结果见本目录 JSON。

回滚镜像为 `runtime-public-gateway:inline-image-rollback-20261113`，原配置备份位于服务器 `/var/tmp/html-inline-image-fix-20261113`。回滚需同时恢复 `public-gateway.conf` 的入口配置和网关镜像，不应清理同项目下其他服务。
