<template>
  <section class="feature-view skill-hub-view">
    <header>
      <p>{{ userId }} 任务</p>
      <h1>跟踪用户发起的分析、监控与报告任务</h1>
    </header>
    <div class="feature-grid compact">
      <article v-for="task in tasks" :key="task.name" class="feature-card">
        <span>{{ task.short }}</span>
        <h2>{{ task.name }}</h2>
        <p>{{ task.status }}</p>
      </article>
    </div>
  </section>
</template>

<script src="../js/views/TasksView.js"></script>
