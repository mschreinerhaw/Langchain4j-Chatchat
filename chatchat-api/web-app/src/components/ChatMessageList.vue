<template>
  <section class="message-panel" aria-live="polite">
    <article
      v-for="message in messages"
      :key="message.id"
      class="chat-message"
      :class="[
        message.role,
        {
          streaming: message.streaming,
          executing: isExecutionRunning(message),
          'streaming-has-content': message.streaming && message.content,
        },
      ]"
    >
      <div
        class="message-avatar"
        :title="message.role === 'user' ? displayUserId : assistantName(message)"
        :aria-label="message.role === 'user' ? displayUserId : assistantName(message)"
        >{{ message.role === "user" ? userAvatarLabel : runtimeAvatarLabel(message) }}</div
      >
      <div class="message-bubble">
        <div class="message-meta">
          <strong>{{ message.role === "user" ? displayUserId : assistantName(message) }}</strong>
          <div class="message-actions">
            <time>{{ formatTime(message.timestamp) }}</time>
            <button
              v-if="messageHasRenderableContent(message)"
              type="button"
              class="message-copy-button"
              :data-copied="copiedMessageId === message.id ? 'true' : undefined"
              :title="copiedMessageId === message.id ? 'Copied' : 'Copy answer'"
              :aria-label="copiedMessageId === message.id ? 'Copied answer' : 'Copy answer content'"
              @click="copyMessage(message)"
            >
              <Check v-if="copiedMessageId === message.id" :size="14" stroke-width="2.4" />
              <Copy v-else :size="14" stroke-width="2.2" />
            </button>
            <button
              v-if="allowMessageDelete && message.role === 'assistant' && !message.streaming"
              type="button"
              class="message-delete-button"
              title="删除这条回答"
              aria-label="删除这条回答"
              @click="$emit('delete-message', message)"
            >
              <Trash2 :size="14" stroke-width="2.2" />
            </button>
          </div>
        </div>
        <template v-if="shouldShowSteps(message) || (message.role === 'assistant' && message.streaming)">
          <section v-for="workbench in [executionWorkbench(message)]" :key="`workbench-${message.id}`"
            class="runtime-execution-panel task-workbench" :class="[workbench.status, {collapsed: !executionStepsExpanded(message)}]"
            aria-label="任务执行工作台">
            <header class="workbench-header">
              <span class="workbench-emblem" aria-hidden="true">↗</span>
              <div class="workbench-heading"><h3>{{ workbenchTitle(message) }}</h3><small>{{ runtimeRunId(message) }}</small></div>
              <span class="workbench-status" role="status" aria-live="polite">{{ workbench.statusLabel }}</span>
              <button v-if="executionStepsCollapsible(message)" type="button" class="runtime-collapse-button"
                :aria-expanded="executionStepsExpanded(message).toString()" aria-label="展开或收起执行工作台"
                @click="toggleExecutionSteps(message)"><ChevronDown v-if="executionStepsExpanded(message)" :size="16"/><ChevronRight v-else :size="16"/></button>
            </header>
            <dl class="workbench-metrics">
              <div><dd>{{ workbench.completedLabel }}</dd><dt>已完成阶段</dt></div>
              <div><dd>{{ workbench.elapsedLabel }}</dd><dt>累计运行</dt></div>
              <div><dd>{{ workbench.completedOperations }}</dd><dt>已完成操作</dt></div>
            </dl>
            <div class="workbench-phase-heading"><strong>当前阶段：{{ workbench.phaseTitle }}</strong><span>第 {{ workbench.current }} 阶段，共 {{ workbench.total }} 阶段</span></div>
            <ol class="workbench-phase-strip" aria-label="业务阶段状态">
              <li v-for="phase in workbench.steps" :key="phase.id" :class="phase.status"
                :title="`${phase.title} · ${executionStatusLabel(phase.status)}${phase.detail ? '：'+phase.detail : ''}`"><span class="sr-only">{{ phase.number }}/{{ phase.total }} {{ phase.title }} · {{ executionStatusLabel(phase.status) }}</span></li>
            </ol>
            <div v-show="executionStepsExpanded(message)" class="workbench-details">
              <div v-if="workbench.alerts.length || workbench.reason" class="workbench-alerts" role="status">
                <p v-if="workbench.reason">{{ workbench.reason }}</p><p v-for="alert in workbench.alerts" :key="alert">{{ alert }}</p>
              </div>
              <section class="workbench-activity" aria-label="实时执行动态">
                <header><h4><span class="workbench-live-dot" :class="workbench.status" aria-hidden="true"></span>实时执行动态</h4><small>最新活动：{{ workbench.latestActivityLabel }}</small></header>
                <p class="workbench-current"><strong>{{ workbench.status === 'unconfirmed' ? '最后已知活动：' : '' }}{{ workbench.currentActivity }}</strong><small>{{ workbench.transportLabel }} · {{ workbench.currentEvidenceLabel }}</small></p>
                <ol v-if="workbench.activities.length" class="workbench-activity-list">
                  <li v-for="operation in workbench.activities" :key="operation.id" :class="operation.state">
                    <span class="workbench-operation-dot" aria-hidden="true"></span>
                    <div><strong>{{ operation.title }}</strong><small>{{ operation.detail }}</small><span class="workbench-operation-time">{{ operation.timeLabel }} · {{ operation.durationLabel }}</span></div>
                    <em>{{ operation.statusLabel }}</em>
                  </li>
                </ol>
                <p v-else class="workbench-empty">尚未收到可确认的独立操作事件；不会根据等待时长推算工作进展。</p>
              </section>
              <section class="workbench-outcomes" aria-label="阶段成果">
                <header><h4>阶段成果</h4><small>仅确认有完成证据的阶段</small></header>
                <ol class="workbench-outcome-grid">
                  <li v-for="phase in workbench.steps" :key="phase.id" :class="phase.status">
                    <span class="workbench-phase-number">{{ phase.number }}</span><div><strong>{{ phase.title }}</strong><small>{{ executionStatusLabel(phase.status) }}</small><p v-if="phase.detail">{{ phase.detail }}</p></div>
                  </li>
                </ol>
              </section>
              <details class="workbench-diagnostics"><summary>查看耗时与运行依据</summary>
                <dl class="workbench-timings"><div v-for="timing in workbench.timings" :key="timing.kind"><dt>{{ timing.label }}</dt><dd>{{ timing.durationLabel }}</dd></div></dl>
                <p>分类时长按已记录的开始、结束事件计算，同类并行区间去重；分类之间可能重叠，不相加为累计运行时间。执行观察时长从首次确认状态起算。</p>
                <p>最近状态同步：{{ workbench.lastHeartbeatLabel }}。连接正常仅代表状态通道可用，不证明底层操作已完成。</p>
                <p>页面长时间提醒阈值：{{ workbench.warningThresholdLabel }}；该提醒不会改变任务终态或发起重试。</p>
                <ol><li v-for="operation in workbench.allActivities" :key="operation.id"><code>{{ operation.operationId }}</code><span>事件序号 {{ operation.sequence }} · {{ operation.timeLabel }} · {{ operation.statusLabel }}</span></li></ol>
              </details>
            </div>
          </section>
        </template>
        <EnterpriseUiArtifactRenderer
          v-if="message.role === 'assistant' && !message.streaming && messageUiArtifact(message)"
          :artifact="messageUiArtifact(message)"
          @drill-down="handleVisualizationDrillDown(message, $event)"
          :preferences="visualizationPreferences(message)"
          @preference-change="handleVisualizationPreference(message, $event)"
          @table-chart="openChartAnalysisModal($event, message)"
        />
        <PythonVisualReport
          v-else-if="
            message.role === 'assistant' && !message.streaming && messageVisualReport(message)
          "
          :report="messageVisualReport(message)"
          :fallback-markdown="message.content || ''"
        />
        <ReportMarkdown
          v-else-if="messageHasRenderableContent(message)"
          class="message-markdown"
          :content="message.content || ''"
          :collapse-thinking="message.role === 'assistant'"
          :render-markdown="
            (content) => renderMarkdown(content, message, content !== String(message.content || ''))
          "
          @drill-down="handleVisualizationDrillDown(message, $event)"
          :preferences="visualizationPreferences(message)"
          @preference-change="handleVisualizationPreference(message, $event)"
          @click="handleMarkdownClick($event, message)"
        />
        <section
          v-if="
            message.role === 'assistant' &&
            !message.streaming &&
            metadataTableCatalog(message).rows.length
          "
          class="metadata-catalog-section"
          :class="{ expanded: metadataCatalogExpanded(message) }"
        >
          <header class="metadata-catalog-toggle-header">
            <button
              type="button"
              :aria-expanded="metadataCatalogExpanded(message).toString()"
              @click="toggleMetadataCatalog(message)"
            >
              <span class="metadata-catalog-heading">
                <ChevronDown v-if="metadataCatalogExpanded(message)" :size="14" />
                <ChevronRight v-else :size="14" />
                <strong>工具命中的物理表</strong>
              </span>
              <small>
                共命中 {{ metadataTableCatalog(message).totalMatched }} 张，当前展示
                {{ metadataTableCatalog(message).rows.length }} 张
                <template v-if="metadataTableCatalog(message).catalogTruncated"
                  >，结果已截断</template
                >
              </small>
            </button>
          </header>
          <div v-show="metadataCatalogExpanded(message)" class="metadata-catalog-table-scroll">
            <table class="metadata-catalog-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>数据库</th>
                  <th>Schema</th>
                  <th>物理表名</th>
                  <th>表说明</th>
                  <th>匹配分</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in metadataTableCatalog(message).rows" :key="row.id">
                  <td>{{ row.index }}</td>
                  <td>{{ row.database || "-" }}</td>
                  <td>{{ row.schema || "-" }}</td>
                  <td
                    ><code>{{ row.tableName }}</code></td
                  >
                  <td>{{ row.tableComment || "-" }}</td>
                  <td>{{ row.score || "-" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <div
          v-if="
            message.role === 'assistant' &&
            !message.streaming &&
            metadataColumnSections(message).length
          "
          class="metadata-column-sections"
        >
          <section
            v-for="section in metadataColumnSections(message)"
            :key="section.id"
            class="metadata-column-section"
          >
            <header>
              <div>
                <strong>{{ section.title }}</strong>
                <small v-if="section.comment">{{ section.comment }}</small>
              </div>
              <span>{{ section.columns.length }} 个字段</span>
            </header>
            <div class="metadata-column-table-scroll">
              <table class="metadata-column-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>真实字段名</th>
                    <th>类型</th>
                    <th>键</th>
                    <th>可空</th>
                    <th>字段说明</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="column in section.columns" :key="column.id">
                    <td>{{ column.ordinal }}</td>
                    <td
                      ><code>{{ column.name }}</code></td
                    >
                    <td>{{ column.type || "-" }}</td>
                    <td>{{ column.key || "-" }}</td>
                    <td>{{ column.nullable }}</td>
                    <td>{{ column.comment || "-" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
        <details
          v-if="
            message.role === 'assistant' &&
            messageVisualizationSpec(message) &&
            !message.streaming &&
            !hasComposedAnalysisReport(message) &&
            isCollapsibleRawDataVisualization(messageVisualizationSpec(message))
          "
          class="supporting-dataset-attachment"
          :open="messageVisualizationSpec(message)?.ui?.defaultCollapsed === false"
        >
          <summary>{{
            rawDataVisualizationToggleLabel(messageVisualizationSpec(message))
          }}</summary>
          <VisualizationRenderer
            :spec="messageVisualizationSpec(message)"
            :preference="visualizationPreference(message, 'attachment')"
            @drill-down="handleVisualizationDrillDown(message, $event)"
            @preference-change="
              handleVisualizationPreference(message, { slot: 'attachment', preference: $event })
            "
          />
        </details>
        <VisualizationRenderer
          v-else-if="
            message.role === 'assistant' &&
            messageVisualizationSpec(message) &&
            !message.streaming &&
            !hasComposedAnalysisReport(message)
          "
          :spec="messageVisualizationSpec(message)"
          :preference="visualizationPreference(message, 'attachment')"
          @drill-down="handleVisualizationDrillDown(message, $event)"
          @preference-change="
            handleVisualizationPreference(message, { slot: 'attachment', preference: $event })
          "
        />
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
        <ResponseReferences
          v-if="
            message.role === 'assistant' &&
            !message.streaming &&
            !isExecutionRunning(message) &&
            message.status !== 'waiting'
          "
          :sources="message.sources || []"
          :citations="[...(message.uiResponse?.citations || []), ...(message.citations || [])]"
          :evidence-premises="message.evidencePremises || []"
          :tool-traces="message.traces || []"
          :conflicts="
            message.evidenceConflicts ||
            message.evidenceManifest?.conflicts ||
            message.uiResponse?.evidenceConflicts ||
            []
          "
          :knowledge-context="message.knowledgeContext || {}"
          compact
        />
        <div
          v-if="canShowEvaluation(message) || canExportMessagePdf(message)"
          class="message-feedback"
          aria-label="回答操作"
        >
          <template v-if="canShowEvaluation(message)">
            <button
              v-for="option in feedbackOptions"
              :key="option.value"
              type="button"
              class="message-feedback-button"
              :class="{ unresolved: option.value === 'unresolved' }"
              :disabled="message.feedbackSubmitting"
              :title="`评价为${option.label}`"
              @click="$emit('feedback', { message, action: option.value })"
            >
              <CircleX v-if="option.value === 'unresolved'" :size="16" stroke-width="2.2" />
              <CircleCheck v-else :size="16" stroke-width="2.2" />
              <span>{{ option.label }}</span>
            </button>
          </template>
          <button
            v-if="canExportMessagePdf(message)"
            type="button"
            class="message-feedback-button"
            :disabled="exportingPdfMessageId === message.id"
            :title="exportingPdfMessageId === message.id ? '正在生成PDF' : '导出当前回答为PDF'"
            @click="exportMessagePdf(message, $event)"
          >
            <FileDown :size="16" stroke-width="2.2" />
            <span>{{ exportingPdfMessageId === message.id ? "导出中" : "导出PDF" }}</span>
          </button>
        </div>
        <p v-if="message.feedbackTime" class="message-feedback-done">感谢评价</p>
        <p v-if="message.feedbackError" class="message-feedback-error">{{
          message.feedbackError
        }}</p>
        <p v-if="pdfExportErrorMessageId === message.id" class="message-feedback-error">
          PDF 导出失败，请稍后重试
        </p>
      </div>
    </article>

    <article v-if="loading && !hasStreamingMessage && !hasTaskProgressMessage" class="chat-message assistant thinking-message">
      <div class="message-avatar">助手</div>
      <div class="message-bubble loading-bubble" aria-label="灵动智策正在启动任务">
        <div class="thinking-brief" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div class="thinking-copy">
          <strong>1/5 接收任务</strong>
          <small>任务已提交，正在等待执行状态。</small>
        </div>
      </div>
    </article>

    <div v-if="reasoningModal" class="reasoning-modal-backdrop" role="presentation">
      <section class="reasoning-modal" role="dialog" aria-modal="true" aria-label="Reasoning path">
        <header>
          <div>
            <span>Reasoning Path</span>
            <h2>{{ reasoningModal.title }}</h2>
          </div>
          <button
            type="button"
            class="app-dialog-close"
            aria-label="关闭"
            title="关闭"
            @click="closeReasoningModal"
            >×</button
          >
        </header>

        <div class="reasoning-modal-metrics">
          <span v-for="metric in reasoningModal.metrics" :key="metric.label">
            {{ metric.label }} <b>{{ metric.value }}</b>
          </span>
        </div>

        <section class="reasoning-modal-section">
          <strong>Selected path</strong>
          <div class="reasoning-modal-path">
            <span v-for="(node, index) in reasoningModal.pathNodes" :key="node.id">
              <b>{{ node.id }}</b>
              <small>{{ node.confidence }}</small>
              <i v-if="index < reasoningModal.pathNodes.length - 1">-&gt;</i>
            </span>
          </div>
        </section>

        <section class="reasoning-modal-section">
          <strong>Path edges</strong>
          <ul>
            <li
              v-for="edge in reasoningModal.pathEdges"
              :key="`${edge.from}-${edge.to}-${edge.type}`"
            >
              <span>{{ edge.from }} -> {{ edge.to }}</span>
              <small>{{ edge.type }} / {{ edge.confidence }}</small>
              <p v-if="edge.reasoning">{{ edge.reasoning }}</p>
            </li>
            <li v-if="!reasoningModal.pathEdges.length" class="empty">No path edge available.</li>
          </ul>
        </section>

        <section class="reasoning-modal-section">
          <strong>Conflict resolution</strong>
          <ul>
            <li v-for="item in reasoningModal.conflicts" :key="item.edge">
              <span>{{ item.edge }}</span>
              <small>{{ item.confidence }}</small>
              <p>{{ item.decision }}</p>
            </li>
            <li v-if="!reasoningModal.conflicts.length" class="empty"
              >No conflict evidence participated in the selected path.</li
            >
          </ul>
        </section>

        <section class="reasoning-modal-section">
          <strong>Decision trace</strong>
          <ul>
            <li v-for="item in reasoningModal.explanation" :key="item">{{ item }}</li>
          </ul>
        </section>
      </section>
    </div>

    <div
      v-if="chartAnalysisModal"
      class="result-chart-modal-backdrop"
      :class="{ floating: chartAnalysisFloating, fullscreen: chartAnalysisFullscreen }"
      role="presentation"
    >
      <section
        class="result-chart-modal"
        :class="{ floating: chartAnalysisFloating, fullscreen: chartAnalysisFullscreen }"
        role="dialog"
        :aria-modal="(!chartAnalysisFloating).toString()"
        aria-label="查询结果图形分析"
        :style="chartAnalysisWindowStyle()"
      >
        <header
          :class="{ draggable: chartAnalysisFloating && !chartAnalysisFullscreen }"
          @pointerdown="startChartAnalysisDrag"
        >
          <div>
            <span>图形分析</span>
            <h2>{{ chartAnalysisActiveDataset()?.title || chartAnalysisModal.title }}</h2>
          </div>
          <div class="result-chart-window-actions" aria-label="图形分析窗口操作">
            <button
              type="button"
              :class="{ active: chartAnalysisFloating }"
              :title="chartAnalysisFloating ? '停靠窗口' : '浮动窗口'"
              :aria-label="chartAnalysisFloating ? '停靠窗口' : '浮动窗口'"
              @click="toggleChartAnalysisFloating"
            >
              <PinOff v-if="chartAnalysisFloating" :size="16" />
              <Pin v-else :size="16" />
            </button>
            <button
              type="button"
              :title="chartAnalysisFullscreen ? '退出全屏' : '全屏'"
              :aria-label="chartAnalysisFullscreen ? '退出全屏' : '全屏'"
              @click="toggleChartAnalysisFullscreen"
            >
              <Minimize2 v-if="chartAnalysisFullscreen" :size="16" />
              <Maximize2 v-else :size="16" />
            </button>
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeChartAnalysisModal"
            >
              <X :size="18" />
            </button>
          </div>
        </header>

        <nav
          v-if="chartAnalysisDatasetCount() > 1"
          class="result-chart-dataset-tabs"
          aria-label="数据集切换"
        >
          <button
            v-for="dataset in chartAnalysisModal.datasets"
            :key="dataset.id"
            type="button"
            :class="{ active: dataset.id === chartAnalysisModal.activeDatasetId }"
            @click="setChartAnalysisDataset(dataset.id)"
          >
            <span>{{ dataset.title }}</span>
            <small>{{ dataset.rows.length }} 行 / {{ dataset.columns.length }} 个字段</small>
          </button>
        </nav>

        <section class="result-chart-collapsible" :class="{ open: chartAnalysisSettingsOpen }">
          <button
            type="button"
            class="result-chart-collapsible-toggle"
            @click="toggleChartAnalysisSettings"
          >
            <span>
              <ChevronDown v-if="chartAnalysisSettingsOpen" :size="16" />
              <ChevronRight v-else :size="16" />
              图表设置
            </span>
            <small>{{ chartAnalysisSemanticSummary() }}</small>
          </button>
          <div v-if="chartAnalysisSettingsOpen" class="result-chart-controls">
            <label>
              <span>图表类型</span>
              <select
                :value="chartAnalysisActiveDataset()?.chartType"
                @change="setChartField('chartType', $event.target.value)"
              >
                <option
                  v-for="option in chartTypeOptions()"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              <span>X 轴 / 维度</span>
              <select
                :value="chartAnalysisActiveDataset()?.xKey"
                @change="setChartField('xKey', $event.target.value)"
              >
                <option
                  v-for="column in chartAnalysisActiveDataset()?.columns || []"
                  :key="column"
                  :value="column"
                >
                  {{ column }}
                </option>
              </select>
            </label>
            <label>
              <span>Y 轴 / 指标</span>
              <select
                :value="chartAnalysisActiveDataset()?.yKey"
                @change="setChartField('yKey', $event.target.value)"
              >
                <option
                  v-for="column in chartAnalysisActiveDataset()?.columns || []"
                  :key="column"
                  :value="column"
                >
                  {{ column }}
                </option>
              </select>
            </label>
            <label>
              <span>分组字段</span>
              <select
                :value="chartAnalysisActiveDataset()?.groupKey"
                @change="setChartField('groupKey', $event.target.value)"
              >
                <option value="">不分组</option>
                <option
                  v-for="column in chartAnalysisActiveDataset()?.columns || []"
                  :key="column"
                  :value="column"
                >
                  {{ column }}
                </option>
              </select>
            </label>
          </div>
        </section>

        <section
          class="result-chart-collapsible result-chart-column-picker"
          :class="{ open: chartAnalysisColumnsOpen }"
          aria-label="下钻数据列"
        >
          <button
            type="button"
            class="result-chart-collapsible-toggle"
            @click="toggleChartAnalysisColumnsPanel"
          >
            <span>
              <ChevronDown v-if="chartAnalysisColumnsOpen" :size="16" />
              <ChevronRight v-else :size="16" />
              下钻数据列
            </span>
            <small
              >已选 {{ chartAnalysisActiveDataset()?.selectedColumns?.length || 0 }} /
              {{ chartAnalysisActiveDataset()?.columns?.length || 0 }}</small
            >
          </button>
          <div v-if="chartAnalysisColumnsOpen" class="result-chart-column-body">
            <div class="result-chart-column-actions">
              <button type="button" @click="selectAllChartAnalysisColumns">全选</button>
              <button type="button" @click="clearChartAnalysisColumns">清空</button>
            </div>
            <div class="result-chart-column-list">
              <label v-for="column in chartAnalysisActiveDataset()?.columns || []" :key="column">
                <input
                  type="checkbox"
                  :checked="chartAnalysisActiveDataset()?.selectedColumns?.includes(column)"
                  @change="toggleChartAnalysisColumn(column)"
                />
                <span>{{ column }}</span>
              </label>
            </div>
          </div>
        </section>
        <p
          v-if="chartAnalysisSemanticSummary() && chartAnalysisSettingsOpen"
          class="result-chart-semantics"
        >
          {{ chartAnalysisSemanticSummary() }}
        </p>

        <VisualizationRenderer
          v-if="chartAnalysisSpec()"
          :spec="chartAnalysisSpec()"
          :preference="{
            view: chartAnalysisActiveDataset()?.view,
            chartType: chartAnalysisActiveDataset()?.chartType,
          }"
          @drill-down="handleChartAnalysisDrillDown"
          @preference-change="handleChartAnalysisPreference"
        />

        <footer>
          <span
            >{{ chartAnalysisActiveDataset()?.rows?.length || 0 }} 行，{{
              chartAnalysisActiveDataset()?.columns?.length || 0
            }}
            个字段</span
          >
          <button
            type="button"
            :disabled="!chartAnalysisActiveDataset()?.selectedColumns?.length"
            @click="drillDownChartAnalysis"
            >下钻分析</button
          >
          <button type="button" @click="closeChartAnalysisModal">关闭</button>
        </footer>
        <span class="result-chart-resize-grip" aria-hidden="true"></span>
      </section>
    </div>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
