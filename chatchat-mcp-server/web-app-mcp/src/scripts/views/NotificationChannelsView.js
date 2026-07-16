import ModalPanel from '../../components/ModalPanel.vue';
import { notificationApi as api } from '../../services/api';
import { parseJsonObject, prettyJson } from '../../utils/json';
import { buildTestNotification } from '../../utils/test-result';
import '../../styles/views/notification-channels.css';

const defaultHeaders = { 'Content-Type': 'application/json; charset=UTF-8' };

const defaultTestPayload = {
  receiver: 'ops@example.com',
  title: 'Agent 任务通知',
  content: '这是一条测试通知。',
  level: 'INFO',
  sourceTaskId: 'manual-test'
};

const emptyForm = {
  id: '',
  channel: 'DINGTALK',
  toolName: '',
  title: '',
  description: '',
  enabled: false,
  runtimeAction: 'confirm_required',
  deliveryMode: 'HTTP',
  method: 'POST',
  endpointUrl: '',
  headers: defaultHeaders,
  bodyTemplate: '',
  secret: '',
  defaultReceiver: 'ops@example.com',
  ccReceiver: '',
  smtpHost: '',
  smtpPort: null,
  smtpUsername: '',
  smtpPassword: '',
  smtpFrom: '',
  smtpAuthEnabled: true,
  smtpStarttlsEnabled: true,
  smtpSslEnabled: false,
  smtpSslTrust: '',
  smsAccount: '',
  smsToken: '',
  smsPlainPassword: '',
  smsMd5Password: '',
  smsPasswordMd5: true,
  smsReturnType: 'text',
  smsExtendCode: '',
  timeoutMs: 10000,
  maxRetries: 1,
  defaultTestPayload
};

