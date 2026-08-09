<template>
  <section class="workspace-panel">
    <header class="panel-heading">
      <div>
        <h2>缓存设置</h2>
        <p>统一管理查询缓存、内置数据源自动缓存及底层存储服务。</p>
      </div>
      <div class="panel-actions">
        <el-button plain :loading="busy" @click="load">
          <el-icon><Refresh /></el-icon>
          <span>刷新配置</span>
        </el-button>
        <el-button type="primary" :loading="busy" @click="save">
          <el-icon><Setting /></el-icon>
          <span>{{ activeTab === 'storage' ? '保存存储配置' : '保存缓存策略' }}</span>
        </el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="workspace-tabs">
      <el-tab-pane label="缓存管理" name="templates">
    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>运行统计</h3>
          <p>汇总能力中心和内置数据源的缓存状态、条目数量、命中次数与存储占用。</p>
        </div>
      </div>
      <div class="metric-grid">
        <div class="metric-card"><span>状态</span><strong>{{ status }}</strong></div>
        <div class="metric-card"><span>缓存条目</span><strong>{{ unifiedStats.entries }}</strong></div>
        <div class="metric-card"><span>过期条目</span><strong>{{ unifiedStats.expiredEntries }}</strong></div>
        <div class="metric-card"><span>命中查询次数</span><strong>{{ unifiedStats.hitCount }}</strong></div>
        <div class="metric-card"><span>占用空间</span><strong>{{ bytes }}</strong></div>
      </div>
      <el-alert
        v-for="warning in cacheWarnings"
        :key="warning"
        class="mt-3"
        type="warning"
        :title="warning"
        show-icon
        :closable="false"
      />
    </section>

    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>查询缓存</h3>
          <p>能力中心和内置数据源在同一列表管理，通过来源标识区分；展开行查看实际缓存明细和来源专属策略。</p>
        </div>
        <div class="cache-maintenance-actions">
          <el-button type="primary" plain @click="openTemplatePicker">选择缓存模板</el-button>
          <el-button plain type="danger" :loading="busy" @click="cleanupAllExpired">清理过期缓存</el-button>
          <el-button type="danger" :loading="busy" @click="evictAllCaches">清理全部缓存</el-button>
        </div>
      </div>
      <el-table row-key="key" :data="unifiedCacheRows" border stripe empty-text="尚未配置或生成查询缓存">
        <el-table-column type="expand" width="48">
          <template #default="{ row }">
            <div class="cache-entry-details">
              <el-form v-if="row.source === 'BUILT_IN'" class="cache-form" label-position="top">
                <el-row :gutter="14">
                  <el-col :xs="24" :md="8">
                    <el-form-item label="单条最大大小（KB）">
                      <el-input-number v-model="financialConfig.maxEntryKb" class="w-100" :min="1" :max="102400" :step="128" controls-position="right" :disabled="!financialConfig.enabled" />
                    </el-form-item>
                  </el-col>
                  <el-col :xs="24" :md="8">
                    <el-form-item label="Redis 不可用时">
                      <el-select v-model="financialConfig.fallbackToRocksDb" class="w-100" :disabled="!financialConfig.enabled || financialConfig.storage !== 'REDIS'">
                        <el-option label="回退 RocksDB" :value="true" />
                        <el-option label="不写入缓存" :value="false" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :xs="24" :md="8">
                    <el-form-item label="并发结果共享窗口（毫秒）">
                      <el-input-number v-model="financialConfig.singleFlightGraceMs" class="w-100" :min="0" :max="5000" :step="100" controls-position="right" :disabled="!financialConfig.enabled" />
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-form>
              <el-table v-if="row.source === 'BUILT_IN'" :data="row.cacheItems" size="small" border empty-text="该数据源尚未产生实际缓存；首次查询会创建缓存">
                <el-table-column prop="dataset" label="数据集" min-width="180" show-overflow-tooltip>
                  <template #default="{ row: item }"><code>{{ item.dataset }}</code></template>
                </el-table-column>
                <el-table-column label="查询条件" min-width="240" show-overflow-tooltip>
                  <template #default="{ row: item }">{{ formatFinancialFilters(item.filters) }}</template>
                </el-table-column>
                <el-table-column label="日期范围" min-width="180">
                  <template #default="{ row: item }">{{ item.startDate || '不限' }} ～ {{ item.endDate || '不限' }}</template>
                </el-table-column>
                <el-table-column prop="tenantId" label="租户" min-width="120" show-overflow-tooltip />
                <el-table-column prop="storage" label="存储" width="100" align="center" />
                <el-table-column prop="hitCount" label="命中查询次数" width="130" align="center" sortable />
                <el-table-column label="最后命中" min-width="170">
                  <template #default="{ row: item }">{{ formatTime(item.lastHitAt) }}</template>
                </el-table-column>
                <el-table-column label="过期时间" min-width="170">
                  <template #default="{ row: item }">{{ formatTime(item.expiresAt) }}</template>
                </el-table-column>
              </el-table>
              <el-table v-else :data="row.cacheItems" size="small" border empty-text="该模板尚未产生实际缓存；首次查询会创建缓存">
                <el-table-column label="查询参数" min-width="240" show-overflow-tooltip>
                  <template #default="{ row: item }">{{ formatDatabaseParameters(item.parameters) }}</template>
                </el-table-column>
                <el-table-column prop="tenantId" label="租户" min-width="120" show-overflow-tooltip />
                <el-table-column prop="storage" label="存储" width="100" align="center" />
                <el-table-column prop="hitCount" label="命中查询次数" width="130" align="center" sortable />
                <el-table-column label="最后命中" min-width="170">
                  <template #default="{ row: item }">{{ formatTime(item.lastHitAt) }}</template>
                </el-table-column>
                <el-table-column label="过期时间" min-width="170">
                  <template #default="{ row: item }">{{ formatTime(item.expiresAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.source === 'CAPABILITY_CENTER' ? 'primary' : row.source === 'BUILT_IN' ? 'success' : 'info'">
              {{ row.source === 'CAPABILITY_CENTER' ? '能力中心' : row.source === 'BUILT_IN' ? '内置数据源' : '已有缓存' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="查询模板" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.title }}</div>
            <code>{{ row.toolName }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="datasourceId" label="数据源" min-width="170" show-overflow-tooltip />
        <el-table-column label="分类" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.category }}</template>
        </el-table-column>
        <el-table-column prop="entryCount" label="缓存条目" width="100" align="center" sortable />
        <el-table-column prop="hitCount" label="命中查询次数" width="130" align="center" sortable />
        <el-table-column label="最后命中" min-width="170">
          <template #default="{ row }">{{ formatTime(row.lastHitAt) }}</template>
        </el-table-column>
        <el-table-column label="存储服务" width="150">
          <template #default="{ row }">
            <el-select v-if="row.source === 'BUILT_IN'" v-model="financialConfig.storage" :disabled="!financialConfig.enabled">
              <el-option label="本地 RocksDB" value="ROCKSDB" />
              <el-option label="Redis" value="REDIS" :disabled="!redis.enabled" />
            </el-select>
            <el-select v-else-if="row.template?.cacheEnabled" v-model="row.template.cacheStorage" :disabled="!row.template.enabled">
              <el-option label="本地 RocksDB" value="ROCKSDB" />
              <el-option label="Redis" value="REDIS" :disabled="!redis.enabled" />
            </el-select>
            <span v-else>{{ row.cacheItems[0]?.storage || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="TTL（秒）" width="180">
          <template #default="{ row }">
            <el-input-number
              v-if="row.source === 'BUILT_IN'"
              v-model="financialConfig.ttlSeconds"
              class="w-100"
              :disabled="!financialConfig.enabled"
              :min="1"
              :max="604800"
              :step="300"
              controls-position="right"
            />
            <el-input-number
              v-else-if="row.template?.cacheEnabled"
              v-model="row.template.cacheTtlSeconds"
              class="w-100"
              :disabled="!row.template.enabled"
              :min="1"
              :max="86400"
              :step="60"
              controls-position="right"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-if="row.source === 'BUILT_IN'" v-model="financialConfig.enabled" />
            <el-button v-else-if="row.template?.cacheEnabled" link type="danger" @click="row.template.cacheEnabled = false">移除</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>存储保护策略</h3>
          <p>这些限制只约束能力中心中已单独开启缓存的模板，不影响内置数据源行的缓存策略。</p>
        </div>
      </div>
      <el-form class="cache-form" label-position="top">
        <el-row :gutter="14">
          <el-col :xs="24" :md="8">
            <el-form-item label="最大缓存行数">
              <el-input-number v-model="config.maxRows" class="w-100" :min="1" :step="100" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="单条最大大小（KB）">
              <el-input-number v-model="config.maxEntryKb" class="w-100" :min="1" :step="64" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="缓存 Key 策略">
              <el-select v-model="config.keyStrategy" class="w-100">
                <el-option label="模板 ID + 参数 + 数据源" value="TEMPLATE_ID_PARAMS_DATASOURCE" />
                <el-option label="模板 ID + 参数 + 数据源 + 用户" value="TEMPLATE_ID_PARAMS_DATASOURCE_USER" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </section>

    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>结果缓存范围</h3>
          <p>设置能力中心模板的空结果和错误结果是否进入缓存，避免不必要的重复查询。</p>
        </div>
      </div>
      <el-form class="cache-form" label-position="top">
        <el-row :gutter="14">
          <el-col :xs="24" :md="8">
            <el-form-item label="空结果缓存">
              <el-select v-model="config.cacheEmptyResults" class="w-100">
                <el-option label="缓存" :value="true" />
                <el-option label="不缓存" :value="false" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="错误结果缓存">
              <el-select v-model="config.cacheErrorResults" class="w-100">
                <el-option label="缓存" :value="true" />
                <el-option label="不缓存" :value="false" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </section>

    <section class="cache-section cache-maintenance">
      <div class="cache-section-head">
        <div>
          <h3>缓存服务模块</h3>
          <p>统一控制能力中心模板缓存和内置数据源缓存；各来源仍可在上方列表中维护具体策略。</p>
        </div>
        <div class="cache-service-switch">
          <span>缓存服务模块开启</span>
          <el-switch v-model="cacheModuleEnabled" />
        </div>
      </div>
    </section>

      </el-tab-pane>

      <el-tab-pane label="缓存存储服务设置" name="storage">
        <section class="cache-section">
          <div class="cache-section-head">
            <div>
              <h3>存储服务状态</h3>
              <p>RocksDB 是默认本地存储；启用并验证 Redis 后，可供数据库查询缓存和内置数据源缓存选择。</p>
            </div>
          </div>
          <div class="metric-grid">
            <div class="metric-card"><span>RocksDB</span><strong>{{ stats.rocksDbAvailable ? '可用' : '不可用' }}</strong></div>
            <div class="metric-card"><span>Redis 配置</span><strong>{{ redis.enabled ? '已启用' : '未启用' }}</strong></div>
            <div class="metric-card"><span>Redis 连接</span><strong>{{ redis.available ? '可用' : '未验证' }}</strong></div>
            <div class="metric-card"><span>Redis 模式</span><strong>{{ redis.mode }}</strong></div>
          </div>
        </section>

        <section class="cache-section">
          <div class="cache-section-head">
            <div>
              <h3>Redis 连接配置</h3>
              <p>支持无认证单机、ACL 用户名密码、Sentinel 哨兵和 Redis Cluster 集群连接。</p>
            </div>
            <el-button plain type="primary" :loading="busy" @click="testRedis">测试连接</el-button>
          </div>
          <el-form class="cache-form" label-position="top">
            <el-row :gutter="14">
              <el-col :xs="24" :md="8">
                <el-form-item label="启用 Redis 存储" required>
                  <el-select v-model="redis.enabled" class="w-100">
                    <el-option label="启用" :value="true" />
                    <el-option label="停用" :value="false" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :md="8">
                <el-form-item label="连接模式" required>
                  <el-select v-model="redis.mode" class="w-100">
                    <el-option label="单机－无用户名密码" value="STANDALONE_NO_AUTH" />
                    <el-option label="单机－用户名密码" value="STANDALONE_AUTH" />
                    <el-option label="哨兵模式 Sentinel" value="SENTINEL" />
                    <el-option label="集群模式 Cluster" value="CLUSTER" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col v-if="!redisUsesCluster" :xs="24" :md="8">
                <el-form-item label="数据库编号" required>
                  <el-input-number v-model="redis.databaseIndex" class="w-100" :min="0" :max="15" controls-position="right" />
                </el-form-item>
              </el-col>
              <el-col :span="24">
                <el-form-item :label="redisUsesSentinel || redisUsesCluster ? 'Redis 节点（每行一个）' : 'Redis 节点'" required>
                  <el-input
                    v-model="redis.nodesText"
                    type="textarea"
                    :rows="redisUsesSentinel || redisUsesCluster ? 4 : 2"
                    placeholder="127.0.0.1:6379"
                  />
                  <div class="field-help">使用 host:port 格式；哨兵或集群模式每行填写一个节点。</div>
                </el-form-item>
              </el-col>
              <el-col v-if="redisUsesSentinel" :xs="24" :md="8">
                <el-form-item label="Master 名称" required>
                  <el-input v-model.trim="redis.masterName" placeholder="如 mymaster" />
                </el-form-item>
              </el-col>
              <template v-if="redisUsesSentinel">
                <el-col :xs="24" :md="8">
                  <el-form-item label="哨兵用户名">
                    <el-input v-model.trim="redis.sentinelUsername" placeholder="Sentinel 无认证时留空" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-form-item label="哨兵密码">
                    <el-input
                      v-model="redis.sentinelPassword"
                      type="password"
                      show-password
                      autocomplete="new-password"
                      :placeholder="redis.sentinelPasswordConfigured ? '已配置，留空表示不修改' : 'Sentinel 无认证时留空'"
                    />
                  </el-form-item>
                </el-col>
              </template>
              <template v-if="redisUsesAuthentication">
                <el-col :xs="24" :md="8">
                  <el-form-item label="Redis 用户名">
                    <el-input v-model.trim="redis.username" placeholder="ACL 用户名；仅密码认证时可留空" />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="8">
                  <el-form-item label="Redis 密码" :required="redis.mode === 'STANDALONE_AUTH' && !redis.passwordConfigured">
                    <el-input
                      v-model="redis.password"
                      type="password"
                      show-password
                      autocomplete="new-password"
                      :placeholder="redis.passwordConfigured ? '已配置，留空表示不修改' : '请输入 Redis 密码'"
                    />
                  </el-form-item>
                </el-col>
              </template>
              <el-col :xs="24" :md="8">
                <el-form-item label="SSL/TLS" required>
                  <el-select v-model="redis.ssl" class="w-100">
                    <el-option label="启用" :value="true" />
                    <el-option label="停用" :value="false" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :md="8">
                <el-form-item label="连接超时（毫秒）" required>
                  <el-input-number v-model="redis.timeoutMillis" class="w-100" :min="500" :max="60000" :step="500" controls-position="right" />
                </el-form-item>
              </el-col>
              <el-col v-if="redisUsesCluster" :xs="24" :md="8">
                <el-form-item label="最大重定向次数" required>
                  <el-input-number v-model="redis.maxRedirects" class="w-100" :min="1" :max="20" controls-position="right" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="templatePickerOpen" title="选择 SQL 模板缓存" width="min(980px, 92vw)" append-to-body>
      <div class="cache-template-picker-toolbar">
        <el-select v-model="templatePickerCategory" clearable placeholder="全部分类">
          <el-option v-for="option in templateCategories" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-input v-model.trim="templatePickerKeyword" clearable placeholder="搜索模板名称、工具、分类、数据库类型或数据源">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button plain @click="selectVisibleTemplates">选择当前结果</el-button>
        <el-button plain @click="clearVisibleTemplates">清除当前结果</el-button>
      </div>

      <el-table v-loading="templateSearchBusy" :data="templatePickerItems" border stripe max-height="480" empty-text="暂无匹配的 SQL 模板">
        <el-table-column label="" width="56" align="center">
          <template #default="{ row }">
            <el-checkbox
              :model-value="templatePickerSelected.includes(row.id)"
              :disabled="!row.enabled && !templatePickerSelected.includes(row.id)"
              @change="toggleTemplatePicker(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="title" label="模板名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="id" label="Template ID" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column prop="toolName" label="工具名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.toolName }}</code></template>
        </el-table-column>
        <el-table-column label="分类" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.categoryName || row.category || 'default' }}</template>
        </el-table-column>
        <el-table-column prop="databaseType" label="数据库类型" width="120" />
        <el-table-column prop="datasourceId" label="数据源" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <div class="cache-template-picker-footer">
          <span>已选择 {{ templatePickerSelected.length }} 个模板</span>
          <div>
            <el-button @click="templatePickerOpen = false">取消</el-button>
            <el-button type="primary" @click="confirmTemplatePicker">确定</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script src="../scripts/views/CacheSettingsView.js"></script>

