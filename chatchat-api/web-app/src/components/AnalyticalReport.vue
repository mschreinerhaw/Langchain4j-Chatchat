<template>
  <section v-if="blocks.length" class="analytical-report">
    <header>
      <p>数据分析报告</p>
      <h2>{{ report.decisionQuestion || "分析判断与依据" }}</h2>
    </header>
    <section v-if="primaryBlocks.length" class="insight-executive">
      <h3>核心业务判断</h3>
      <ol
        ><li v-for="block in primaryBlocks" :key="block.id">{{ block.observation }}</li></ol
      >
    </section>
    <p v-else class="insight-state"
      >当前尚无同时绑定计算数据与证据的核心结论，以下说明保留供核对。</p
    >
    <article
      v-for="block in blocks"
      :key="block.id"
      class="analytical-insight"
      :data-finding-id="block.id"
    >
      <header
        ><span>{{ sectionLabel(block.section) }}</span
        ><h3>{{ block.question || "分析发现" }}</h3></header
      >
      <p class="insight-observation">{{ block.observation }}</p>
      <p v-if="block.presentation?.validationStatus !== 'VERIFIED_DATA_BOUND'" class="insight-state"
        >数据状态：待补充可验证数据</p
      >
      <template v-else>
        <VisualizationRenderer
          v-if="block.visualization?.type === 'chart'"
          :spec="block.visualization"
          :preference="preferences[`finding:${block.id}`]"
          @preference-change="
            $emit('preference-change', { slot: `finding:${block.id}`, preference: $event })
          "
          :style="{
            '--insight-chart-height': `${Math.max(300, Math.min(1000, (block.data.rows?.length || 0) * 26 + 100))}px`,
          }"
          @drill-down="$emit('drill-down', { ...$event, findingId: block.id })"
        />
        <dl v-if="block.presentation?.showKeyMetrics" class="insight-metric">
          <dt>{{ block.data.title }}</dt
          ><dd
            >{{ block.data.metric }} <small>{{ block.data.metricUnit }}</small></dd
          >
        </dl>
        <div v-if="block.presentation?.showDataTable" class="insight-data">
          <table
            ><caption>支撑数据 · {{ block.data.unit }}</caption>
            <thead
              ><tr
                ><th>对象</th><th>数值（{{ block.data.unit }}）</th></tr
              ></thead
            >
            <tbody
              ><tr v-for="(row, index) in block.data.rows" :key="index"
                ><td>{{ row.entity }}</td
                ><td>{{ row.value }}</td></tr
              ></tbody
            >
          </table>
        </div>
        <p class="insight-scope">{{ block.data.scope }}</p>
        <p v-if="block.data.calculation" class="insight-scope"
          >计算口径：{{ block.data.calculation }}</p
        >
      </template>
      <section v-if="block.interpretation"
        ><h4>解释与判断</h4><p>{{ block.interpretation }}</p></section
      >
      <section v-if="block.implication"
        ><h4>业务含义</h4><p>{{ block.implication }}</p></section
      >
      <p v-if="block.confidence">判断可信度：{{ block.confidence }}</p>
      <ul v-if="block.caveats?.length" class="insight-caveats"
        ><li v-for="caveat in block.caveats" :key="caveat">{{ caveat }}</li></ul
      >
      <details v-if="block.evidence?.length" class="insight-evidence"
        ><summary>数据来源与证据（{{ block.evidence.length }}）</summary>
        <div v-for="evidence in block.evidence" :key="evidence.artifactId">
          <p>{{ evidence.text }}</p
          ><p>来源：{{ evidence.sourceScope }}</p>
          <p>记录：{{ (evidence.recordRefs || []).join("、") }}</p>
          <p>原始支持值：{{ (evidence.supportingValues || []).join("、") }}</p>
        </div>
      </details>
    </article>
  </section>
</template>

<script src="../js/components/AnalyticalReport.js"></script>

<style scoped src="../styles/components/analytical-report.css"></style>
