<template>
  <div class="right-panel">
    <InfoCard title="最近报告" action="全部">
      <article v-for="report in reports" :key="report.title" class="report-item">
        <span class="file-badge" :class="report.type">{{ report.mark }}</span>
        <div>
          <strong>{{ report.title }}</strong>
          <time>{{ report.time }}</time>
        </div>
      </article>
    </InfoCard>

    <InfoCard title="我的收藏" action="全部">
      <article v-for="favorite in favorites" :key="favorite.title" class="simple-row">
        <span class="favorite-star"></span>
        <strong>{{ favorite.title }}</strong>
        <time>{{ favorite.date }}</time>
      </article>
    </InfoCard>

    <InfoCard title="我的任务" action="全部">
      <article v-for="task in tasks" :key="task.title" class="task-row">
        <span class="task-dot" :class="task.status"></span>
        <strong>{{ task.title }}</strong>
        <span>{{ task.label }}</span>
      </article>
      <button type="button" class="new-task">新建任务</button>
    </InfoCard>
  </div>
</template>

<script>
import InfoCard from "./InfoCard.vue";

export default {
  name: "RightPanel",
  components: {
    InfoCard
  },
  data() {
    return {
      reports: [
        { title: "半导体行业周报", time: "2024-06-04 14:32", type: "blue", mark: "D" },
        { title: "贵州茅台投资价值分析", time: "2024-06-04 10:15", type: "red", mark: "A" },
        { title: "AI算力产业链深度报告", time: "2024-06-03 16:45", type: "green", mark: "R" },
        { title: "中芯国际财报解读", time: "2024-06-03 09:20", type: "amber", mark: "C" },
        { title: "市场每日跟踪报告", time: "2024-06-02 17:30", type: "blue", mark: "D" }
      ],
      favorites: [
        { title: "宁德时代", date: "2024-06-01" },
        { title: "光伏行业分析框架", date: "2024-05-30" },
        { title: "北向资金流向监控", date: "2024-05-29" },
        { title: "半导体产业链图谱", date: "2024-05-28" }
      ],
      tasks: [
        { title: "生成半导体行业日报", label: "进行中", status: "running" },
        { title: "监控AI板块异动", label: "进行中", status: "running alt" },
        { title: "舆情日报推送", label: "已暂停", status: "paused" }
      ]
    };
  }
};
</script>
