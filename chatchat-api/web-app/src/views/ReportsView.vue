<template>
  <section class="feature-view reports-view">
    <header>
      <p>{{ userId }} 报告</p>
      <h1>报告草稿与归档</h1>
    </header>
    <div class="table-like">
      <article v-for="report in reports" :key="report.title">
        <strong>{{ report.title }}</strong>
        <span>{{ report.type }}</span>
        <time>{{ report.date }}</time>
      </article>
    </div>
  </section>
</template>

<script src="../js/views/ReportsView.js"></script>
