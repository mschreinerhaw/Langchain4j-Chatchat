<template>
  <section class="workspace-panel">
    <header class="panel-heading">
      <div>
        <h2>通知告警</h2>
        <p>仅支持邮件、短信、企业微信、钉钉四类告警渠道；新增告警统一按 HTTP/Webhook 方式配置。</p>
      </div>
      <div class="panel-actions">
        <el-input
          v-model.trim="searchKeyword"
          class="search-input"
          clearable
          placeholder="搜索渠道、工具名称或描述"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button plain :loading="busyAction === 'refresh'" @click="refreshTools">
          <el-icon><Refresh /></el-icon>
          <span>刷新 MCP 工具</span>
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新增告警</span>
        </el-button>
      </div>
    </header>

    <div class="bulk-row">
      <span>共 {{ channels.length }} 个渠道，匹配 {{ filteredChannels.length }} 个</span>
    </div>

    <div v-loading="busy" class="notification-grid">
      <article
        v-for="channel in filteredChannels"
        :key="channel.id || channel.toolName"
        class="notification-card"
        :class="{ active: channel.id === selectedNotificationChannelId }"
      >
        <div class="notification-card-main">
          <h3>{{ channel.title || channel.toolName }}</h3>
          <p>{{ channel.description || '-' }}</p>
        </div>
        <div class="notification-meta">
          <el-tag type="primary" effect="light">{{ channel.channel }}</el-tag>
          <el-tag :type="channel.enabled ? 'success' : 'info'" effect="light">
            {{ channel.enabled ? '启用' : '下线' }}
          </el-tag>
          <el-tag :type="channel.runtimeAction === 'forbidden' ? 'danger' : 'warning'" effect="light">
            {{ channel.runtimeAction || 'confirm_required' }}
          </el-tag>
          <el-tag effect="plain">{{ channel.deliveryMode || 'HTTP' }}</el-tag>
          <el-tag effect="plain">{{ channel.toolName }}</el-tag>
        </div>
        <div class="notification-endpoint">{{ channelEndpoint(channel) }}</div>
        <div class="notification-actions">
          <el-button size="small" type="primary" plain @click="openTest(channel)">测试</el-button>
          <el-button size="small" plain @click="openEdit(channel)">编辑</el-button>
          <el-button size="small" plain @click="toggleEnabled(channel)">
            {{ channel.enabled ? '下线' : '启用' }}
          </el-button>
          <el-button
            size="small"
            :type="channel.runtimeAction === 'forbidden' ? 'success' : 'danger'"
            plain
            @click="togglePolicy(channel)"
          >
            {{ channel.runtimeAction === 'forbidden' ? '恢复确认' : '禁用调用' }}
          </el-button>
        </div>
      </article>

      <el-empty v-if="!busy && filteredChannels.length === 0" description="暂无匹配的通知渠道" />
    </div>

    <ModalPanel :open="formOpen" :title="formTitle" subtitle="配置通知 MCP 工具、投递参数和测试消息。" wide @close="closeForm">
      <el-form class="entity-form" label-position="top">
        <section class="notification-type-guide">
          <div>
            <strong>{{ channelGuide.title }}</strong>
            <span>{{ channelGuide.mode }}</span>
          </div>
          <p>{{ channelGuide.description }}</p>
          <div class="notification-param-tags">
            <el-tag v-for="param in channelGuide.params" :key="param" effect="light">{{ param }}</el-tag>
          </div>
        </section>
        <div class="notification-form-shell">
          <nav class="notification-section-nav" aria-label="告警配置分类">
            <button
              type="button"
              :class="{ active: notificationSectionOpen.basic }"
              @click="showNotificationSection('basic')"
            >
              基础信息
            </button>
            <button
              type="button"
              :class="{ active: notificationSectionOpen.policy }"
              @click="showNotificationSection('policy')"
            >
              发布策略
            </button>
            <button
              v-if="isHttpMode"
              type="button"
              :class="{ active: notificationSectionOpen.http }"
              @click="showNotificationSection('http')"
            >
              Webhook 参数
            </button>
            <button
              v-if="form.channel === 'SMS'"
              type="button"
              :class="{ active: notificationSectionOpen.sms }"
              @click="showNotificationSection('sms')"
            >
              短信参数
            </button>
            <button
              v-if="form.deliveryMode === 'SMTP'"
              type="button"
              :class="{ active: notificationSectionOpen.smtp }"
              @click="showNotificationSection('smtp')"
            >
              SMTP 参数
            </button>
            <button
              type="button"
              :class="{ active: notificationSectionOpen.test }"
              @click="showNotificationSection('test')"
            >
              测试消息
            </button>
          </nav>
          <div class="notification-form-content">
        <el-row :gutter="14">
          <el-col v-show="notificationSectionOpen.basic" :xs="24">
            <div
              class="notification-form-heading"
              :class="{ collapsed: !notificationSectionOpen.basic }"
              role="button"
              @click="toggleNotificationSection('basic')"
            >
              <strong>基础信息</strong>
              <span>定义 MCP 通知工具的类型、名称和展示描述。</span>
            </div>
          </el-col>
          <el-col v-show="notificationSectionOpen.basic" :xs="24" :md="8">
            <el-form-item label="通知类型" required>
              <el-select v-model="form.channel" class="w-100" :disabled="!isNew" @change="channelChanged">
                <el-option
                  v-for="option in channelOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.basic" :xs="24" :md="8">
            <el-form-item label="工具名称" required>
              <el-input v-model.trim="form.toolName" placeholder="notify_dingtalk_xxx" />
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.basic" :xs="24" :md="8">
            <el-form-item label="显示名称" required>
              <el-input v-model.trim="form.title" placeholder="HTTP 告警" />
            </el-form-item>
          </el-col>

          <el-col v-show="notificationSectionOpen.policy" :xs="24">
            <div
              class="notification-form-heading"
              :class="{ collapsed: !notificationSectionOpen.policy }"
              role="button"
              @click="toggleNotificationSection('policy')"
            >
              <strong>发布与调用策略</strong>
              <span>控制工具是否发布、调用前是否确认，以及失败重试和超时。</span>
            </div>
          </el-col>

          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="6">
            <el-form-item label="发布状态">
              <el-select v-model="form.enabled" class="w-100">
                <el-option label="启用" :value="true" />
                <el-option label="下线" :value="false" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="6">
            <el-form-item label="运行策略">
              <el-select v-model="form.runtimeAction" class="w-100">
                <el-option
                  v-for="option in runtimeActionOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="6">
            <el-form-item label="发送模式">
              <el-select v-model="form.deliveryMode" class="w-100" :disabled="isNew">
                <el-option
                  v-for="option in deliveryModeOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <div class="field-hint">新增告警固定使用 HTTP / Webhook。</div>
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="3">
            <el-form-item label="HTTP 方法">
              <el-select v-model="form.method" class="w-100">
                <el-option v-for="method in methodOptions" :key="method" :label="method" :value="method" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="3">
            <el-form-item label="失败重试次数">
              <el-input-number v-model="form.maxRetries" class="w-100" :min="0" :max="5" controls-position="right" />
            </el-form-item>
          </el-col>

          <el-col v-show="notificationSectionOpen.policy" :xs="24" :md="6">
            <el-form-item label="超时毫秒">
              <el-input-number v-model="form.timeoutMs" class="w-100" :min="1000" :step="1000" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col v-show="notificationSectionOpen.policy" :xs="24">
            <el-form-item label="工具描述">
              <el-input v-model="form.description" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>

          <el-col v-if="isHttpMode" v-show="notificationSectionOpen.http" :xs="24">
            <div
              class="notification-form-heading"
              :class="{ collapsed: !notificationSectionOpen.http }"
              role="button"
              @click="toggleNotificationSection('http')"
            >
              <strong>HTTP / Webhook 投递参数</strong>
              <span>配置目标地址、请求头、密钥和消息体模板。</span>
            </div>
          </el-col>

          <template v-if="isHttpMode">
            <el-col v-show="notificationSectionOpen.http" :xs="24">
              <el-form-item label="Endpoint / Webhook URL" required>
                <el-input v-model.trim="form.endpointUrl" placeholder="https://example.com/webhook?token={{channelSecret}}" />
              </el-form-item>
            </el-col>
            <el-col v-show="notificationSectionOpen.http" :xs="24" :lg="12">
              <el-form-item label="请求头 JSON">
                <el-input v-model="headersJson" class="codebox" type="textarea" :rows="6" spellcheck="false" />
              </el-form-item>
            </el-col>
            <el-col v-show="notificationSectionOpen.http" :xs="24" :lg="12">
              <el-form-item label="渠道密钥">
                <el-input
                  v-model="form.secret"
                  type="password"
                  autocomplete="new-password"
                  show-password
                  placeholder="可在 URL/Header/Body 中用 {{channelSecret}}"
                />
              </el-form-item>
            </el-col>
            <el-col v-show="notificationSectionOpen.http" :xs="24">
              <el-form-item label="告警内容 / 请求体模板" required>
                <el-input
                  v-model="form.bodyTemplate"
                  class="codebox"
                  type="textarea"
                  :rows="7"
                  spellcheck="false"
                  placeholder="{&quot;title&quot;:&quot;{{title}}&quot;,&quot;content&quot;:&quot;{{content}}&quot;,&quot;level&quot;:&quot;{{level}}&quot;}"
                />
                <div class="template-helper-row">
                  <el-button size="small" text type="primary" @click="variableHelpOpen = true">查看可用变量</el-button>
                  <span class="legacy-variable-text" hidden>
                  <el-popover placement="top-start" trigger="click" width="520">
                    <template #reference>
                      <el-button size="small" text type="primary">查看可用变量</el-button>
                    </template>
                    <div class="template-variable-help">
                      <strong>请求体模板变量</strong>
                      <el-table :data="templateVariableRows" size="small" border>
                        <el-table-column prop="name" label="参数名称" width="170" />
                        <el-table-column prop="usage" label="使用说明" />
                      </el-table>
                    </div>
                  </el-popover>
                  </span>
                  可用变量：{{ variableText }}
                </div>
              </el-form-item>
            </el-col>
          </template>

          <el-col v-if="form.channel === 'SMS'" v-show="notificationSectionOpen.sms" :xs="24">
            <section class="form-subsection" :class="{ collapsed: !notificationSectionOpen.sms }">
              <button class="subsection-toggle" type="button" @click="toggleNotificationSection('sms')">
                <strong>短信接收配置</strong>
                <span>{{ notificationSectionOpen.sms ? '收起' : '展开' }}</span>
              </button>
              <h3>短信接收配置</h3>
              <el-row v-show="notificationSectionOpen.sms" :gutter="14">
                <el-col :xs="24">
                  <el-form-item label="手机号接收人">
                    <el-input v-model.trim="form.defaultReceiver" placeholder="13800000000，多个手机号用逗号分隔" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="短信网关账号">
                    <el-input v-model.trim="form.smsAccount" placeholder="xzxz" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="返回类型">
                    <el-input v-model.trim="form.smsReturnType" placeholder="text" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24">
                  <el-form-item label="短信接口 Token">
                    <el-input v-model="form.smsToken" type="password" autocomplete="new-password" show-password placeholder="留空则不修改" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24">
                  <el-checkbox v-model="form.smsPasswordMd5">密码已是 MD5 密文</el-checkbox>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="短信网关明文密码">
                    <el-input v-model="form.smsPlainPassword" type="password" autocomplete="new-password" show-password placeholder="留空则不修改" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="短信网关 MD5 密码">
                    <el-input v-model.trim="form.smsMd5Password" placeholder="751CB3F4AA17C36186F4856C8982BF27" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24">
                  <el-form-item label="短信扩展码">
                    <el-input v-model.trim="form.smsExtendCode" placeholder="不需要时留空" />
                  </el-form-item>
                </el-col>
              </el-row>
            </section>
          </el-col>

          <el-col v-if="form.deliveryMode === 'SMTP'" v-show="notificationSectionOpen.smtp" :xs="24">
            <section class="form-subsection" :class="{ collapsed: !notificationSectionOpen.smtp }">
              <button class="subsection-toggle" type="button" @click="toggleNotificationSection('smtp')">
                <strong>SMTP 邮件参数</strong>
                <span>{{ notificationSectionOpen.smtp ? '收起' : '展开' }}</span>
              </button>
              <h3>SMTP 邮件参数</h3>
              <el-row v-show="notificationSectionOpen.smtp" :gutter="14">
                <el-col :xs="24" :md="12">
                  <el-form-item label="收件人">
                    <el-input v-model.trim="form.defaultReceiver" placeholder="ops@example.com，多个邮箱用逗号分隔" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="抄送人">
                    <el-input v-model.trim="form.ccReceiver" placeholder="cc@example.com，多个邮箱用逗号分隔" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-form-item label="SMTP Host">
                    <el-input v-model.trim="form.smtpHost" placeholder="smtp.example.com" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="4">
                  <el-form-item label="端口">
                    <el-input-number v-model="form.smtpPort" class="w-100" :min="1" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="6">
                  <el-form-item label="用户名">
                    <el-input v-model.trim="form.smtpUsername" autocomplete="off" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="6">
                  <el-form-item label="密码">
                    <el-input v-model="form.smtpPassword" type="password" autocomplete="new-password" show-password />
                  </el-form-item>
                </el-col>
                <el-col :xs="24">
                  <el-form-item label="发件人">
                    <el-input v-model.trim="form.smtpFrom" placeholder="alerts@example.com" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-checkbox v-model="form.smtpAuthEnabled">请求认证</el-checkbox>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-checkbox v-model="form.smtpStarttlsEnabled">STARTTLS 连接</el-checkbox>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-checkbox v-model="form.smtpSslEnabled">SSL 连接</el-checkbox>
                </el-col>
                <el-col :xs="24">
                  <el-form-item label="SSL 证书信任">
                    <el-input v-model.trim="form.smtpSslTrust" placeholder="smtp.example.com 或 *" />
                  </el-form-item>
                </el-col>
              </el-row>
            </section>
          </el-col>

          <el-col v-show="notificationSectionOpen.test" :xs="24">
            <div
              class="notification-form-heading"
              :class="{ collapsed: !notificationSectionOpen.test }"
              role="button"
              @click="toggleNotificationSection('test')"
            >
              <strong>测试消息</strong>
              <span>用于验证当前通知工具能否成功发送。</span>
            </div>
          </el-col>

          <el-col v-show="notificationSectionOpen.test" :xs="24">
            <el-form-item label="测试消息 JSON">
              <el-input v-model="testPayloadJson" class="codebox" type="textarea" :rows="6" spellcheck="false" />
            </el-form-item>
          </el-col>
        </el-row>
          </div>
        </div>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button plain :disabled="!form.id" :loading="busyAction === 'test'" @click="testForm">测试发送</el-button>
          <el-button type="primary" :loading="busyAction === 'save'" @click="save">保存配置</el-button>
        </div>
      </template>
    </ModalPanel>

    <ModalPanel :open="variableHelpOpen" title="请求体模板变量" @close="variableHelpOpen = false">
      <div class="template-variable-help">
        <el-table :data="templateVariableRows" size="small" border>
          <el-table-column prop="name" label="参数名称" width="170" />
          <el-table-column prop="usage" label="使用说明" />
        </el-table>
      </div>
      <template #footer>
        <el-button type="primary" @click="variableHelpOpen = false">知道了</el-button>
      </template>
    </ModalPanel>
  </section>
</template>

<script src="../scripts/views/NotificationChannelsView.js"></script>