export default {
  name: 'NotificationChannelsView',
  components: { ModalPanel },
  emits: ['notify', 'error', 'result'],
  data() {
    return {
      busy: false,
      busyAction: '',
      channels: [],
      selectedNotificationChannelId: '',
      searchKeyword: '',
      formOpen: false,
      variableHelpOpen: false,
      notificationSectionOpen: {
        basic: true,
        policy: false,
        http: true,
        sms: true,
        smtp: true,
        test: false
      },
      form: clone(emptyForm),
      headersJson: prettyJson(defaultHeaders),
      testPayloadJson: prettyJson(defaultTestPayload),
      channelOptions: [
        { value: 'EMAIL', label: 'EMAIL' },
        { value: 'SMS', label: 'SMS' },
        { value: 'WECHAT_WORK', label: 'WECHAT_WORK' },
        { value: 'DINGTALK', label: 'DINGTALK' }
      ],
      runtimeActionOptions: [
        { value: 'confirm_required', label: 'confirm_required' },
        { value: 'forbidden', label: 'forbidden' }
      ],
      deliveryModeOptions: [
        { value: 'HTTP', label: 'HTTP / Webhook' },
        { value: 'SMTP', label: 'SMTP 邮件' }
      ],
      methodOptions: ['POST', 'PUT', 'PATCH'],
      variableText: ''
    };
  },
  computed: {
    isNew() {
      return !this.form.id;
    },
    isHttpMode() {
      return (this.form.deliveryMode || 'HTTP') === 'HTTP';
    },
    formTitle() {
      return this.isNew ? '新增 HTTP 告警' : `配置 ${this.form.toolName || this.form.channel}`;
    },
    templateVariableRows() {
      return [
        { name: '{{receiver}}', usage: '接收人。邮件为邮箱地址，短信为手机号，Webhook 可按平台要求放入接收人字段。' },
        { name: '{{title}}', usage: '告警标题，适合放在消息标题或 subject 中。' },
        { name: '{{content}}', usage: '告警正文，包含主要通知内容。' },
        { name: '{{level}}', usage: '告警级别，例如 INFO、WARN、ERROR。' },
        { name: '{{sourceTaskId}}', usage: '来源任务 ID，用于关联审计、任务或调用链。' },
        { name: '{{channelSecret}}', usage: '渠道密钥，可放在 URL、Header 或 Body 中，发送时自动替换。' },
        { name: '{{smsAccount}}', usage: '短信网关账号，仅短信渠道常用。' },
        { name: '{{smsToken}}', usage: '短信接口 Token，仅短信渠道常用。' },
        { name: '{{smsPassword}}', usage: '短信网关密码，按配置自动使用明文或 MD5。' },
        { name: '{{smsPlainPassword}}', usage: '短信网关明文密码。' },
        { name: '{{smsMd5Password}}', usage: '短信网关 MD5 密码。' },
        { name: '{{smsReturnType}}', usage: '短信接口返回类型，例如 text。' },
        { name: '{{smsExtendCode}}', usage: '短信扩展码，不需要时可留空。' }
      ];
    },
    channelGuide() {
      const guides = {
        EMAIL: {
          title: '邮件告警',
          mode: 'SMTP',
          description: '适合发送详细分析结果、日报和需要留档的告警。',
          params: ['收件人', '抄送人', 'SMTP Host', '端口', '用户名', '密码', '发件人']
        },
        SMS: {
          title: '短信告警',
          mode: 'HTTP/Webhook',
          description: '适合发送高优先级、短文本告警，接收人填写手机号。',
          params: ['手机号接收人', '短信网关账号', 'Token', '密码/MD5 密码', '返回类型', '扩展码']
        },
        WECHAT_WORK: {
          title: '企业微信告警',
          mode: 'HTTP/Webhook',
          description: '适合团队群机器人或企业微信消息接口通知。',
          params: ['Webhook URL', '请求头', '渠道密钥', '消息体模板', '测试消息']
        },
        DINGTALK: {
          title: '钉钉告警',
          mode: 'HTTP/Webhook',
          description: '适合钉钉机器人或钉钉消息接口通知。',
          params: ['Webhook URL', '请求头', '渠道密钥', '消息体模板', '测试消息']
        }
      };
      return guides[this.form.channel] || guides.DINGTALK;
    },
    filteredChannels() {
      const keyword = this.searchKeyword.trim().toLowerCase();
      if (!keyword) return this.channels;
      return this.channels.filter(channel => [
        channel.channel,
        channel.toolName,
        channel.title,
        channel.description,
        channel.endpointUrl,
        channel.smtpHost,
        channel.smtpFrom
      ].some(value => String(value || '').toLowerCase().includes(keyword)));
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      this.busy = true;
      try {
        const result = await api.list();
        this.channels = Array.isArray(result) ? result : [];
        if (this.selectedNotificationChannelId && !this.channels.some(item => item.id === this.selectedNotificationChannelId)) {
          this.selectedNotificationChannelId = '';
        }
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    openCreate() {
      this.selectedNotificationChannelId = '';
      this.fillForm(newNotificationDraft());
      this.resetNotificationSections();
      this.formOpen = true;
    },
    openEdit(channel) {
      this.selectedNotificationChannelId = channel.id;
      this.fillForm(channel);
      this.resetNotificationSections();
      this.formOpen = true;
    },
    openTest(channel) {
      this.openEdit(channel);
    },
    closeForm() {
      this.formOpen = false;
    },
    fillForm(channel) {
      const normalized = normalizeChannel(channel);
      this.form = normalized;
      this.headersJson = prettyJson(normalized.headers || defaultHeaders);
      this.testPayloadJson = prettyJson(normalized.defaultTestPayload || defaultTestPayload);
    },
    channelChanged() {
      const defaults = notificationDefaultsFor(this.form.channel);
      if (this.isNew && (!this.form.toolName || this.form.toolName.startsWith('notify_'))) {
        this.form.toolName = defaults.toolName;
      }
      if (!this.form.title || this.form.title === '通知工具') {
        this.form.title = defaults.title;
      }
      if (!this.form.description) {
        this.form.description = defaults.description;
      }
      if (this.isNew) {
        this.form.deliveryMode = 'HTTP';
      } else if (!this.form.deliveryMode) {
        this.form.deliveryMode = defaults.deliveryMode;
      }
      if (this.form.channel === 'SMS' && !this.form.defaultReceiver) {
        this.form.defaultReceiver = '13800000000';
      }
      this.resetNotificationSections();
    },
    toggleNotificationSection(section) {
      this.showNotificationSection(section);
    },
    showNotificationSection(section) {
      this.notificationSectionOpen = {
        basic: false,
        policy: false,
        http: false,
        sms: false,
        smtp: false,
        test: false,
        [section]: true
      };
    },
    resetNotificationSections() {
      this.notificationSectionOpen = {
        basic: true,
        policy: false,
        http: false,
        sms: false,
        smtp: false,
        test: false
      };
    },
    async refreshTools() {
      this.busyAction = 'refresh';
      try {
        const result = await api.refresh();
        this.$emit('notify', {
          title: '刷新完成',
          message: result?.refreshed ? '通知 MCP 工具已重新发布。' : '通知 MCP 工具刷新完成。'
        });
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async toggleEnabled(channel) {
      this.busyAction = `enabled-${channel.id}`;
      try {
        await api.setEnabled(channel.id, !channel.enabled);
        this.$emit('notify', {
          title: '更新成功',
          message: `${channel.toolName} 已${channel.enabled ? '下线' : '启用'}。`
        });
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async togglePolicy(channel) {
      const nextAction = channel.runtimeAction === 'forbidden' ? 'confirm_required' : 'forbidden';
      this.busyAction = `policy-${channel.id}`;
      try {
        await api.setRuntimeAction(channel.id, nextAction);
        this.$emit('notify', {
          title: '策略已更新',
          message: `${channel.toolName} 已切换为 ${nextAction}。`
        });
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async save() {
      let payload;
      try {
        payload = this.readFormPayload();
      } catch (error) {
        this.$emit('error', error);
        return;
      }
      if (!payload.toolName || !payload.title) {
        this.$emit('notify', { type: 'warning', title: '请补全配置', message: '工具名称和显示名称不能为空。' });
        return;
      }
      if (payload.deliveryMode === 'HTTP' && !payload.endpointUrl?.trim()) {
        this.$emit('notify', { type: 'warning', title: '请填写 Webhook URL', message: 'HTTP 告警必须配置 Endpoint / Webhook URL。' });
        return;
      }
      if (payload.deliveryMode === 'HTTP' && !payload.bodyTemplate?.trim()) {
        this.$emit('notify', { type: 'warning', title: '请填写告警内容', message: 'HTTP 告警必须填写请求体模板。' });
        return;
      }
      this.busyAction = 'save';
      try {
        const saved = await api.save(payload);
        this.selectedNotificationChannelId = saved.id;
        this.$emit('notify', { title: '保存成功', message: `${saved.toolName} 配置已生效。` });
        await this.load();
        this.fillForm(saved);
        this.formOpen = false;
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async testForm() {
      if (!this.form.id) {
        this.$emit('notify', { type: 'warning', title: '无法测试', message: '请先保存通知工具。' });
        return;
      }
      let payload;
      try {
        payload = parseJsonObject(this.testPayloadJson, {});
      } catch (error) {
        this.$emit('error', new Error(`测试消息 JSON 格式不正确：${error.message}`));
        return;
      }
      this.busyAction = 'test';
      try {
        const result = await api.test(this.form.id, payload);
        this.$emit('notify', buildTestNotification(result, {
          successTitle: '通知测试发送成功',
          failureTitle: '通知测试发送失败'
        }));
        this.$emit('result', {
          title: `${this.form.toolName} 测试结果`,
          value: result
        });
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    readFormPayload() {
      const payload = normalizeChannel(this.form);
      if (!payload.id) {
        payload.deliveryMode = 'HTTP';
      }
      payload.headers = parseJsonObject(this.headersJson, {});
      payload.timeoutMs = Number(payload.timeoutMs || 5000);
      payload.maxRetries = Number(payload.maxRetries || 0);
      payload.smtpPort = payload.smtpPort ? Number(payload.smtpPort) : null;
      delete payload.defaultTestPayload;
      delete payload.createdAt;
      delete payload.updatedAt;
      return payload;
    },
    channelEndpoint(channel) {
      if (channel.deliveryMode === 'SMTP') {
        return channel.smtpFrom || channel.smtpHost || 'SMTP 未配置';
      }
      return channel.endpointUrl || 'Webhook 未配置';
    }
  }
};

function newNotificationDraft() {
  const defaults = notificationDefaultsFor('DINGTALK');
  return {
    ...clone(emptyForm),
    channel: 'DINGTALK',
    toolName: defaults.toolName,
    title: 'HTTP 告警',
    description: '通过 HTTP/Webhook 发送用户自定义告警内容。',
    enabled: false,
    deliveryMode: 'HTTP',
    defaultReceiver: 'ops@example.com',
    defaultTestPayload: clone(defaultTestPayload)
  };
}

function normalizeChannel(channel = {}) {
  return {
    ...clone(emptyForm),
    ...clone(channel),
    channel: channel.channel || 'DINGTALK',
    enabled: channel.enabled ?? true,
    runtimeAction: channel.runtimeAction || 'confirm_required',
    deliveryMode: channel.deliveryMode || 'HTTP',
    method: channel.method || 'POST',
    headers: channel.headers && Object.keys(channel.headers).length ? channel.headers : clone(defaultHeaders),
    smtpAuthEnabled: channel.smtpAuthEnabled ?? true,
    smtpStarttlsEnabled: channel.smtpStarttlsEnabled ?? true,
    smtpSslEnabled: channel.smtpSslEnabled ?? false,
    smsPasswordMd5: channel.smsPasswordMd5 ?? true,
    smsReturnType: channel.smsReturnType || 'text',
    timeoutMs: Number(channel.timeoutMs || 10000),
    maxRetries: Number(channel.maxRetries ?? 1),
    defaultTestPayload: channel.defaultTestPayload || clone(defaultTestPayload)
  };
}

function notificationDefaultsFor(channel) {
  const suffix = Date.now().toString(36);
  if (channel === 'SMS') {
    return {
      toolName: `notify_sms_${suffix}`,
      title: '短信通知工具',
      description: '通过短信网关发送 Agent 分析结果或告警通知。',
      deliveryMode: 'HTTP'
    };
  }
  if (channel === 'WECHAT_WORK') {
    return {
      toolName: `notify_wechat_work_${suffix}`,
      title: '企业微信通知工具',
      description: '通过企业微信机器人或消息接口发送 Agent 告警通知。',
      deliveryMode: 'HTTP'
    };
  }
  if (channel === 'DINGTALK') {
    return {
      toolName: `notify_dingtalk_${suffix}`,
      title: '钉钉通知工具',
      description: '通过钉钉机器人或消息接口发送 Agent 告警通知。',
      deliveryMode: 'HTTP'
    };
  }
  return {
    toolName: `notify_email_${suffix}`,
    title: '邮件通知工具',
    description: '向指定邮箱发送 Agent 分析结果或告警通知。',
    deliveryMode: 'SMTP'
  };
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}
