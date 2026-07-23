import { API_BASE } from './config';
import { apiFetch } from './http';

const SEARCH_INDEX_URL = `${API_BASE}/mcp-search-index`;

export const authApi = {
  currentUser: () => apiFetch(`${API_BASE}/admin/auth/me`),
  loginAudits: params => {
    const query = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value) !== '') query.set(key, value);
    });
    return apiFetch(`${API_BASE}/admin/login-audits${query.toString() ? `?${query}` : ''}`);
  },
  changePassword: (currentPassword, newPassword) => apiFetch(`${API_BASE}/admin/auth/password`, {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword })
  }),
  changeManagedPassword: payload => apiFetch(`${API_BASE}/admin/auth/password`, {
    method: 'POST',
    body: JSON.stringify(payload || {})
  })
};

export const apiServicesApi = {
  list: () => apiFetch(`${API_BASE}/api-services`),
  save: service => saveEntity(`${API_BASE}/api-services`, service, toApiServicePayload),
  remove: id => apiFetch(`${API_BASE}/api-services/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  batchRemove: ids => apiFetch(`${API_BASE}/api-services/batch-delete`, { method: 'POST', body: JSON.stringify({ ids }) }),
  setEnabled: (id, enabled) => apiFetch(`${API_BASE}/api-services/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' }),
  test: (id, args) => apiFetch(`${API_BASE}/api-services/${encodeURIComponent(id)}/test`, { method: 'POST', body: JSON.stringify(args || {}) }),
  refresh: () => apiFetch(`${API_BASE}/api-services/refresh`, { method: 'POST' }),
  rebuildIndex: () => apiFetch(`${SEARCH_INDEX_URL}/api-services/rebuild`, { method: 'POST' }),
  listLivedata: () => apiFetch(`${API_BASE}/livedata-apis`),
  getLivedataConfig: () => apiFetch(`${API_BASE}/livedata-apis/config`),
  saveLivedataConfig: config => apiFetch(`${API_BASE}/livedata-apis/config`, { method: 'PUT', body: JSON.stringify(config || {}) }),
  registerLivedata: (ids, overwriteExisting) => apiFetch(`${API_BASE}/livedata-apis/register`, {
    method: 'POST',
    body: JSON.stringify({ ids, overwriteExisting })
  }),
  testLivedata: (id, args) => apiFetch(`${API_BASE}/livedata-apis/${encodeURIComponent(id)}/test`, {
    method: 'POST',
    body: JSON.stringify(args || {})
  }),
  deleteLivedataRegistration: id => apiFetch(
    `${API_BASE}/livedata-apis/${encodeURIComponent(id)}/registration`,
    { method: 'DELETE' }
  )
};

export const mcpServicesApi = {
  list: () => apiFetch(`${API_BASE}/mcp-services`),
  save: service => saveEntity(`${API_BASE}/mcp-services`, service),
  remove: id => apiFetch(`${API_BASE}/mcp-services/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  setEnabled: (id, enabled) => apiFetch(`${API_BASE}/mcp-services/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' }),
  generateToken: () => apiFetch(`${API_BASE}/mcp-services/generate-token`, { method: 'POST' }),
  regenerateToken: id => apiFetch(`${API_BASE}/mcp-services/${encodeURIComponent(id)}/token`, { method: 'POST' })
};

export const newsApi = {
  listSources: () => apiFetch(`${API_BASE}/news/sources`),
  listPresets: () => apiFetch(`${API_BASE}/news/presets`),
  listCollectionTemplates: () => apiFetch(`${API_BASE}/news/collection-templates`),
  listPatternPresets: () => apiFetch(`${API_BASE}/news/pattern-presets`),
  getRule: id => apiFetch(`${API_BASE}/news/sources/${encodeURIComponent(id)}/rule`),
  saveSource: source => saveEntity(`${API_BASE}/news/sources`, source),
  saveRule: (id, rule) => apiFetch(`${API_BASE}/news/sources/${encodeURIComponent(id)}/rule`, { method: 'PUT', body: JSON.stringify(rule || {}) }),
  removeSource: id => apiFetch(`${API_BASE}/news/sources/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    collect: id => apiFetch(`${API_BASE}/news/sources/${encodeURIComponent(id)}/collect`, { method: 'POST' }),
    checkRobots: id => apiFetch(`${API_BASE}/news/sources/${encodeURIComponent(id)}/robots-check`, { method: 'POST' }),
  listRecords: params => {
    const query = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value) !== '') query.set(key, value);
    });
    return apiFetch(`${API_BASE}/news/records?${query}`);
  },
  searchIndex: request => apiFetch(`${API_BASE}/news/search`, { method: 'POST', body: JSON.stringify(request || {}) })
};

export const assetsApi = {
  listSsh: () => apiFetch(`${API_BASE}/ops/ssh-hosts`),
  saveSsh: asset => saveEntity(`${API_BASE}/ops/ssh-hosts`, asset),
  deleteSsh: id => apiFetch(`${API_BASE}/ops/ssh-hosts/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  testSsh: asset => apiFetch(`${API_BASE}/ops/ssh-hosts/test`, { method: 'POST', body: JSON.stringify(asset) }),
  listHttp: () => apiFetch(`${API_BASE}/ops/http-endpoints`),
  saveHttp: asset => saveEntity(`${API_BASE}/ops/http-endpoints`, asset),
  deleteHttp: id => apiFetch(`${API_BASE}/ops/http-endpoints/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  testHttp: asset => apiFetch(`${API_BASE}/ops/http-endpoints/test`, { method: 'POST', body: JSON.stringify(asset) }),
  refreshOps: () => apiFetch(`${API_BASE}/ops/refresh-tools`, { method: 'POST' }),
  listSql: () => apiFetch(`${API_BASE}/sql/datasources`),
  saveSql: asset => saveEntity(`${API_BASE}/sql/datasources`, asset),
  deleteSql: id => apiFetch(`${API_BASE}/sql/datasources/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  testSql: asset => apiFetch(`${API_BASE}/sql/datasources/test`, { method: 'POST', body: JSON.stringify(asset) }),
  refreshSqlTools: () => apiFetch(`${API_BASE}/sql/refresh-tools`, { method: 'POST' }),
  refreshSqlMetadata: id => apiFetch(`${API_BASE}/sql/datasources/${encodeURIComponent(id)}/metadata/refresh`, { method: 'POST' }),
  listCommandTemplates: () => apiFetch(`${API_BASE}/ops/command-templates`),
  saveCommandTemplate: template => saveEntity(`${API_BASE}/ops/command-templates`, template),
  deleteCommandTemplate: id => apiFetch(`${API_BASE}/ops/command-templates/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listSqlTemplates: () => apiFetch(`${API_BASE}/sql/templates`),
  saveSqlTemplate: template => saveEntity(`${API_BASE}/sql/templates`, template),
  deleteSqlTemplate: id => apiFetch(`${API_BASE}/sql/templates/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  rebuildAssetIndex: assetType => apiFetch(`${SEARCH_INDEX_URL}${assetType ? `/assets/${encodeURIComponent(assetType)}/rebuild` : '/assets/rebuild'}`, { method: 'POST' }),
  rebuildSelectedSqlAssetIndexes: ids => apiFetch(`${SEARCH_INDEX_URL}/assets/sql_datasource/rebuild-selected`, { method: 'POST', body: JSON.stringify({ ids: ids || [] }) }),
  rebuildTemplateIndex: () => apiFetch(`${SEARCH_INDEX_URL}/templates/rebuild`, { method: 'POST' }),
  searchIndex: request => apiFetch(`${SEARCH_INDEX_URL}/search`, { method: 'POST', body: JSON.stringify(request || {}) }),
  validateTemplateDsl: request => apiFetch(`${API_BASE}/template-dsl/validate`, { method: 'POST', body: JSON.stringify(request || {}) }),
  importTemplateDsl: request => apiFetch(`${API_BASE}/template-dsl/import`, { method: 'POST', body: JSON.stringify(request || {}) })
};

export const databaseApi = {
  list: () => apiFetch(`${API_BASE}/database-query`),
  save: query => saveEntity(`${API_BASE}/database-query`, query),
  remove: id => apiFetch(`${API_BASE}/database-query/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  batchRemove: ids => apiFetch(`${API_BASE}/database-query/batch-delete`, { method: 'POST', body: JSON.stringify({ ids }) }),
  setEnabled: (id, enabled) => apiFetch(`${API_BASE}/database-query/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' }),
  testSaved: (id, params) => apiFetch(`${API_BASE}/database-query/${encodeURIComponent(id)}/test`, { method: 'POST', body: JSON.stringify(params || {}) }),
  testDraft: payload => apiFetch(`${API_BASE}/database-query/test`, { method: 'POST', body: JSON.stringify(payload) }),
  rebuildIndex: () => apiFetch(`${SEARCH_INDEX_URL}/database-queries/rebuild`, { method: 'POST' }),
  validateDsl: request => apiFetch(`${API_BASE}/template-dsl/validate`, { method: 'POST', body: JSON.stringify({ ...(request || {}), templateType: 'DATABASE_QUERY' }) }),
  importDsl: request => apiFetch(`${API_BASE}/template-dsl/import`, { method: 'POST', body: JSON.stringify({ ...(request || {}), templateType: 'DATABASE_QUERY' }) }),
  getTradingCalendar: () => apiFetch(`${API_BASE}/dynamic-date-params/trading-calendar/config`),
  saveTradingCalendar: config => apiFetch(`${API_BASE}/dynamic-date-params/trading-calendar/config`, { method: 'PUT', body: JSON.stringify(config || {}) }),
  testTradingCalendar: config => apiFetch(`${API_BASE}/dynamic-date-params/trading-calendar/test`, { method: 'POST', body: JSON.stringify(config || {}) }),
  testTradingCalendarFunction: config => apiFetch(`${API_BASE}/dynamic-date-params/trading-calendar/function-test`, { method: 'POST', body: JSON.stringify(config || {}) })
};

export const cacheApi = {
  getConfig: () => apiFetch(`${API_BASE}/cache/database-query/config`),
  saveConfig: config => apiFetch(`${API_BASE}/cache/database-query/config`, { method: 'PUT', body: JSON.stringify(config) }),
  getStats: () => apiFetch(`${API_BASE}/cache/database-query/stats`),
  listTemplates: (filters = {}) => {
    const params = new URLSearchParams();
    if (filters.keyword) params.set('keyword', filters.keyword);
    if (filters.category) params.set('category', filters.category);
    const query = params.toString();
    return apiFetch(`${API_BASE}/cache/database-query/templates${query ? `?${query}` : ''}`);
  },
  saveTemplate: (id, policy) => apiFetch(`${API_BASE}/cache/database-query/templates/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(policy) }),
  getRedisConfig: () => apiFetch(`${API_BASE}/cache/database-query/storage/redis`),
  saveRedisConfig: config => apiFetch(`${API_BASE}/cache/database-query/storage/redis`, { method: 'PUT', body: JSON.stringify(config) }),
  testRedisConfig: config => apiFetch(`${API_BASE}/cache/database-query/storage/redis/test`, { method: 'POST', body: JSON.stringify(config) }),
  cleanupExpired: () => apiFetch(`${API_BASE}/cache/database-query/cleanup-expired`, { method: 'POST' }),
  evictAll: () => apiFetch(`${API_BASE}/cache/database-query/evict`, { method: 'POST' })
};

export const notificationApi = {
  list: () => apiFetch(`${API_BASE}/notifications`),
  save: channel => saveEntity(`${API_BASE}/notifications`, channel),
  setEnabled: (id, enabled) => apiFetch(`${API_BASE}/notifications/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' }),
  setRuntimeAction: (id, runtimeAction) => apiFetch(`${API_BASE}/notifications/${encodeURIComponent(id)}/runtime-action?runtimeAction=${encodeURIComponent(runtimeAction)}`, { method: 'POST' }),
  test: (id, payload) => apiFetch(`${API_BASE}/notifications/${encodeURIComponent(id)}/test`, { method: 'POST', body: JSON.stringify(payload || {}) }),
  refresh: () => apiFetch(`${API_BASE}/notifications/refresh`, { method: 'POST' })
};

export const auditApi = {
  list: params => {
    const query = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value) !== '') query.set(key, value);
    });
    return apiFetch(`${API_BASE}/audit-logs${query.toString() ? `?${query}` : ''}`);
  },
  get: id => apiFetch(`${API_BASE}/audit-logs/${encodeURIComponent(id)}`)
};

export const authorizationApi = {
  snapshot: () => apiFetch(`${API_BASE}/mcp-authorization/snapshot`),
  sync: () => apiFetch(`${API_BASE}/mcp-authorization/sync`, { method: 'POST' }),
  roles: (tenantId = '') => {
    const query = new URLSearchParams();
    if (tenantId) query.set('tenantId', tenantId);
    return apiFetch(`${API_BASE}/mcp-authorization/roles${query.toString() ? `?${query}` : ''}`);
  },
  users: (tenantId = '') => {
    const query = new URLSearchParams();
    if (tenantId) query.set('tenantId', tenantId);
    return apiFetch(`${API_BASE}/mcp-authorization/users${query.toString() ? `?${query}` : ''}`);
  },
  rolePermissions: (roleId, tenantId = '') => {
    const query = new URLSearchParams({ roleId });
    if (tenantId) query.set('tenantId', tenantId);
    return apiFetch(`${API_BASE}/mcp-authorization/role-permissions?${query}`);
  },
  createRolePermission: payload => apiFetch(`${API_BASE}/mcp-authorization/role-permissions`, { method: 'POST', body: JSON.stringify(payload) }),
  deleteRolePermission: id => apiFetch(`${API_BASE}/mcp-authorization/role-permissions/${encodeURIComponent(id)}`, { method: 'DELETE' })
};

export const licenseApi = {
  status: () => apiFetch(`${API_BASE}/license/status`)
};

function saveEntity(baseUrl, entity, mapper = value => value) {
  const body = JSON.stringify(mapper(entity));
  if (entity.id) {
    return apiFetch(`${baseUrl}/${encodeURIComponent(entity.id)}`, { method: 'PUT', body });
  }
  return apiFetch(baseUrl, { method: 'POST', body });
}

function toApiServicePayload(service) {
  return {
    toolName: service.toolName,
    title: service.title,
    description: service.description,
    businessGroup: service.businessGroup,
    businessGroupName: service.businessGroupName,
    businessGroupDescription: service.businessGroupDescription,
    gatewayId: service.gatewayId,
    method: null,
    urlTemplate: null,
    headers: null,
    bodyTemplate: null,
    inputSchema: service.inputSchema,
    outputSchema: service.outputSchema,
    capabilitySpec: service.capabilitySpec,
    dependencySpec: service.dependencySpec,
    governance: service.governance,
    enabled: service.enabled,
    timeoutMs: null,
    cacheEnabled: service.cacheEnabled,
    cacheTtlSeconds: service.cacheTtlSeconds
  };
}
