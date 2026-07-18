<template>
  <section class="news-admin-view">
    <el-card shadow="never" class="news-hero">
      <div><h2>资讯采集后台</h2><p>配置资讯网站、采集周期、限速与精确抽取规则。这里只管理采集，Agent 仅能调用已发布的查询工具。</p></div>
      <div class="news-hero-actions">
        <el-button :loading="loading" @click="refreshSources"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button @click="openLogs">采集日志</el-button>
        <el-button type="primary" @click="createSource">新增资讯源</el-button>
      </div>
    </el-card>

    <el-alert title="所有资讯源在启用和采集前都会检测 robots.txt。明确禁止或无法可靠确认许可时将停止采集并提示原因；robots.txt 检测不替代网站使用条款及法律审查。" type="warning" :closable="false" show-icon />

    <section class="news-list-toolbar">
      <el-input
        v-model="filters.keyword"
        clearable
        placeholder="搜索名称、编码、地址或域名"
        @input="resetPage"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="filters.sourceType" clearable placeholder="全部类型" @change="resetPage">
        <el-option v-for="option in sourceTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="filters.enabled" clearable placeholder="全部状态" @change="resetPage">
        <el-option label="已启用" :value="true" />
        <el-option label="已停用" :value="false" />
      </el-select>
      <el-button @click="resetFilters">重置</el-button>
      <span class="news-list-count">共 {{ filteredSources.length }} 条</span>
    </section>

    <el-table v-loading="loading" :data="pagedSources" border stripe class="news-table" empty-text="暂无匹配的资讯源">
      <el-table-column prop="sourceName" label="资讯源" min-width="170"><template #default="{ row }"><strong>{{ row.sourceName }}</strong><small>{{ row.sourceCode }}</small></template></el-table-column>
      <el-table-column label="类型" width="140"><template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template></el-table-column>
      <el-table-column prop="entryUrl" label="入口地址" min-width="300" show-overflow-tooltip />
      <el-table-column label="调度计划" width="190">
        <template #default="{ row }">
          <span class="schedule-table-label">{{ describeCron(row.scheduleCron) }}</span>
          <small>{{ row.scheduleCron }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="collectedRecords" label="记录" width="85" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="430" fixed="right"><template #default="{ row }">
        <el-button link type="primary" @click="editSource(row)">配置</el-button>
        <el-button link @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
        <el-button link type="warning" :loading="checkingRobotsId === row.id" @click="checkRobots(row)">协议检测</el-button>
        <el-button v-if="!robotsOverrideActive(row)" link type="warning" @click="openRobotsOverride(row)">忽略检测</el-button>
        <el-button v-else link type="danger" @click="cancelRobotsOverride(row)">取消忽略</el-button>
        <el-button link type="success" :disabled="!row.enabled" :loading="collectingId === row.id" @click="collect(row)">立即采集</el-button>
        <el-button link type="danger" :disabled="row.collectedRecords > 0" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>

    <div class="news-pagination">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="pageSizes"
        :total="filteredSources.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="resetPage"
      />
    </div>

    <el-dialog v-model="dialogOpen" :title="form.id ? '编辑资讯源' : '新增资讯源'" width="900px" destroy-on-close>
      <el-form label-position="top" class="news-form">
        <div class="news-form-grid">
          <el-form-item label="来源编码"><el-input v-model.trim="form.sourceCode" /></el-form-item>
          <el-form-item label="来源名称"><el-input v-model.trim="form.sourceName" /></el-form-item>
          <el-form-item label="来源类型"><el-select v-model="form.sourceType"><el-option label="交易所首页（内置）" value="EXCHANGE_HOME" disabled /><el-option label="深交所首页（内置）" value="SZSE_HOME" disabled /><el-option label="资讯首页（内置）" value="NEWS_HOME" disabled /><el-option label="巨潮资讯首页（内置）" value="CNINFO_HOME" disabled /><el-option label="财联社电报（内置）" value="CLS_TELEGRAPH" disabled /><el-option label="巨潮公告（内置）" value="CNINFO_ANNOUNCEMENTS" disabled /><el-option label="上交所公告（内置）" value="SSE_ANNOUNCEMENTS" disabled /><el-option label="网页列表" value="WEB_LIST" /><el-option label="固定网页" value="WEB_SINGLE_PAGE" /><el-option label="RSS/Atom" value="RSS" /><el-option label="JSON API" value="API" /></el-select></el-form-item>
          <el-form-item class="wide" label="入口地址"><el-input v-model.trim="form.entryUrl" /></el-form-item>
          <el-form-item label="允许域名"><el-input v-model.trim="form.allowedDomain" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item label="请求间隔(ms)"><el-input-number v-model="form.configuration.sleepMillis" :min="0" :max="60000" /></el-form-item>
          <el-form-item label="超时(ms)"><el-input-number v-model="form.configuration.timeoutMillis" :min="1000" :max="120000" /></el-form-item>
        </div>
        <el-divider content-position="left">采集日历</el-divider>
        <section class="schedule-builder">
          <el-radio-group v-model="scheduleEditor.mode" class="schedule-mode" @change="applyVisualSchedule">
            <el-radio-button label="interval">固定间隔</el-radio-button>
            <el-radio-button label="daily">每天</el-radio-button>
            <el-radio-button label="weekly">每周</el-radio-button>
            <el-radio-button label="monthly">每月</el-radio-button>
            <el-radio-button label="advanced">高级 Cron</el-radio-button>
          </el-radio-group>

          <div v-if="scheduleEditor.mode === 'interval'" class="schedule-config-row">
            <span>每隔</span>
            <el-select v-model="scheduleEditor.intervalCron" class="schedule-interval-select" @change="applyVisualSchedule">
              <el-option v-for="option in intervalOptions" :key="option.cron" :label="option.label" :value="option.cron" />
            </el-select>
            <span>执行一次</span>
          </div>

          <div v-else-if="scheduleEditor.mode === 'daily'" class="schedule-config-row">
            <span>每天</span>
            <el-time-picker v-model="scheduleEditor.time" format="HH:mm" value-format="HH:mm" :clearable="false" @change="applyVisualSchedule" />
            <span>开始采集</span>
          </div>

          <div v-else-if="scheduleEditor.mode === 'weekly'" class="schedule-calendar-block">
            <span class="schedule-field-label">选择星期</span>
            <el-checkbox-group v-model="scheduleEditor.weekdays" class="weekday-picker" @change="applyVisualSchedule">
              <el-checkbox-button v-for="day in weekdayOptions" :key="day.value" :label="day.value">{{ day.label }}</el-checkbox-button>
            </el-checkbox-group>
            <div class="schedule-config-row">
              <span>执行时间</span>
              <el-time-picker v-model="scheduleEditor.time" format="HH:mm" value-format="HH:mm" :clearable="false" @change="applyVisualSchedule" />
            </div>
          </div>

          <div v-else-if="scheduleEditor.mode === 'monthly'" class="schedule-calendar-block">
            <span class="schedule-field-label">选择每月日期</span>
            <div class="monthday-picker">
              <button
                v-for="day in monthDayOptions"
                :key="day.value"
                type="button"
                :class="{ active: scheduleEditor.monthDays.includes(day.value) }"
                @click="toggleMonthDay(day.value)"
              >{{ day.label }}</button>
            </div>
            <div class="schedule-config-row">
              <span>执行时间</span>
              <el-time-picker v-model="scheduleEditor.time" format="HH:mm" value-format="HH:mm" :clearable="false" @change="applyVisualSchedule" />
            </div>
          </div>

          <el-input
            v-else
            v-model.trim="form.scheduleCron"
            placeholder="例如：0 */10 * * * *"
          >
            <template #prepend>Cron</template>
          </el-input>

          <div class="schedule-preview">
            <strong>{{ describeCron(form.scheduleCron) }}</strong>
            <code>{{ form.scheduleCron }}</code>
            <span>时区：{{ form.configuration.zoneId || 'Asia/Shanghai' }}</span>
          </div>
        </section>
        <el-divider v-if="['WEB_LIST','WEB_SINGLE_PAGE'].includes(form.sourceType)" content-position="left">网页抽取规则</el-divider>
        <div v-if="['WEB_LIST','WEB_SINGLE_PAGE'].includes(form.sourceType)" class="news-form-grid">
          <el-form-item v-if="form.sourceType === 'WEB_LIST'" class="wide" label="详情链接选择器"><el-input v-model.trim="form.rule.linkSelector" /></el-form-item>
          <el-form-item label="标题选择器">
            <el-select v-model="form.rule.titleSelector" filterable allow-create default-first-option clearable placeholder="选择常用项或输入 CSS Selector">
              <el-option v-for="option in selectorPresets.title" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="发布时间选择器">
            <el-select v-model="form.rule.publishTimeSelector" filterable allow-create default-first-option clearable placeholder="选择常用项或输入 CSS Selector">
              <el-option v-for="option in selectorPresets.publishTime" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="作者/来源选择器">
            <el-select v-model="form.rule.authorSelector" filterable allow-create default-first-option clearable placeholder="选择常用项或输入 CSS Selector">
              <el-option v-for="option in selectorPresets.author" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item class="wide" label="正文选择器">
            <el-select v-model="form.rule.contentSelector" filterable allow-create default-first-option clearable placeholder="选择常用项或输入 CSS Selector">
              <el-option v-for="option in selectorPresets.content" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item class="wide" label="详情页 URL 正则（可选模板或自定义）">
            <el-select
              v-model="form.rule.urlPattern"
              filterable
              allow-create
              default-first-option
              clearable
              placeholder="选择内置模板，或直接输入 Java 正则表达式"
            >
              <el-option
                v-for="preset in patternPresets"
                :key="preset.code"
                :label="preset.name"
                :value="preset.regex"
              >
                <div class="pattern-option"><strong>{{ preset.name }}</strong><small>{{ preset.description }}</small></div>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item class="wide" label="附件链接选择器（可留空自动识别 PDF/Word/Excel/CSV）">
            <el-select v-model="form.configuration.attachmentSelector" filterable allow-create default-first-option clearable placeholder="选择常用项、输入 CSS Selector，或留空自动识别">
              <el-option v-for="option in selectorPresets.attachment" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item class="wide" label="附件额外允许域名"><el-input v-model.trim="form.configuration.attachmentAllowedDomains" placeholder="多个域名用逗号分隔；默认允许资讯源域名及其子域名" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="robotsOverrideDialogOpen" title="临时忽略 robots.txt 检测" width="620px" destroy-on-close>
      <el-alert
        title="此操作不会关闭检测：系统仍会保留原始检测结果，但会在有效期内允许该资讯源继续采集。robots.txt 豁免不代表已获得版权、数据使用或网站条款授权。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" class="news-form robots-override-form">
        <el-form-item label="忽略原因（必填）">
          <el-input v-model="robotsOverrideForm.reason" type="textarea" :rows="5" maxlength="500" show-word-limit placeholder="请填写授权依据、业务负责人、临时处置背景或后续处理计划，至少 10 个字符。" />
        </el-form-item>
        <el-form-item label="有效时长">
          <el-input-number v-model="robotsOverrideForm.hours" :min="1" :max="168" />
          <span style="margin-left: 8px">小时（最长 7 天，到期自动恢复强制拦截）</span>
        </el-form-item>
        <el-checkbox v-model="robotsOverrideForm.acknowledged">我已确认具备相应授权，并理解该操作不能替代网站条款、版权及法律审查。</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="robotsOverrideDialogOpen = false">取消</el-button>
        <el-button type="warning" :loading="robotsOverrideSaving" @click="saveRobotsOverride">确认临时忽略</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialogOpen" title="采集日志" width="1100px" destroy-on-close>
      <div class="news-log-toolbar">
        <el-select v-model="logSourceId" clearable placeholder="全部资讯源" @change="reloadLogs">
          <el-option v-for="source in sources" :key="source.id" :label="source.sourceName" :value="source.id" />
        </el-select>
        <el-button :loading="logsLoading" @click="loadLogs"><el-icon><Refresh /></el-icon>刷新日志</el-button>
      </div>
      <el-table v-loading="logsLoading" :data="logs" border stripe empty-text="暂无采集日志" max-height="520">
        <el-table-column prop="sourceName" label="资讯源" min-width="150" />
        <el-table-column prop="sourceUrl" label="来源地址" min-width="280" show-overflow-tooltip />
        <el-table-column label="采集状态" width="110"><template #default="{ row }"><el-tag :type="recordStatusType(row.collectStatus)">{{ recordStatusLabel(row.collectStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="分析状态" width="110"><template #default="{ row }">{{ analysisStatusLabel(row.analysisStatus) }}</template></el-table-column>
        <el-table-column label="采集时间" width="180"><template #default="{ row }">{{ formatTime(row.collectedAt) }}</template></el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
      </el-table>
      <div class="news-pagination news-log-pagination">
        <el-pagination
          v-model:current-page="logPage"
          v-model:page-size="logPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="logTotal"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadLogs"
          @size-change="reloadLogs"
        />
      </div>
      <template #footer><el-button @click="logDialogOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
<script src="../scripts/views/NewsCollectionView.js"></script>
