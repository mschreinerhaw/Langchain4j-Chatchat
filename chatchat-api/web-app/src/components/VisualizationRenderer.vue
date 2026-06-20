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
    </header>

    <div v-if="activeView === 'graph'" class="visualization-graph">
      <div v-if="isMetrics" class="visualization-metrics">
        <article v-for="metric in metrics" :key="metric.label">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small v-if="metric.unit">{{ metric.unit }}</small>
        </article>
      </div>

      <svg v-else-if="isPieChart" viewBox="0 0 640 260" role="img" :aria-label="title">
        <g :transform="`translate(${pieCenter.x} ${pieCenter.y})`">
          <path
            v-for="slice in pieSlices"
            :key="slice.label"
            :d="slice.path"
            :fill="slice.color"
            class="visualization-click-target"
            @click="emitDrillDown({ label: slice.label, value: slice.value, row: slice.row })"
          />
        </g>
        <g class="visualization-legend">
          <g v-for="(slice, index) in pieSlices" :key="`${slice.label}-legend`" :transform="`translate(370 ${48 + index * 28})`">
            <rect width="10" height="10" rx="2" :fill="slice.color" />
            <text x="18" y="10">{{ slice.label }} {{ slice.percent }}%</text>
          </g>
        </g>
      </svg>

      <svg v-else viewBox="0 0 640 260" role="img" :aria-label="title">
        <g class="visualization-axis">
          <line x1="48" y1="214" x2="608" y2="214" />
          <line x1="48" y1="28" x2="48" y2="214" />
          <text x="44" y="32" text-anchor="end">{{ formatCompact(maxValue) }}</text>
          <text x="44" y="214" text-anchor="end">{{ formatCompact(minValue) }}</text>
        </g>
        <g v-if="isBarChart">
          <rect
            v-for="bar in barItems"
            :key="bar.label"
            :x="bar.x"
            :y="bar.y"
            :width="bar.width"
            :height="bar.height"
            :fill="bar.color"
            rx="3"
            class="visualization-click-target"
            @click="emitDrillDown(bar)"
          />
        </g>
        <g v-else>
          <polyline
            v-for="series in lineSeries"
            :key="series.key"
            :points="series.points"
            :stroke="series.color"
            fill="none"
            stroke-width="2.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <circle
            v-for="point in scatterPoints"
            :key="point.id"
            :cx="point.x"
            :cy="point.y"
            r="3.5"
            :fill="point.color"
            class="visualization-click-target"
            @click="emitDrillDown(point)"
          />
        </g>
        <g class="visualization-x-labels">
          <text
            v-for="label in xLabels"
            :key="label.key"
            :x="label.x"
            y="238"
            text-anchor="middle"
          >
            {{ label.text }}
          </text>
        </g>
      </svg>
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
