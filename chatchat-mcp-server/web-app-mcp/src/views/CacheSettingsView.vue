<template>
  <section class="workspace-panel">
    <header class="panel-heading">
      <div>
        <h2>缓存设置</h2>
        <p>配置数据库查询缓存策略并查看运行统计。</p>
      </div>
      <div class="panel-actions">
        <el-button plain :loading="busy" @click="load">
          <el-icon><Refresh /></el-icon>
          <span>刷新配置</span>
        </el-button>
        <el-button type="primary" :loading="busy" @click="save">
          <el-icon><Setting /></el-icon>
          <span>保存策略</span>
        </el-button>
      </div>
    </header>

    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>运行统计</h3>
          <p>查看缓存当前状态、条目数量和存储占用。</p>
        </div>
      </div>
      <div class="metric-grid">
        <div class="metric-card"><span>状态</span><strong>{{ status }}</strong></div>
        <div class="metric-card"><span>缓存条目</span><strong>{{ stats.entries ?? 0 }}</strong></div>
        <div class="metric-card"><span>过期条目</span><strong>{{ stats.expiredEntries ?? 0 }}</strong></div>
        <div class="metric-card"><span>占用空间</span><strong>{{ bytes }}</strong></div>
      </div>
    </section>

    <section class="cache-section">
      <div class="cache-section-head">
        <div>
          <h3>缓存策略</h3>
          <p>控制数据库查询结果的缓存开关、容量限制和 Key 生成方式。</p>
        </div>
      </div>
      <el-form class="cache-form" label-position="top">
        <el-row :gutter="14">
          <el-col :xs="24" :md="8">
            <el-form-item label="启用缓存">
              <el-select v-model="config.enabled" class="w-100">
                <el-option label="启用" :value="true" />
                <el-option label="停用" :value="false" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="默认 TTL（秒）">
              <el-input-number v-model="config.defaultTtlSeconds" class="w-100" :min="1" :step="1" controls-position="right" />
            </el-form-item>
          </el-col>
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
                <el-option label="SQL + 参数 + 数据源" value="SQL_PARAMS_DATASOURCE" />
                <el-option label="规范 SQL + 参数" value="NORMALIZED_SQL_PARAMS" />
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
          <p>设置空结果和错误结果是否进入缓存，避免不必要的重复查询。</p>
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
          <h3>缓存维护</h3>
          <p>清理无效缓存或在策略调整后重置全部缓存数据。</p>
        </div>
        <div class="cache-maintenance-actions">
          <el-button plain type="danger" :loading="busy" @click="cleanupExpired">清理过期缓存</el-button>
          <el-button type="danger" :loading="busy" @click="evictAll">清理全部缓存</el-button>
        </div>
      </div>
    </section>
  </section>
</template>

<script src="../scripts/views/CacheSettingsView.js"></script>

