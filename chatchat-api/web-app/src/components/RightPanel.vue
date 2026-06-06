<template>
  <div class="right-panel">
    <InfoCard title="最近报告" action="全部" collapsible>
      <article v-for="report in reports" :key="report.title" class="report-item">
        <span class="file-badge" :class="report.type">{{ report.mark }}</span>
        <div>
          <strong>{{ report.title }}</strong>
          <time>{{ report.time }}</time>
        </div>
      </article>
    </InfoCard>

    <InfoCard :title="`${displayUserId} 收藏`" action="全部" collapsible>
      <article v-for="favorite in favorites" :key="favorite.title" class="simple-row">
        <span class="favorite-star"></span>
        <strong>{{ favorite.title }}</strong>
        <time>{{ favorite.date }}</time>
      </article>
    </InfoCard>

    <InfoCard :title="`${displayUserId} 任务`" action="全部" collapsible>
      <article v-for="task in tasks" :key="task.title" class="task-row">
        <span class="task-dot" :class="task.status"></span>
        <strong>{{ task.title }}</strong>
        <span>{{ task.label }}</span>
      </article>
      <button type="button" class="new-task">新建任务</button>
    </InfoCard>
  </div>
</template>

<script src="../js/components/RightPanel.js"></script>
