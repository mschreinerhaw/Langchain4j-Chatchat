<template>
  <div class="right-panel" :class="{ collapsed }">
    <div class="right-panel-head">
      <div>
        <strong>侧边工具</strong>
        <span>报告、收藏与任务</span>
      </div>
      <button
        type="button"
        :aria-label="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
        :title="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
        @click="$emit('toggle-collapsed')"
      >
        <PanelRightOpen v-if="collapsed" :size="18" stroke-width="1.8" />
        <PanelRightClose v-else :size="18" stroke-width="1.8" />
      </button>
    </div>

    <div v-if="collapsed" class="right-tool-rail">
      <button
        v-for="item in railItems"
        :key="item.id"
        type="button"
        :title="item.label"
        :aria-label="item.label"
        @click="$emit('toggle-collapsed')"
      >
        <component :is="item.icon" :size="18" stroke-width="2" />
        <span>{{ item.count }}</span>
      </button>
    </div>

    <template v-else>
      <InfoCard title="最近报告" action="全部" collapsible>
        <article v-for="report in reports" :key="report.title" class="report-item">
          <span class="file-badge" :class="report.type">{{ report.mark }}</span>
          <div>
            <strong>{{ report.title }}</strong>
            <time>{{ report.time }}</time>
          </div>
        </article>
        <p v-if="reports.length === 0" class="right-panel-empty">暂无最近报告</p>
      </InfoCard>

      <InfoCard title="收藏" action="全部" collapsible>
        <article v-for="favorite in favorites" :key="favorite.title" class="simple-row">
          <span class="favorite-star"></span>
          <strong>{{ favorite.title }}</strong>
          <time>{{ favorite.date }}</time>
        </article>
        <p v-if="favorites.length === 0" class="right-panel-empty">暂无收藏内容</p>
      </InfoCard>

      <InfoCard title="任务" action="全部" collapsible>
        <article v-for="task in tasks" :key="task.title" class="task-row">
          <span class="task-dot" :class="task.status"></span>
          <strong>{{ task.title }}</strong>
          <span>{{ task.label }}</span>
        </article>
        <p v-if="tasks.length === 0" class="right-panel-empty">暂无任务</p>
        <button type="button" class="new-task">新建任务</button>
      </InfoCard>
    </template>
  </div>
</template>

<script src="../js/components/RightPanel.js"></script>
