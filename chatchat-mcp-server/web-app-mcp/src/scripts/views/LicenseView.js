import { licenseApi } from '../../services/api';
import '../../styles/views/license.css';

const EDITION_LABELS = {
  community: '社区版', standard: '标准版', professional: '专业版', enterprise: '企业版', trial: '试用版'
};

export default {
  name: 'LicenseView',
  emits: ['notify', 'error'],
  data: () => ({ busy: false, status: {}, catalog: [] }),
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
    licensedCatalog() {
      const licensed = new Set((this.license.modules || []).map(key => String(key).trim().toLowerCase()));
      const allLicensed = licensed.has('mcp');
      return this.catalog.filter(item => allLicensed || licensed.has(String(item.key).trim().toLowerCase()));
    },
    authorizedMenus() { return this.licensedCatalog.filter(item => item.navigation !== false); },
    authorizedCapabilities() { return this.licensedCatalog.filter(item => item.navigation === false); }
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
        const [status, catalog] = await Promise.all([licenseApi.status(), licenseApi.catalog()]);
        this.status = status || {};
        this.catalog = Array.isArray(catalog) ? catalog : [];
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
