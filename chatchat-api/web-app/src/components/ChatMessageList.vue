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
          'streaming-has-content': message.streaming && message.content
        }
      ]"
    >
      <div
        class="message-avatar"
        :title="message.role === 'user' ? displayUserId : assistantName(message)"
        :aria-label="message.role === 'user' ? displayUserId : assistantName(message)"
      >{{ message.role === "user" ? userAvatarLabel : runtimeAvatarLabel(message) }}</div>
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
          </div>
        </div>
        <section
          v-if="shouldShowSteps(message) || (message.role === 'assistant' && message.streaming)"
          class="runtime-execution-panel"
          :class="{ compact: !!message.content, running: isExecutionRunning(message) || message.streaming, completed: runtimeStatusLabel(message) === '完成' }"
        >
          <header class="runtime-execution-header">
            <div class="runtime-run-mark" aria-hidden="true">
              <span></span>
            </div>
            <div class="runtime-run-title">
              <span>整体过程</span>
              <strong>{{ runtimeRunId(message) }}</strong>
            </div>
            <div class="runtime-run-meta">
              <small v-if="runtimeElapsed(message)">{{ runtimeElapsed(message) }}</small>
              <b>{{ runtimeStatusLabel(message) }}</b>
            </div>
          </header>
          <div class="runtime-progress-track" aria-hidden="true">
            <span :style="{ width: `${runtimeProgress(message)}%` }"></span>
          </div>
        </section>
        <section
          v-if="message.role === 'assistant' && runtimeToolCalls(message).length"
          class="runtime-tool-timeline"
          aria-label="工具调用过程"
        >
          <header>
            <button
              type="button"
              :aria-expanded="toolCallsExpanded(message).toString()"
              @click="toggleToolCalls(message)"
            >
              <span class="runtime-tool-heading">
                <ChevronDown v-if="toolCallsExpanded(message)" :size="15" />
                <ChevronRight v-else :size="15" />
                <strong>工具调用</strong>
              </span>
              <small
                class="runtime-tool-summary-status"
                :class="runtimeToolStatusClass(message)"
                aria-live="polite"
              >
                <i aria-hidden="true"></i>
                {{ runtimeToolStatusLabel(message) }}
              </small>
            </button>
          </header>
          <ol v-show="toolCallsExpanded(message)">
            <li
              v-for="call in runtimeToolCalls(message)"
              :key="call.id"
              :class="{ done: toolCallDone(call), failed: toolCallFailed(call), active: !toolCallDone(call) && !toolCallFailed(call) }"
            >
              <span class="runtime-tool-status" aria-hidden="true">
                <Check v-if="toolCallDone(call)" :size="14" stroke-width="2.6" />
                <CircleX v-else-if="toolCallFailed(call)" :size="14" stroke-width="2.4" />
                <i v-else></i>
              </span>
              <div>
                <code>{{ call.name }}</code>
                <small v-if="call.detail">{{ call.detail }}</small>
              </div>
              <em>{{ call.latencyMs ? `${call.latencyMs}ms` : (toolCallDone(call) ? '完成' : toolCallFailed(call) ? '失败' : '运行中') }}</em>
            </li>
          </ol>
        </section>
        <div
          v-if="messageHasRenderableContent(message)"
          class="message-markdown"
          v-html="renderMarkdown(message.content, message)"
          @click="handleMarkdownClick"
        ></div>
        <section
          v-if="message.role === 'assistant' && !message.streaming && metadataTableCatalog(message).rows.length"
          class="metadata-catalog-section"
        >
          <header>
            <div>
              <strong>工具命中的物理表</strong>
              <small>
                共命中 {{ metadataTableCatalog(message).totalMatched }} 张，当前展示 {{ metadataTableCatalog(message).rows.length }} 张
                <template v-if="metadataTableCatalog(message).catalogTruncated">，结果已截断</template>
              </small>
            </div>
          </header>
          <div class="metadata-catalog-table-scroll">
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
                  <td><code>{{ row.tableName }}</code></td>
                  <td>{{ row.tableComment || "-" }}</td>
                  <td>{{ row.score || "-" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <div
          v-if="message.role === 'assistant' && !message.streaming && metadataColumnSections(message).length"
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
                    <td><code>{{ column.name }}</code></td>
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
        <VisualizationRenderer
          v-if="message.role === 'assistant' && message.visualizationSpec && !message.streaming"
          :spec="message.visualizationSpec"
          @drill-down="handleVisualizationDrillDown(message, $event)"
        />
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
        <ResponseReferences
          v-if="message.role === 'assistant' && !message.streaming && !isExecutionRunning(message) && message.status !== 'waiting'"
          :sources="message.sources || []"
          :evidence-premises="message.evidencePremises || []"
          :tool-traces="message.traces || []"
          compact
        />
        <div v-if="canShowEvaluation(message)" class="message-feedback" aria-label="回答评价">
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
        </div>
        <p v-else-if="message.feedbackTime" class="message-feedback-done">感谢评价</p>
        <p v-if="message.feedbackError" class="message-feedback-error">{{ message.feedbackError }}</p>
      </div>
    </article>

    <article v-if="loading && !hasStreamingMessage" class="chat-message assistant thinking-message">
      <div class="message-avatar">RUN</div>
      <div class="message-bubble loading-bubble" aria-label="LiveRuntime is starting a run">
        <div class="thinking-brief" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div class="thinking-copy">
          <strong>LiveRuntime is preparing the run</strong>
          <small>Planning, binding tools, and waiting for execution events</small>
          <div class="thinking-steps" aria-hidden="true">
            <span>Planner</span>
            <span>Tool Routing</span>
            <span>Execution</span>
          </div>
        </div>
      </div>
    </article>

    <div
      v-if="reasoningModal"
      class="reasoning-modal-backdrop"
      role="presentation"
    >
      <section class="reasoning-modal" role="dialog" aria-modal="true" aria-label="Reasoning path">
        <header>
          <div>
            <span>Reasoning Path</span>
            <h2>{{ reasoningModal.title }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeReasoningModal">×</button>
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
            <li v-for="edge in reasoningModal.pathEdges" :key="`${edge.from}-${edge.to}-${edge.type}`">
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
            <li v-if="!reasoningModal.conflicts.length" class="empty">No conflict evidence participated in the selected path.</li>
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
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeChartAnalysisModal">
              <X :size="18" />
            </button>
          </div>
        </header>

        <nav v-if="chartAnalysisDatasetCount() > 1" class="result-chart-dataset-tabs" aria-label="数据集切换">
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
          <button type="button" class="result-chart-collapsible-toggle" @click="toggleChartAnalysisSettings">
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
                <option v-for="option in chartTypeOptions()" :key="option.value" :value="option.value">
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
                <option v-for="column in chartAnalysisActiveDataset()?.columns || []" :key="column" :value="column">
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
                <option v-for="column in chartAnalysisActiveDataset()?.columns || []" :key="column" :value="column">
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
                <option v-for="column in chartAnalysisActiveDataset()?.columns || []" :key="column" :value="column">
                  {{ column }}
                </option>
              </select>
            </label>
          </div>
        </section>

        <section class="result-chart-collapsible result-chart-column-picker" :class="{ open: chartAnalysisColumnsOpen }" aria-label="下钻数据列">
          <button type="button" class="result-chart-collapsible-toggle" @click="toggleChartAnalysisColumnsPanel">
            <span>
              <ChevronDown v-if="chartAnalysisColumnsOpen" :size="16" />
              <ChevronRight v-else :size="16" />
              下钻数据列
            </span>
            <small>已选 {{ chartAnalysisActiveDataset()?.selectedColumns?.length || 0 }} / {{ chartAnalysisActiveDataset()?.columns?.length || 0 }}</small>
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
                >
                <span>{{ column }}</span>
              </label>
            </div>
          </div>
        </section>
        <p v-if="chartAnalysisSemanticSummary() && chartAnalysisSettingsOpen" class="result-chart-semantics">
          {{ chartAnalysisSemanticSummary() }}
        </p>

        <VisualizationRenderer
          v-if="chartAnalysisSpec()"
          :spec="chartAnalysisSpec()"
          @drill-down="handleChartAnalysisDrillDown"
        />

        <footer>
          <span>{{ chartAnalysisActiveDataset()?.rows?.length || 0 }} 行，{{ chartAnalysisActiveDataset()?.columns?.length || 0 }} 个字段</span>
          <button type="button" :disabled="!(chartAnalysisActiveDataset()?.selectedColumns?.length)" @click="drillDownChartAnalysis">下钻分析</button>
          <button type="button" @click="closeChartAnalysisModal">关闭</button>
        </footer>
        <span class="result-chart-resize-grip" aria-hidden="true"></span>
      </section>
    </div>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
