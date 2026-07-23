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
    copyValue(value, title) {
      navigator.clipboard?.writeText(value || '');
      this.$emit('notify', { title });
    }
  }
};
