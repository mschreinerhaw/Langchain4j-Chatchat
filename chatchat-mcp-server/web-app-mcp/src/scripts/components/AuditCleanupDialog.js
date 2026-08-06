import { ElMessage } from 'element-plus';
import ModalPanel from '../../components/ModalPanel.vue';
import { auditApi } from '../../services/api';

export default {
  name: 'AuditCleanupDialog',
  components: { ModalPanel },
  props: {
    open: { type: Boolean, default: false },
    title: { type: String, default: '清理审计日志' },
    auditCategory: { type: String, default: '' }
  },
  emits: ['close', 'cleared', 'error'],
  data() {
    return {
      timeRange: [],
      validationError: '',
      submitting: false
    };
  },
  watch: {
    open(value) {
      if (value) {
        this.timeRange = [];
        this.validationError = '';
      }
    }
  },
  methods: {
    disableFutureDate(date) {
      return date.getTime() > Date.now();
    },
    close() {
      if (!this.submitting) this.$emit('close');
    },
    async submit() {
      if (!Array.isArray(this.timeRange) || this.timeRange.length !== 2) {
        this.validationError = '请选择完整的开始时间和结束时间';
        return;
      }
      const from = new Date(this.timeRange[0]).getTime();
      const to = new Date(this.timeRange[1]).getTime();
      if (!Number.isFinite(from) || !Number.isFinite(to) || from > to) {
        this.validationError = '时间范围无效，请重新选择';
        return;
      }
      this.submitting = true;
      try {
        const result = await auditApi.cleanup({ from, to, auditCategory: this.auditCategory });
        const deletedCount = Number(result?.deletedCount || 0);
        ElMessage.success(`清理完成，共删除 ${deletedCount} 条审计日志`);
        this.$emit('cleared', result);
        this.$emit('close');
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.submitting = false;
      }
    }
  }
};
