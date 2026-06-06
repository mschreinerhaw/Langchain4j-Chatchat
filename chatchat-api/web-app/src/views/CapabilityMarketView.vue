<template>
  <section class="feature-view capability-market-view">
    <header>
      <p>能力广场</p>
      <h1>面向业务人员的投研与经营能力</h1>
    </header>
    <div class="feature-grid">
      <article v-for="item in capabilities" :key="item.title" class="feature-card">
        <span>{{ item.code }}</span>
        <h2>{{ item.title }}</h2>
        <p>{{ item.description }}</p>
      </article>
    </div>
  </section>
</template>

<script src="../js/views/CapabilityMarketView.js"></script>
