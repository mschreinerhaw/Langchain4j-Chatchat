import { cacheApi } from '../../services/api';
import { formatBytes } from '../../utils/json';

import { buildTestNotification } from '../../utils/test-result';
import '../../styles/views/cache-settings.css';

export default {
  name: 'CacheSettingsView',
  emits: ['notify', 'error'],
  data() {
    return {
      busy: false,
      activeTab: 'templates',
      config: {
        enabled: true,
        defaultTtlSeconds: 300,
        maxRows: 1000,
        maxEntryKb: 512,
        keyStrategy: 'TEMPLATE_ID_PARAMS_DATASOURCE',
        cacheEmptyResults: false,
        cacheErrorResults: false
      },
      stats: {},
      databaseOverview: { items: [], entries: 0, expiredEntries: 0, hitCount: 0, bytes: 0, bypassNoTenantCount: 0 },
      financialConfig: {
        enabled: true,
        storage: 'ROCKSDB',
        ttlSeconds: 1800,
        fallbackToRocksDb: true,
        maxEntryKb: 2048,
        singleFlightGraceMs: 500
      },
      financialOverview: {
        items: [], entries: 0, expiredEntries: 0, hitCount: 0, bytes: 0,
        selectedStorageAvailable: true, bypassNoTenantCount: 0, writeFailureCount: 0,
        oversizedSkipCount: 0, lastWriteFailure: ''
      },
      templates: [],
      templatePickerOpen: false,
      templatePickerKeyword: '',
      templatePickerCategory: '',
      templatePickerSelected: [],
      templateSearchResultIds: null,
      templateSearchBusy: false,
      templateSearchTimer: null,
      templateSearchSequence: 0,
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
    cacheModuleEnabled: {
      get() {
        return this.config.enabled && this.financialConfig.enabled;
      },
      set(enabled) {
        this.config.enabled = enabled;
        this.financialConfig.enabled = enabled;
      }
    },
    status() {
      if (!this.config.enabled && !this.financialConfig.enabled) return '未启用';
      const databaseUnavailable = this.config.enabled && !this.stats.storeAvailable;
      const financialUnavailable = this.financialConfig.enabled
        && this.financialOverview.selectedStorageAvailable === false;
      return databaseUnavailable || financialUnavailable ? '部分存储不可用' : '运行中';
    },
    bytes() {
      return formatBytes(Number(this.stats.bytes || 0) + Number(this.financialOverview.bytes || 0));
    },
    unifiedStats() {
      return {
        entries: Number(this.stats.entries || 0) + Number(this.financialOverview.entries || 0),
        expiredEntries: Number(this.stats.expiredEntries || 0) + Number(this.financialOverview.expiredEntries || 0),
        hitCount: Number(this.stats.hitCount || 0) + Number(this.financialOverview.hitCount || 0)
      };
    },
    databaseCacheWarning() {
      if (!this.config.enabled || !(this.stats.bypassNoTenantCount > 0)) return '';
      return `有 ${this.stats.bypassNoTenantCount} 次能力中心查询因缺少租户上下文而未写入缓存；已安全绕过，未使用共享租户缓存。`;
    },
    financialCacheWarning() {
      if (!this.financialConfig.enabled) return '';
      if (this.financialOverview.selectedStorageAvailable === false) {
        return `当前选择的 ${this.financialConfig.storage} 缓存存储不可用，查询结果不会写入缓存。`;
      }
      const reasons = [];
      if (this.financialOverview.bypassNoTenantCount > 0) {
        reasons.push(`缺少租户上下文而绕过 ${this.financialOverview.bypassNoTenantCount} 次`);
      }
      if (this.financialOverview.writeFailureCount > 0) {
        reasons.push(`写入失败 ${this.financialOverview.writeFailureCount} 次`);
      }
      if (this.financialOverview.oversizedSkipCount > 0) {
        reasons.push(`结果超出大小限制 ${this.financialOverview.oversizedSkipCount} 次`);
      }
      const detail = this.financialOverview.lastWriteFailure ? `；最近原因：${this.financialOverview.lastWriteFailure}` : '';
      return reasons.length ? `存在未缓存查询：${reasons.join('，')}${detail}` : '';
    },
    cacheWarnings() {
      return [this.databaseCacheWarning, this.financialCacheWarning].filter(Boolean);
    },
    cachedTemplates() {
      return this.templates.filter(item => item.cacheEnabled);
    },
    unifiedCacheRows() {
      const entriesByTemplate = new Map();
      (this.databaseOverview.items || []).forEach(item => {
        const templateId = item.templateId || 'legacy-entry';
        if (!entriesByTemplate.has(templateId)) entriesByTemplate.set(templateId, []);
        entriesByTemplate.get(templateId).push(item);
      });
      const configuredIds = new Set(this.cachedTemplates.map(item => item.id));
      const rows = this.cachedTemplates.map(template => {
        const cacheItems = entriesByTemplate.get(template.id) || [];
        return this.databaseCacheRow(template.id, 'CAPABILITY_CENTER', template, cacheItems);
      });
      entriesByTemplate.forEach((cacheItems, templateId) => {
        if (configuredIds.has(templateId)) return;
        const template = this.templates.find(item => item.id === templateId) || null;
        rows.push(this.databaseCacheRow(templateId, 'EXISTING_CACHE', template, cacheItems));
      });
      rows.push(this.financialCacheRow());
      return rows;
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
      const remoteIds = Array.isArray(this.templateSearchResultIds)
        ? new Set(this.templateSearchResultIds)
        : null;
      return this.templates.filter(item => {
        if (remoteIds && !remoteIds.has(item.id)) return false;
        if (this.templatePickerCategory && String(item.category || 'default') !== this.templatePickerCategory) return false;
        if (!keyword || remoteIds) return true;
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
  beforeUnmount() {
    if (this.templateSearchTimer) clearTimeout(this.templateSearchTimer);
  },
  watch: {
    templatePickerKeyword() {
      this.scheduleTemplateSearch();
    },
    templatePickerCategory() {
      this.scheduleTemplateSearch();
    }
  },
  methods: {
    databaseCacheRow(templateId, source, template, cacheItems) {
      const items = cacheItems || [];
      return {
        key: `${source}:${templateId}`,
        source,
        template,
        templateId,
        title: template?.title || items[0]?.title || items[0]?.toolName || templateId,
        toolName: template?.toolName || items[0]?.toolName || '',
        datasourceId: template?.datasourceId || items[0]?.datasourceId || '',
        category: template?.categoryName || template?.category || 'default',
        cacheItems: items,
        entryCount: items.length,
        hitCount: items.reduce((total, item) => total + Number(item.hitCount || 0), 0),
        lastHitAt: items.reduce((latest, item) => Math.max(latest, Number(item.lastHitAt || 0)), 0)
      };
    },
    financialCacheRow() {
      const items = this.financialOverview.items || [];
      return {
        key: 'BUILT_IN:financial_data_search',
        source: 'BUILT_IN',
        template: null,
        templateId: 'financial_data_search',
        title: '内置金融数据查询',
        toolName: 'financial_data_search',
        datasourceId: '内置金融数据源',
        category: '内置数据源',
        cacheItems: items,
        entryCount: Number(this.financialOverview.entries || 0),
        hitCount: Number(this.financialOverview.hitCount || 0),
        lastHitAt: items.reduce((latest, item) => Math.max(latest, Number(item.lastHitAt || 0)), 0)
      };
    },
    async load() {
      this.busy = true;
      try {
        const [config, stats, databaseOverview, templates, redis, financialConfig, financialOverview] = await Promise.all([
          cacheApi.getConfig(),
          cacheApi.getStats(),
          cacheApi.getDatabaseEntries(),
          cacheApi.listTemplates(),
          cacheApi.getRedisConfig(),
          cacheApi.getFinancialConfig(),
          cacheApi.getFinancialEntries()
        ]);
        this.config = { ...this.config, ...(config || {}) };
        this.stats = stats || {};
        this.databaseOverview = { ...this.databaseOverview, ...(databaseOverview || {}) };
        this.templates = (templates || []).map(item => ({ ...item }));
        this.financialConfig = { ...this.financialConfig, ...(financialConfig || {}) };
        this.financialOverview = { ...this.financialOverview, ...(financialOverview || {}) };
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
      if (this.financialConfig.enabled && this.financialConfig.storage === 'REDIS' && !this.redis.enabled) {
        this.$emit('error', new Error('请先启用并配置 Redis 缓存存储'));
        return;
      }
      await this.run(async () => {
        await cacheApi.saveConfig(this.config);
        await cacheApi.saveFinancialConfig(this.financialConfig);
        await Promise.all(this.templates.map(item => cacheApi.saveTemplate(item.id, {
          cacheEnabled: item.cacheEnabled,
          cacheTtlSeconds: item.cacheTtlSeconds,
          cacheStorage: item.cacheStorage || 'ROCKSDB'
        })));
      }, '缓存策略已保存');
      await this.load();
    },
    openTemplatePicker() {
      this.templatePickerKeyword = '';
      this.templatePickerCategory = '';
      this.templateSearchResultIds = null;
      this.templatePickerSelected = this.templates.filter(item => item.cacheEnabled).map(item => item.id);
      this.templatePickerOpen = true;
    },
    scheduleTemplateSearch() {
      if (this.templateSearchTimer) clearTimeout(this.templateSearchTimer);
      const sequence = ++this.templateSearchSequence;
      const keyword = this.templatePickerKeyword.trim();
      const category = this.templatePickerCategory;
      if (!keyword && !category) {
        this.templateSearchResultIds = null;
        this.templateSearchBusy = false;
        return;
      }
      this.templateSearchTimer = setTimeout(() => this.loadTemplateSearchResults(keyword, category, sequence), 250);
    },
    async loadTemplateSearchResults(keyword, category, sequence) {
      this.templateSearchBusy = true;
      try {
        const matches = await cacheApi.listTemplates({ keyword, category });
        if (sequence === this.templateSearchSequence) {
          this.templateSearchResultIds = (matches || []).map(item => item.id);
        }
      } catch (error) {
        if (sequence === this.templateSearchSequence) this.$emit('error', error);
      } finally {
        if (sequence === this.templateSearchSequence) this.templateSearchBusy = false;
      }
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
    formatFinancialFilters(filters) {
      const entries = Object.entries(filters || {});
      return entries.length ? entries.map(([key, value]) => {
        const rendered = value && typeof value === 'object' ? JSON.stringify(value) : value;
        return `${key}=${rendered}`;
      }).join('，') : '无';
    },
    formatDatabaseParameters(parameters) {
      const entries = Object.entries(parameters || {});
      return entries.length ? entries.map(([key, value]) => {
        const rendered = value && typeof value === 'object' ? JSON.stringify(value) : value;
        return `${key}=${rendered}`;
      }).join('，') : '无参数';
    },
    formatTime(value) {
      if (!value) return '-';
      return new Date(value).toLocaleString('zh-CN', { hour12: false });
    },
    async cleanupAllExpired() {
      if (!window.confirm('确定清理全部来源的过期查询缓存吗？')) return;
      await this.run(() => Promise.all([
        cacheApi.cleanupExpired(),
        cacheApi.cleanupFinancialExpired()
      ]), '全部来源的过期缓存已清理');
      await this.load();
    },
    async evictAllCaches() {
      if (!window.confirm('确定清理能力中心和内置数据源的全部查询缓存吗？')) return;
      await this.run(() => Promise.all([
        cacheApi.evictAll(),
        cacheApi.evictFinancialAll()
      ]), '全部查询缓存已清理');
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
      await this.runTest(() => cacheApi.testRedisConfig(payload), {
        successTitle: 'Redis 连接测试成功',
        failureTitle: 'Redis 连接测试失败'
      });
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
    },
    async runTest(action, notificationOptions) {
      this.busy = true;
      try {
        const result = await action();
        this.$emit('notify', buildTestNotification(result, notificationOptions));
        return result;
      } catch (error) {
        this.$emit('error', error);
        return null;
      } finally {
        this.busy = false;
      }
    }
  }
};



