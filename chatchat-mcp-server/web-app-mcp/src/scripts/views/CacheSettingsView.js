import { cacheApi } from '../../services/api';
import { formatBytes } from '../../utils/json';

import '../../styles/views/cache-settings.css';

export default {
  name: 'CacheSettingsView',
  emits: ['notify', 'error'],
  data() {
    return {
      busy: false,
      config: {
        enabled: false,
        defaultTtlSeconds: 300,
        maxRows: 1000,
        maxEntryKb: 512,
        keyStrategy: 'SQL_PARAMS_DATASOURCE',
        cacheEmptyResults: false,
        cacheErrorResults: false
      },
      stats: {}
    };
  },
  computed: {
    status() {
      return this.stats.cacheEnabled && this.stats.storeAvailable ? '运行中' : '未启用';
    },
    bytes() {
      return formatBytes(this.stats.bytes || 0);
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      this.busy = true;
      try {
        const [config, stats] = await Promise.all([cacheApi.getConfig(), cacheApi.getStats()]);
        this.config = { ...this.config, ...(config || {}) };
        this.stats = stats || {};
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    async save() {
      await this.run(() => cacheApi.saveConfig(this.config), '缓存配置已保存');
      await this.load();
    },
    async cleanupExpired() {
      if (!window.confirm('确定清理已过期的数据库查询缓存吗？')) return;
      await this.run(cacheApi.cleanupExpired, '过期缓存已清理');
      await this.load();
    },
    async evictAll() {
      if (!window.confirm('确定清理全部数据库查询缓存吗？')) return;
      await this.run(cacheApi.evictAll, '全部缓存已清理');
      await this.load();
    },
    async run(action, title) {
      this.busy = true;
      try {
        await action();
        this.$emit('notify', { title });
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    }
  }
};



