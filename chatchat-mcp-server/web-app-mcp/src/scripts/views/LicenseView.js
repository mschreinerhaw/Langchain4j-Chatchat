import { licenseApi } from '../../services/api';
import '../../styles/views/license.css';

export default {
  name: 'LicenseView',
  emits: ['notify', 'error'],
  data: () => ({ busy: false, status: {} }),
  computed: {
    license() { return this.status.license || {}; },
    statusType() { return this.status.valid ? 'success' : (this.status.status === 'NOT_INSTALLED' ? 'warning' : 'danger'); },
    enabledFeatures() {
      return Object.entries(this.license.features || {}).filter(([, enabled]) => enabled).map(([name]) => name);
    }
  },
  mounted() { this.loadStatus(); },
  methods: {
    async loadStatus() {
      this.busy = true;
      try { this.status = await licenseApi.status() || {}; }
      catch (error) { this.$emit('error', error); }
      finally { this.busy = false; }
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
        this.$emit('notify', {
          type: 'danger',
          title: '复制失败',
          message: '请手动选择内容复制，或通过 HTTPS 访问管理端。'
        });
      }
    }
  }
};
