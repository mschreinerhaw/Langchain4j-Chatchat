import { cacheApi } from '../../services/api';
import { formatBytes } from '../../utils/json';

import '../../styles/views/cache-settings.css';

export default {
  name: 'CacheSettingsView',
  emits: ['notify', 'error'],
  data() {
    return {
      busy: false,
      activeTab: 'templates',
      config: {
        enabled: false,
        defaultTtlSeconds: 300,
        maxRows: 1000,
        maxEntryKb: 512,
        keyStrategy: 'SQL_PARAMS_DATASOURCE',
        cacheEmptyResults: false,
        cacheErrorResults: false
      },
      stats: {},
      templates: [],
      templatePickerOpen: false,
      templatePickerKeyword: '',
      templatePickerCategory: '',
      templatePickerSelected: [],
      redis: {
        enabled: false,
        mode: 'STANDALONE_NO_AUTH',
        nodesText: '127.0.0.1:6379',
        masterName: '',
        databaseIndex: 0,
        username: '',
        password: '',
        passwordConfigured: false,
        sentinelUsername: '',
        sentinelPassword: '',
        sentinelPasswordConfigured: false,
        ssl: false,
        timeoutMillis: 3000,
        maxRedirects: 5,
        available: false
      }
    };
  },
  computed: {
    status() {
      if (!this.config.enabled) {
        return '未启用';
      }
      return this.stats.storeAvailable ? '运行中' : '存储不可用';
    },
    bytes() {
      return formatBytes(this.stats.bytes || 0);
    },
    cachedTemplates() {
      return this.templates.filter(item => item.cacheEnabled);
    },
    templateCategories() {
      const categories = new Map();
      this.templates.forEach(item => {
        const value = String(item.category || 'default').trim() || 'default';
        const label = String(item.categoryName || value).trim() || value;
        categories.set(value, label);
      });
      return [...categories.entries()]
        .map(([value, label]) => ({ value, label }))
        .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
    },
    templatePickerItems() {
      const keyword = this.templatePickerKeyword.trim().toLowerCase();
      return this.templates.filter(item => {
        if (this.templatePickerCategory && String(item.category || 'default') !== this.templatePickerCategory) return false;
        if (!keyword) return true;
        return [item.title, item.toolName, item.category, item.categoryName, item.databaseType, item.datasourceId]
          .some(value => String(value || '').toLowerCase().includes(keyword));
      });
    },
    redisUsesAuthentication() {
      return this.redis.mode !== 'STANDALONE_NO_AUTH';
    },
    redisUsesSentinel() {
      return this.redis.mode === 'SENTINEL';
    },
    redisUsesCluster() {
      return this.redis.mode === 'CLUSTER';
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      this.busy = true;
      try {
        const [config, stats, templates, redis] = await Promise.all([
          cacheApi.getConfig(),
          cacheApi.getStats(),
          cacheApi.listTemplates(),
          cacheApi.getRedisConfig()
        ]);
        this.config = { ...this.config, ...(config || {}) };
        this.stats = stats || {};
        this.templates = (templates || []).map(item => ({ ...item }));
        this.redis = {
          ...this.redis,
          ...(redis || {}),
          nodesText: (redis?.nodes || []).join('\n'),
          password: '',
          sentinelPassword: ''
        };
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    async save() {
      if (this.activeTab === 'storage') {
        await this.saveRedis();
        return;
      }
      if (this.templates.some(item => item.cacheEnabled && (!item.cacheTtlSeconds || item.cacheTtlSeconds < 1))) {
        this.$emit('error', new Error('已启用缓存的 SQL 模板必须填写有效 TTL'));
        return;
      }
      await this.run(async () => {
        await cacheApi.saveConfig(this.config);
        await Promise.all(this.templates.map(item => cacheApi.saveTemplate(item.id, {
          cacheEnabled: item.cacheEnabled,
          cacheTtlSeconds: item.cacheTtlSeconds,
          cacheStorage: item.cacheStorage || 'ROCKSDB'
        })));
      }, '模板缓存策略已保存');
      await this.load();
    },
    openTemplatePicker() {
      this.templatePickerKeyword = '';
      this.templatePickerCategory = '';
      this.templatePickerSelected = this.templates.filter(item => item.cacheEnabled).map(item => item.id);
      this.templatePickerOpen = true;
    },
    toggleTemplatePicker(id) {
      const selected = new Set(this.templatePickerSelected);
      if (selected.has(id)) selected.delete(id);
      else selected.add(id);
      this.templatePickerSelected = [...selected];
    },
    selectVisibleTemplates() {
      const selected = new Set(this.templatePickerSelected);
      this.templatePickerItems.filter(item => item.enabled).forEach(item => selected.add(item.id));
      this.templatePickerSelected = [...selected];
    },
    clearVisibleTemplates() {
      const visible = new Set(this.templatePickerItems.map(item => item.id));
      this.templatePickerSelected = this.templatePickerSelected.filter(id => !visible.has(id));
    },
    confirmTemplatePicker() {
      const selected = new Set(this.templatePickerSelected);
      this.templates.forEach(item => {
        const wasEnabled = item.cacheEnabled;
        item.cacheEnabled = item.enabled && selected.has(item.id);
        if (item.cacheEnabled && !wasEnabled) {
          item.cacheTtlSeconds = item.cacheTtlSeconds > 0 ? item.cacheTtlSeconds : this.config.defaultTtlSeconds;
          item.cacheStorage = item.cacheStorage || 'ROCKSDB';
        }
      });
      this.templatePickerOpen = false;
    },
    redisPayload() {
      return {
        enabled: this.redis.enabled,
        mode: this.redis.mode,
        nodes: String(this.redis.nodesText || '').split(/[,，;\r\n]+/).map(item => item.trim()).filter(Boolean),
        masterName: this.redis.masterName,
        databaseIndex: this.redis.databaseIndex,
        username: this.redis.username,
        password: this.redis.password,
        sentinelUsername: this.redis.sentinelUsername,
        sentinelPassword: this.redis.sentinelPassword,
        ssl: this.redis.ssl,
        timeoutMillis: this.redis.timeoutMillis,
        maxRedirects: this.redis.maxRedirects
      };
    },
    validateRedis() {
      const payload = this.redisPayload();
      if (!payload.nodes.length) throw new Error('请至少填写一个 Redis 节点');
      if (payload.mode === 'SENTINEL' && !String(payload.masterName || '').trim()) {
        throw new Error('哨兵模式必须填写 Master 名称');
      }
      if (payload.mode === 'STANDALONE_AUTH' && !payload.password && !this.redis.passwordConfigured) {
        throw new Error('用户名密码连接模式必须填写 Redis 密码');
      }
      return payload;
    },
    async saveRedis() {
      let payload;
      try {
        payload = this.validateRedis();
      } catch (error) {
        this.$emit('error', error);
        return;
      }
      await this.run(() => cacheApi.saveRedisConfig(payload), 'Redis 缓存存储配置已保存');
      await this.load();
    },
    async testRedis() {
      let payload;
      try {
        payload = this.validateRedis();
      } catch (error) {
        this.$emit('error', error);
        return;
      }
      await this.run(() => cacheApi.testRedisConfig(payload), 'Redis 连接测试成功');
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



