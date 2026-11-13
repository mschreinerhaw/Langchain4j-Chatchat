# 报告单层展示与整页滚动

HTML 报告消息取消外层气泡边框、底色和内边距；报告容器取消重复边框、灰底和“分析报告”标题。全屏与下载保留为轻量操作按钮，报告自身的排版及配色保持不变。

沙箱内报告在内容、图片、字体和视口变化后上报自身高度。宿主只接受当前 iframe 的合法高度消息，使报告自然展开，使用外层页面滚动；全屏时由全屏容器滚动。保留 opaque sandbox、原有 CSP、资源哈希校验和网络限制。

29 项相关测试及生产构建通过。原始报告在 1440 和 390 像素视口下均无内层纵向滚动条，动态内容增加和收起后高度均正确同步。正式入口的已部署组件再次读取原始归档报告，两张图片正常解码，高度上报与显示一致。验证摘要见同目录 JSON；原始报告内容及截图未提交至仓库。

已部署网关镜像 `runtime-public-gateway:report-single-surface-20261113`，后端镜像未改动。当前网关通过 `runtime-os-stage-maintenance` 与 `/opt/runtime-os-docker-stage/compose.gateway-ui.json` 定向管理。上一版清单备份在服务器 `/var/tmp/report-single-surface-20261113/gateway-before.private.json`，可用于回滚。
