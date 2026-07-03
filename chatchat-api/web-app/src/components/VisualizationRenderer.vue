<template>
  <section v-if="panelSpec" class="visualization-renderer visualization-panel" :class="`layout-${panelSpec.layout}`">
    <header class="visualization-header">
      <div>
        <p>{{ panelSpec.analysisType || "BI Panel" }}</p>
        <h3>{{ panelSpec.title }}</h3>
      </div>
    </header>

    <div class="visualization-panel-grid">
      <article
        v-for="block in panelSpec.blocks"
        :key="block.id"
        class="visualization-panel-block"
      >
        <VisualizationRenderer
          :spec="block.spec"
          compact
          @drill-down="forwardDrillDown(block, $event)"
        />
      </article>
    </div>

    <div v-if="hasPanelInsight" class="visualization-insight">
      <p v-if="panelSpec.insight.summary">{{ panelSpec.insight.summary }}</p>
      <span v-if="panelSpec.insight.trend">{{ panelSpec.insight.trend }}</span>
      <strong v-if="panelSpec.insight.anomaly">{{ panelSpec.insight.anomaly }}</strong>
      <em v-for="driver in panelSpec.insight.drivers || []" :key="driver">{{ driver }}</em>
    </div>
  </section>

  <section v-else-if="normalizedSpec" class="visualization-renderer" :class="{ compact }">
    <header class="visualization-header">
      <div>
        <p>{{ chartLabel }}</p>
        <h3>{{ title }}</h3>
      </div>
      <div class="visualization-actions">
        <nav v-if="availableViews.length > 1" class="visualization-tabs" aria-label="Visualization views">
          <button
            v-for="view in availableViews"
            :key="view"
            type="button"
            :class="{ active: activeView === view }"
            @click="activeView = view"
          >
            {{ viewLabel(view) }}
          </button>
        </nav>
        <button
          v-if="canExport"
          type="button"
          class="visualization-export-button"
          :title="exportTitle"
          @click="exportCurrentView"
        >
          {{ exportLabel }}
        </button>
      </div>
    </header>

    <p v-if="chartSemanticSummary" class="visualization-semantics">
      {{ chartSemanticSummary }}
    </p>

    <div v-if="activeView === 'graph'" class="visualization-graph">
      <div v-if="isMetrics" class="visualization-metrics">
        <article v-for="metric in metrics" :key="metric.label">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small v-if="metric.unit">{{ metric.unit }}</small>
        </article>
      </div>

      <div v-else ref="chartCanvas" class="visualization-echart" role="img" :aria-label="title"></div>
    </div>

    <div v-else-if="activeView === 'table'" class="visualization-table">
      <table>
        <thead>
          <tr>
            <th v-for="column in columns" :key="column">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index" @click="emitDrillDown({ row, rowIndex: index })">
            <td v-for="column in columns" :key="column">{{ row[column] }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <pre v-else class="visualization-raw"><code>{{ rawJson }}</code></pre>

    <div v-if="hasInsight" class="visualization-insight">
      <p v-if="normalizedSpec.insight.summary">{{ normalizedSpec.insight.summary }}</p>
      <span v-if="normalizedSpec.insight.trend">{{ normalizedSpec.insight.trend }}</span>
      <strong v-if="normalizedSpec.insight.anomaly">{{ normalizedSpec.insight.anomaly }}</strong>
      <em v-for="driver in normalizedSpec.insight.drivers || []" :key="driver">{{ driver }}</em>
    </div>
  </section>
</template>

<script src="../js/components/VisualizationRenderer.js"></script>
