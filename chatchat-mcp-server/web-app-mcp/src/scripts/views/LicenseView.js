import { licenseApi } from '../../services/api';
import '../../styles/views/license.css';

const EDITION_LABELS = {
  community: '社区版', standard: '标准版', professional: '专业版', enterprise: '企业版', trial: '试用版'
};

const FEATURE_LABELS = {
  sql_query: 'SQL 查询', news_collect: '资讯采集', agent_runtime: 'Agent 运行时',
  market_analysis: '市场分析', jmx_monitor: 'JMX 监控', ssh_execute: 'SSH 运维执行'
};

export default {
  name: 'LicenseView',
  emits: ['notify', 'error'],
  data: () => ({ busy: false, status: {}, menus: [] }),
  computed: {
    license() { return this.status.license || {}; },
    statusType() {
      if (this.status.valid) return 'success';
      return this.status.status === 'NOT_INSTALLED' ? 'warning' : 'danger';
    },
    statusTone() {
      if (this.status.valid) return 'success';
      return this.status.status === 'NOT_INSTALLED' ? 'warning' : 'danger';
    },
    statusBadge() {
      if (this.status.valid) return '有效';
      if (this.status.status === 'NOT_INSTALLED') return '未安装';
      if (this.daysRemaining !== null && this.daysRemaining < 0) return '已过期';
      return '不可用';
    },
    statusTitle() {
      if (this.status.valid) return '产品授权正常';
      if (this.status.status === 'NOT_INSTALLED') return '尚未安装 License';
      if (this.daysRemaining !== null && this.daysRemaining < 0) return '产品授权已过期';
      return '产品授权不可用';
    },
    statusDescription() {
      if (this.status.valid) return `LiveMCP ${this.editionLabel}授权已生效，服务可在许可范围内正常运行。`;
      return this.status.message || '请联系授权管理员检查 License 配置。';
    },
    editionLabel() {
      const edition = String(this.license.edition || '').trim();
      return EDITION_LABELS[edition.toLowerCase()] || edition || '版本未配置';
    },
    daysRemaining() {
      if (!this.license.expireTime) return null;
      const expiry = new Date(`${this.license.expireTime}T23:59:59`);
      if (Number.isNaN(expiry.getTime())) return null;
      return Math.ceil((expiry.getTime() - Date.now()) / 86400000);
    },
    expiryHint() {
      if (this.daysRemaining === null) return '未配置到期时间';
      if (this.daysRemaining < 0) return `已过期 ${Math.abs(this.daysRemaining)} 天`;
      if (this.daysRemaining === 0) return '今天到期，请及时续期';
      return `剩余 ${this.daysRemaining} 天`;
    },
    enabledFeatures() {
      return Object.entries(this.license.features || {})
        .filter(([, enabled]) => enabled)
        .map(([key]) => ({ key, label: FEATURE_LABELS[key] || this.humanizeKey(key) }));
    },
    authorizedMenus() { return this.menus.filter(item => item.authorized); }
  },
  mounted() { this.loadStatus(); },
  methods: {
    formatDate(value) {
      if (!value) return '未配置';
      const parts = String(value).slice(0, 10).split('-');
      return parts.length === 3 ? `${parts[0]}年${Number(parts[1])}月${Number(parts[2])}日` : value;
    },
    quotaValue(value) {
      if (value === null || value === undefined) return '不限';
      const amount = Number(value);
      return Number.isFinite(amount) ? amount.toLocaleString('zh-CN') : value;
    },
    humanizeKey(value) {
      return String(value || '').split(/[_-]+/).filter(Boolean)
        .map(part => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');
    },
    async loadStatus() {
      this.busy = true;
      try {
        const [status, menus] = await Promise.all([licenseApi.status(), licenseApi.menus()]);
        this.status = status || {};
        this.menus = Array.isArray(menus) ? menus : [];
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    async copyValue(value, title) {
      const text = String(value || '').trim();
      if (!text) {
        this.$emit('notify', { type: 'warning', title: '暂无可复制内容' });
        return;
      }
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
        } else {
          const input = document.createElement('textarea');
          input.value = text;
          input.setAttribute('readonly', '');
          input.style.position = 'fixed';
          input.style.left = '-9999px';
          input.style.opacity = '0';
          document.body.appendChild(input);
          input.focus();
          input.select();
          input.setSelectionRange(0, input.value.length);
          const copied = document.execCommand('copy');
          document.body.removeChild(input);
          if (!copied) throw new Error('浏览器拒绝访问剪贴板');
        }
        this.$emit('notify', { title, message: text });
      } catch (error) {
        this.$emit('notify', { type: 'danger', title: '复制失败', message: '请手动选择内容复制，或通过 HTTPS 访问管理端。' });
      }
    }
  }
};
