<template>
  <div class="view-stack license-view" v-loading="busy">
    <el-card class="workspace-panel license-workspace" shadow="never">
      <template #header>
        <div class="panel-heading license-heading">
          <div>
            <div class="license-heading-title">
              <h2>License 授权中心</h2>
              <el-tag effect="plain" round>{{ editionLabel }}</el-tag>
            </div>
            <p>查看当前部署的产品授权、资源配额与服务器绑定信息。</p>
          </div>
          <el-button plain :loading="busy" @click="loadStatus">
            <el-icon><Refresh /></el-icon>刷新授权
          </el-button>
        </div>
      </template>

      <section class="license-hero" :class="`is-${statusTone}`">
        <div class="license-hero-main">
          <div class="license-status-icon"><el-icon><Key /></el-icon></div>
          <div>
            <div class="license-eyebrow">授权状态</div>
            <div class="license-status-line">
              <h3>{{ statusTitle }}</h3>
              <el-tag :type="statusType" effect="dark" round>{{ statusBadge }}</el-tag>
            </div>
            <p>{{ statusDescription }}</p>
          </div>
        </div>
        <div class="license-hero-meta">
          <div><span>授权客户</span><strong>{{ license.customer || '未配置' }}</strong><small>{{ license.customerCode || '暂无客户编号' }}</small></div>
          <div><span>License 编号</span><strong class="license-number">{{ license.licenseNo || '未签发' }}</strong><small>签发于 {{ formatDate(license.issuedTime) }}</small></div>
        </div>
      </section>

      <div class="license-metric-grid">
        <section class="license-metric">
          <div class="license-metric-icon product"><el-icon><Cpu /></el-icon></div>
          <div><span>产品与版本</span><strong>{{ license.product || 'LiveMCP' }}</strong><small>{{ editionLabel }}</small></div>
        </section>
        <section class="license-metric">
          <div class="license-metric-icon expiry"><el-icon><Tickets /></el-icon></div>
          <div><span>授权有效期</span><strong>{{ formatDate(license.expireTime) }}</strong><small :class="{ 'is-danger': daysRemaining !== null && daysRemaining < 0 }">{{ expiryHint }}</small></div>
        </section>
        <section class="license-metric">
          <div class="license-metric-icon users"><el-icon><User /></el-icon></div>
          <div><span>用户数量上限</span><strong>{{ quotaValue(license.maxUsers) }}</strong><small>可纳入授权的用户总数</small></div>
        </section>
        <section class="license-metric license-metric-highlight">
          <div class="license-metric-icon agents"><el-icon><Share /></el-icon></div>
          <div><span>Agent 发布上限</span><strong>{{ quotaValue(license.maxAgents) }}</strong><small>达到上限后仍可新建，但不可继续发布</small></div>
        </section>
      </div>

      <div class="license-content-grid">
        <section class="license-section license-entitlements">
          <div class="license-section-heading">
            <div><h3>已授权产品模块</h3><p>当前 License 可访问的管理端模块。</p></div>
            <span class="license-count">{{ authorizedMenus.length }} 项</span>
          </div>
          <div v-if="authorizedMenus.length" class="license-chip-grid">
            <div v-for="item in authorizedMenus" :key="item.key" class="license-chip">
              <el-icon><CollectionTag /></el-icon><span>{{ item.label }}</span>
            </div>
          </div>
          <div v-else class="license-empty">当前 License 未配置产品模块</div>
        </section>

        <section class="license-section license-capabilities">
          <div class="license-section-heading">
            <div><h3>已授权能力</h3><p>可供业务调用的产品能力许可。</p></div>
            <span class="license-count">{{ enabledFeatures.length }} 项</span>
          </div>
          <div v-if="enabledFeatures.length" class="license-feature-list">
            <div v-for="item in enabledFeatures" :key="item.key" class="license-feature-item">
              <span class="license-feature-check">✓</span>
              <div><strong>{{ item.label }}</strong><small>{{ item.key }}</small></div>
            </div>
          </div>
          <div v-else class="license-empty">当前 License 未配置能力许可</div>
        </section>
      </div>

      <section class="license-section license-binding">
        <div class="license-section-heading">
          <div><h3>部署绑定信息</h3><p>用于授权申请、续期及部署环境核验，请勿随意变更服务器硬件环境。</p></div>
          <el-tag :type="status.enforcementEnabled ? 'success' : 'info'" effect="plain">
            {{ status.enforcementEnabled ? '授权校验已启用' : '授权校验未启用' }}
          </el-tag>
        </div>

        <div class="license-binding-grid">
          <div class="license-binding-item">
            <div class="license-binding-label"><el-icon><Cpu /></el-icon><span>服务器机器码</span></div>
            <div class="license-code-row">
              <code>{{ status.serverId || '未读取到机器码' }}</code>
              <el-button link type="primary" @click="copyValue(status.serverId, '机器码已复制')">复制</el-button>
            </div>
          </div>
          <div class="license-binding-item">
            <div class="license-binding-label"><el-icon><Connection /></el-icon><span>物理网卡 MAC 地址</span></div>
            <div v-if="(status.macAddresses || []).length" class="license-mac-list">
              <div v-for="mac in status.macAddresses" :key="mac" class="license-code-row">
                <code>{{ mac }}</code>
                <el-button link type="primary" @click="copyValue(mac, 'MAC 地址已复制')">复制</el-button>
              </div>
            </div>
            <div v-else class="license-empty compact">未读取到可用的物理网卡地址</div>
          </div>
        </div>
        <div class="license-binding-tip">
          授权仅识别实际使用的物理网卡；虚拟网卡、容器网卡和隧道适配器不会参与绑定。
        </div>
      </section>
    </el-card>
  </div>
</template>

<script src="../scripts/views/LicenseView.js"></script>
