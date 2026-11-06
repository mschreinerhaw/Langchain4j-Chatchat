<template>
  <section class="exploration-activity-panel" aria-label="实时分析活动">
    <header class="analysis-activity-heading">
      <button type="button" class="analysis-activity-toggle" :aria-expanded="expanded" @click="expanded=!expanded">
        <ChevronDown v-if="expanded" :size="15" /><ChevronRight v-else :size="15" />
        <Activity :size="16" /><strong>分析活动</strong><span class="analysis-live-state" :class="{live:running}">{{ running?'实时更新':'执行记录' }}</span>
      </button>
      <div class="analysis-activity-summary"><span>{{ currentAction }}</span><time>累计 {{ workbench.elapsedLabel }}</time></div>
    </header>
    <dl class="analysis-delivery-stages" aria-label="分析交付状态"><div v-for="stage in deliveryStages" :key="stage.key"><dt>{{ stage.label }}</dt><dd>{{ stage.value }}</dd></div></dl>
    <template v-if="expanded">
      <div class="analysis-activity-toolbar">
        <div role="tablist" aria-label="分析活动视图">
          <button type="button" role="tab" :aria-selected="tab==='activity'" @click="tab='activity'">活动记录 <span>{{ rows.length }}</span></button>
          <button type="button" role="tab" :aria-selected="tab==='details'" @click="tab='details'">详细日志</button>
        </div>
        <div class="analysis-activity-controls">
          <label v-if="tab==='activity'"><input v-model="onlyIssues" type="checkbox" />仅看异常</label>
          <button v-if="tab==='activity'" type="button" :class="{selected:following}" :aria-pressed="following" @click="following?following=false:followLatest()">跟随最新<ArrowDown :size="12" /></button>
        </div>
      </div>
      <div v-if="tab==='activity'" ref="scroller" class="analysis-event-scroll" role="log" aria-label="分析活动记录" aria-live="polite" tabindex="0" @scroll="onScroll">
        <table v-if="visibleRows.length" class="analysis-event-table">
          <thead><tr><th scope="col">时间</th><th scope="col">执行动作</th><th scope="col">耗时</th><th scope="col">状态</th></tr></thead>
          <tbody><tr v-for="row in visibleRows" :key="row.id" :class="[row.state,{critical:row.critical}]">
            <td class="analysis-event-time">{{ row.timeLabel }}</td>
            <td><div class="analysis-event-action"><LoaderCircle v-if="running && ['active','waiting'].includes(row.state)" :size="14" class="analysis-event-spinner" /><CircleCheck v-else-if="row.state==='done'" :size="14" /><CircleAlert v-else-if="row.critical" :size="14" /><Circle v-else :size="13" /><strong>{{ actionTitle(row) }}</strong><span v-if="row.count>1" class="analysis-event-count">× {{ row.count }}</span></div><p>{{ row.detail }}</p></td>
            <td class="analysis-event-duration">{{ duration(row) }}</td><td><span class="analysis-event-status">{{ row.statusLabel }}</span></td>
          </tr></tbody>
        </table>
        <div v-else class="analysis-activity-empty"><LoaderCircle v-if="running && !onlyIssues" :size="18" class="analysis-event-spinner" /><CircleCheck v-else :size="18" /><strong>{{ onlyIssues?'暂无异常记录':running?'等待首条执行事件':'暂无独立执行记录' }}</strong><p>{{ onlyIssues?'收到权限提示、确认请求或执行异常时会在这里显示。':'真实的计划、数据读取、计算和结果校验会持续显示在这里。' }}</p></div>
      </div>
      <div v-else class="analysis-technical-events" aria-label="分析诊断日志">
        <p>最近 {{ events.length }} 条诊断事件 · 仅展示当前分析节点的记录</p>
        <details v-for="(event,index) in events" :key="event.eventId || event.sequence || index"><summary><span>#{{ event.sequence || index+1 }}</span><code>{{ technicalType(event) }}</code></summary><pre>{{ JSON.stringify(event,null,2) }}</pre></details>
        <p v-if="!events.length">尚未收到诊断事件。</p>
      </div>
      <footer class="analysis-activity-footer"><span><span class="analysis-connection-dot" :class="{live:running}"></span>{{ running?'根据执行回执更新；等待期间保留最后已知动作':'记录来自任务执行回执' }}</span><span>{{ events.length }} 条事件 · 同类动作已聚合</span></footer>
    </template>
  </section>
</template>
<script src="../js/components/ExplorationActivityPanel.js"></script>
<style scoped src="../styles/components/exploration-activity-panel.css"></style>
