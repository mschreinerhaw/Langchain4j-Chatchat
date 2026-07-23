<template>
  <div class="view-stack" v-loading="busy">
    <el-card class="workspace-panel" shadow="never">
      <template #header>
        <div class="panel-heading">
          <div><h2>License 授权信息</h2><p>当前部署的 LiveMCP 产品授权为只读信息。</p></div>
          <el-button plain @click="loadStatus"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
      </template>

      <div class="license-summary">
        <section class="license-status-card"><span>当前状态</span><el-tag :type="statusType" size="large">{{ status.message || '正在读取' }}</el-tag><small>强制校验：{{ status.enforcementEnabled ? '已启用' : '部署过渡模式' }}</small></section>
        <section><span>客户</span><strong>{{ license.customer || '-' }}</strong><small>{{ license.customerCode || '-' }}</small></section>
        <section><span>产品版本</span><strong>{{ license.product || 'LiveMCP' }}</strong><small>{{ license.edition || '-' }}</small></section>
        <section><span>有效期</span><strong>{{ license.expireTime || '-' }}</strong><small>签发：{{ license.issuedTime || '-' }}</small></section>
        <section><span>最大用户数</span><strong>{{ license.maxUsers ?? '-' }}</strong><small>License：{{ license.licenseNo || '-' }}</small></section>
      </div>

      <div class="license-detail-grid">
        <section class="license-panel"><h3>授权模块</h3><div class="license-tags"><el-tag v-for="item in license.modules || []" :key="item">{{ item }}</el-tag><span v-if="!(license.modules || []).length">暂无模块授权</span></div></section>
        <section class="license-panel"><h3>授权功能</h3><div class="license-tags"><el-tag v-for="item in enabledFeatures" :key="item" type="success">{{ item }}</el-tag><span v-if="!enabledFeatures.length">暂无功能授权</span></div></section>
        <section class="license-panel server-panel"><h3>服务器机器码</h3><code>{{ status.serverId || '-' }}</code><el-button link type="primary" @click="copyValue(status.serverId, '机器码已复制')">复制</el-button></section>
      </div>

      <section class="license-panel mac-panel">
        <div><h3>本机物理网卡 MAC 地址</h3><p>申请或续期授权时，请将实际使用的物理网卡 MAC 地址提供给 LiveMCP 企业授权人员；虚拟网卡、容器网卡和隧道适配器不会参与授权。</p></div>
        <div class="mac-list">
          <div v-for="mac in status.macAddresses || []" :key="mac"><code>{{ mac }}</code><el-button link type="primary" @click="copyValue(mac, 'MAC 地址已复制')">复制</el-button></div>
          <span v-if="!(status.macAddresses || []).length">未读取到可用 MAC 地址</span>
        </div>
      </section>
    </el-card>
  </div>
</template>

<script src="../scripts/views/LicenseView.js"></script>
