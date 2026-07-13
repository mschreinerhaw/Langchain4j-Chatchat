import * as echarts from "echarts";
import { markRaw } from "vue";

const PALETTE = ["#2f7cf6", "#20b26b", "#f4a629", "#ef4f5f", "#7c3aed", "#0891b2"];
const CHART_TYPES = new Set(["line", "bar", "pie", "scatter"]);
const PANEL_LAYOUTS = new Set(["grid", "stack"]);
const MAX_PANEL_BLOCKS = 6;

function compact(value) {
  if (value === null || value === undefined) {
    return "";
  }
  return String(value);
}

function escapeHtml(value) {
  return compact(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function fileSafeName(value) {
  const name = compact(value).trim().replace(/[\\/:*?"<>|]+/g, "_").replace(/\s+/g, "_");
  return (name || "chart").slice(0, 80);
}

function csvCell(value) {
  const text = compact(value).replace(/\r?\n/g, " ");
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function numeric(value) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const parsed = Number(String(value ?? "").replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

function normalizeRows(spec = {}) {
  if (Array.isArray(spec.dataset?.rows)) {
    const columns = Array.isArray(spec.dataset?.columns) ? spec.dataset.columns : [];
    return spec.dataset.rows
      .map((row) => {
        if (row && typeof row === "object" && !Array.isArray(row)) {
          return row;
        }
        if (Array.isArray(row)) {
          return Object.fromEntries(columns.map((column, index) => [column, row[index]]));
        }
        return null;
      })
      .filter(Boolean);
  }
  if (Array.isArray(spec.data)) {
    return spec.data.filter((row) => row && typeof row === "object" && !Array.isArray(row));
  }
  return [];
}

function normalizeMetrics(metrics, rows = [], type = "") {
  if ((type === "metric" || type === "metrics") && rows.length) {
    return rows.map((row, index) => ({
      label: row.metric || row.label || row.name || `Metric ${index + 1}`,
      value: row.value ?? row.amount ?? "",
      unit: row.unit || ""
    })).filter((item) => item.value !== "");
  }
  if (Array.isArray(metrics)) {
    return metrics
      .map((item, index) => ({
        label: item?.label || item?.name || item?.key || `Metric ${index + 1}`,
        value: item?.value ?? item?.amount ?? "",
        unit: item?.unit || ""
      }))
      .filter((item) => item.value !== "");
  }
  if (metrics && typeof metrics === "object") {
    return Object.entries(metrics).map(([label, value]) => ({ label, value, unit: "" }));
  }
  return [];
}

function isTimeKey(key, rows = []) {
  const normalized = String(key || "").toLowerCase();
  if (/date|time|month|year|day|week|quarter/.test(normalized)) {
    return true;
  }
  return rows.some((row) => !Number.isNaN(Date.parse(String(row?.[key] || ""))));
}

function chooseChartType(spec = {}, rows = [], xKey = "", series = []) {
  const requested = String(spec.chartType || spec.chart || "").toLowerCase();
  if (CHART_TYPES.has(requested)) {
    return requested;
  }
  if (series.length >= 2 && rows.length > 2 && !isTimeKey(xKey, rows)) {
    return "scatter";
  }
  const label = `${spec.title || ""} ${series[0]?.name || ""}`.toLowerCase();
  if (rows.length > 1 && rows.length <= 8 && /share|ratio|percent|占比|比例/.test(label)) {
    return "pie";
  }
  return isTimeKey(xKey, rows) ? "line" : "bar";
}

function hasExplicitChartSemantics(spec = {}) {
  const requested = String(spec.chartType || spec.chart || "").toLowerCase();
  const xKey = spec.dataset?.xKey || spec.xKey || spec.x;
  const series = Array.isArray(spec.dataset?.series) ? spec.dataset.series : [];
  const legacyY = Array.isArray(spec.y) ? spec.y : (spec.y ? [spec.y] : []);
  return CHART_TYPES.has(requested) && !!xKey && (series.some((item) => item?.yKey) || legacyY.length > 0);
}

function normalizeSingleRenderableSpec(spec = {}) {
  if (!spec || typeof spec !== "object") {
    return null;
  }
  const rows = normalizeRows(spec);
  const requestedType = String(spec.type || "").toLowerCase();
  const type = requestedType === "metrics" ? "metric" : requestedType;
  const metrics = normalizeMetrics(spec.metrics || spec.values || spec.kpis, rows, type);
  const columns = [...new Set(rows.flatMap((row) => Object.keys(row || {})))];
  const explicitChart = hasExplicitChartSemantics(spec);
  const xKey = spec.dataset?.xKey || spec.xKey || spec.x || (explicitChart ? "" : columns.find((column) => numeric(rows[0]?.[column]) === null) || columns[0] || "name");
  const explicitSeries = Array.isArray(spec.dataset?.series) ? spec.dataset.series : [];
  const legacyY = Array.isArray(spec.y) ? spec.y : (spec.y ? [spec.y] : []);
  const numericColumns = columns.filter((column) => column !== xKey && rows.some((row) => numeric(row[column]) !== null));
  const seriesCandidates = explicitChart
    ? (explicitSeries.length ? explicitSeries : legacyY.map((yKey) => ({ name: yKey, yKey })))
    : (explicitSeries.length
    ? explicitSeries
    : (legacyY.length ? legacyY.map((yKey) => ({ name: yKey, yKey })) : numericColumns.map((yKey) => ({ name: yKey, yKey }))));
  const series = seriesCandidates.filter((item) => item?.yKey && rows.some((row) => numeric(row[item.yKey]) !== null)).slice(0, 4);
  const chartType = (type === "chart" || (!type && series.length)) && (explicitChart || !type)
    ? chooseChartType(spec, rows, xKey, series)
    : "";
  const hasChart = CHART_TYPES.has(chartType) && rows.length > 0 && series.length > 0;
  if (!hasChart && !metrics.length && !rows.length) {
    return null;
  }
  return {
    ...spec,
    version: spec.version || "v1",
    type: hasChart ? "chart" : (type === "metric" || type === "metrics" ? "metric" : (metrics.length ? "metric" : "table")),
    chartType: hasChart ? chartType : "",
    dataset: {
      ...(spec.dataset || {}),
      xKey,
      series,
      rows
    },
        insight: spec.insightSpec || spec.insight || {},
    rows,
    metrics
  };
}

function isPanelSpec(spec = {}) {
  const type = String(spec.type || "").toLowerCase();
  return type === "panel" || type === "dashboard" || Array.isArray(spec.blocks);
}

function normalizePanelBlock(block, index) {
  const raw = block?.spec || block?.data || block?.visualizationSpec || block;
  const enriched = {
    ...(raw || {}),
    type: block?.type || raw?.type,
    title: block?.title || raw?.title
  };
  const normalized = normalizeSingleRenderableSpec(enriched);
  if (!normalized) {
    return null;
  }
  return {
    id: block?.id || `block-${index + 1}`,
    type: block?.type || normalized.type,
    title: block?.title || normalized.title,
    spec: normalized
  };
}

export default {
  name: "VisualizationRenderer",
  props: {
    spec: {
      type: Object,
      default: null
    },
    compact: {
      type: Boolean,
      default: false
    }
  },
  emits: ["drill-down"],
  data() {
    return {
      activeView: "graph",
      chartInstance: null,
      resizeObserver: null
    };
  },
  computed: {
    panelSpec() {
      if (!this.spec || typeof this.spec !== "object" || !isPanelSpec(this.spec)) {
        return null;
      }
      const blocks = (Array.isArray(this.spec.blocks) ? this.spec.blocks.slice(0, MAX_PANEL_BLOCKS) : [])
        .map(normalizePanelBlock)
        .filter(Boolean);
      if (!blocks.length) {
        const fallback = normalizeSingleRenderableSpec(this.spec);
        if (fallback) {
          blocks.push({ id: "primary", type: fallback.type, title: fallback.title, spec: fallback });
        }
      }
      if (!blocks.length) {
        return null;
      }
      const requestedLayout = String(this.spec.layout || "").toLowerCase();
      return {
        version: "v2",
        type: "panel",
        title: this.spec.title || "BI Panel",
        analysisType: this.spec.analysisType || "",
        layout: PANEL_LAYOUTS.has(requestedLayout) ? requestedLayout : "stack",
        insight: this.spec.insightSpec || this.spec.insight || {},
        blocks
      };
    },
    normalizedSpec() {
      if (this.panelSpec || !this.spec || typeof this.spec !== "object") {
        return null;
      }
      return normalizeSingleRenderableSpec(this.spec);
    },
    title() {
      return compact(this.normalizedSpec?.title) || "自动可视化";
    },
    chartLabel() {
      if (this.isMetrics) {
        return "指标卡";
      }
      if (this.normalizedSpec?.type === "table") {
        return "数据表格";
      }
      return {
        bar: "柱状图",
        line: "折线图",
        pie: "饼图",
        scatter: "散点图"
      }[this.chartType] || "数据图表";
    },
    rows() {
      return this.normalizedSpec?.rows || [];
    },
    metrics() {
      return this.normalizedSpec?.metrics || [];
    },
    columns() {
      const explicit = this.normalizedSpec?.dataset?.columns;
      if (Array.isArray(explicit) && explicit.length) {
        return explicit.map(compact);
      }
      return [...new Set(this.rows.flatMap((row) => Object.keys(row || {})))];
    },
    chartType() {
      return String(this.normalizedSpec?.chartType || "").toLowerCase();
    },
    isMetrics() {
      return this.normalizedSpec?.type === "metric" || this.normalizedSpec?.type === "metrics";
    },
    isBarChart() {
      return this.chartType === "bar";
    },
    isPieChart() {
      return this.chartType === "pie";
    },
    xKey() {
      return this.normalizedSpec?.dataset?.xKey || this.normalizedSpec?.x || this.columns.find((column) => numeric(this.rows[0]?.[column]) === null) || this.columns[0] || "name";
    },
    yKeys() {
      const series = Array.isArray(this.normalizedSpec?.dataset?.series) ? this.normalizedSpec.dataset.series : [];
      if (series.length) {
        return series.map((item) => item.yKey).filter(Boolean).slice(0, 4);
      }
      const explicit = this.normalizedSpec?.y;
      const values = Array.isArray(explicit) ? explicit : (explicit ? [explicit] : []);
      const numericColumns = this.columns.filter((column) => column !== this.xKey && this.rows.some((row) => numeric(row[column]) !== null));
      return (values.length ? values : numericColumns).filter(Boolean).slice(0, 4);
    },
    seriesMeta() {
      const series = Array.isArray(this.normalizedSpec?.dataset?.series) ? this.normalizedSpec.dataset.series : [];
      return this.yKeys.map((key) => {
        const match = series.find((item) => item?.yKey === key) || {};
        const label = compact(match.label || match.name || key);
        const unit = compact(match.unit || this.normalizedSpec?.dataset?.unit || "");
        return {
          yKey: key,
          name: unit && !label.includes(unit) ? `${label}（${unit}）` : label,
          unit
        };
      });
    },
    xAxisLabel() {
      return compact(this.normalizedSpec?.dataset?.xLabel || this.normalizedSpec?.xLabel || this.xKey);
    },
    yAxisLabel() {
      if (this.chartType === "scatter" && this.seriesMeta.length === 1) {
        return this.seriesMeta[0].name;
      }
      if (this.seriesMeta.length === 1) {
        return this.seriesMeta[0].name;
      }
      return this.seriesMeta.length ? "指标值" : "";
    },
    chartSemanticSummary() {
      if (!this.chartOption || this.chartType === "pie") {
        if (this.chartType === "pie" && this.seriesMeta.length) {
          return `分类：${this.xAxisLabel}；扇区大小：${this.seriesMeta[0].name}`;
        }
        return "";
      }
      const seriesNames = this.seriesMeta.map((item) => item.name).join("、");
      return `X 轴：${this.xAxisLabel}；Y 轴：${this.yAxisLabel || seriesNames}；图例/线条：${seriesNames}`;
    },
    chartOption() {
      if (!this.normalizedSpec || this.isMetrics || !CHART_TYPES.has(this.chartType) || !this.rows.length || !this.yKeys.length) {
        return null;
      }
      const seriesNameByKey = Object.fromEntries(this.seriesMeta.map((item) => [item.yKey, item.name]));
      const common = {
        color: PALETTE,
        animationDuration: 220,
        textStyle: {
          color: "#344054",
          fontFamily: "Inter, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
        },
        tooltip: {
          trigger: this.chartType === "pie" ? "item" : "axis",
          confine: true,
          formatter: (params) => this.formatTooltip(params, seriesNameByKey),
          valueFormatter: (value) => this.formatCompact(Array.isArray(value) ? value[1] : value)
        },
        legend: {
          type: "scroll",
          top: 4,
          right: 8,
          textStyle: { color: "#667085", fontWeight: 700 }
        }
      };
      if (this.chartType === "pie") {
        const key = this.yKeys[0];
        return {
          ...common,
          tooltip: { ...common.tooltip, trigger: "item" },
          series: [{
            name: seriesNameByKey[key] || key,
            type: "pie",
            radius: ["42%", "68%"],
            center: ["50%", "55%"],
            avoidLabelOverlap: true,
            data: this.rows.map((row, rowIndex) => ({
              name: compact(row[this.xKey] ?? row.label ?? row.name ?? `Row ${rowIndex + 1}`),
              value: Math.max(0, numeric(row[key]) ?? 0),
              row,
              rowIndex,
              yKey: key
            }))
          }]
        };
      }
      if (this.chartType === "scatter") {
        return {
          ...common,
          grid: { left: 68, right: 18, top: 54, bottom: 46, containLabel: true },
          xAxis: { type: "value", name: this.xAxisLabel, nameGap: 22, axisLabel: { color: "#667085" } },
          yAxis: {
            type: "value",
            name: this.yAxisLabel,
            nameLocation: "middle",
            nameGap: 48,
            nameRotate: 90,
            axisLabel: { color: "#667085" }
          },
          series: this.yKeys.map((key) => ({
            name: seriesNameByKey[key] || key,
            type: "scatter",
            symbolSize: 8,
            data: this.rows.map((row, rowIndex) => ({
              value: [numeric(row[this.xKey]) ?? 0, numeric(row[key]) ?? 0],
              row,
              rowIndex,
              xKey: this.xKey,
              yKey: key
            }))
          }))
        };
      }
      return {
        ...common,
        grid: { left: 68, right: 18, top: 54, bottom: 46, containLabel: true },
        xAxis: {
          type: "category",
          name: this.xAxisLabel,
          nameGap: 28,
          data: this.rows.map((row, index) => compact(row[this.xKey] ?? `Row ${index + 1}`)),
          axisLabel: { color: "#667085", hideOverlap: true }
        },
        yAxis: {
          type: "value",
          name: this.yAxisLabel,
          nameLocation: "middle",
          nameGap: 48,
          nameRotate: 90,
          axisLabel: { color: "#667085" }
        },
        dataZoom: this.rows.length > 20 ? [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }] : [],
        series: this.yKeys.map((key) => ({
          name: seriesNameByKey[key] || key,
          type: this.chartType,
          smooth: this.chartType === "line",
          barMaxWidth: 34,
          emphasis: { focus: "series" },
          data: this.rows.map((row, rowIndex) => ({
            value: numeric(row[key]) ?? 0,
            row,
            rowIndex,
            xKey: this.xKey,
            yKey: key
          }))
        }))
      };
    },
    availableViews() {
      const views = [];
      if (this.isMetrics || this.chartOption) {
        views.push("graph");
      }
      if (this.rows.length) {
        views.push("table");
      }
      views.push("raw");
      if (this.normalizedSpec?.ui?.allowSwitch === false) {
        return [this.defaultView].filter((view) => views.includes(view));
      }
      return views;
    },
    defaultView() {
      const view = String(this.normalizedSpec?.ui?.defaultView || "").toLowerCase();
      if (view === "chart") {
        return "graph";
      }
      if (["graph", "table", "raw"].includes(view)) {
        return view;
      }
      return this.isMetrics || this.rows.length ? "graph" : "raw";
    },
    rawJson() {
      return JSON.stringify(this.normalizedSpec || this.spec, null, 2);
    },
    hasInsight() {
      const insight = this.normalizedSpec?.insight || {};
      return !!(insight.summary || insight.anomaly || insight.trend || (Array.isArray(insight.drivers) && insight.drivers.length));
    },
    hasPanelInsight() {
      const insight = this.panelSpec?.insight || {};
      return !!(insight.summary || insight.anomaly || insight.trend || (Array.isArray(insight.drivers) && insight.drivers.length));
    },
    canExport() {
      if (this.activeView === "graph") {
        return !!this.chartOption || this.isMetrics;
      }
      if (this.activeView === "table") {
        return this.rows.length > 0;
      }
      return !!this.normalizedSpec;
    },
    exportLabel() {
      return this.activeView === "graph" ? "导出 PNG" : (this.activeView === "table" ? "导出 CSV" : "导出 JSON");
    },
    exportTitle() {
      return `导出${this.viewLabel(this.activeView)}`;
    },
    numericValues() {
      const values = this.rows.flatMap((row) => this.yKeys.map((key) => numeric(row[key])).filter((value) => value !== null));
      return values.length ? values : [0];
    },
    minValue() {
      return Math.min(0, ...this.numericValues);
    },
    maxValue() {
      const max = Math.max(...this.numericValues);
      return max === this.minValue ? max + 1 : max;
    },
    xNumericValues() {
      if (this.chartType !== "scatter") {
        return [];
      }
      const values = this.rows.map((row) => numeric(row[this.xKey])).filter((value) => value !== null);
      return values.length ? values : [0];
    },
    minXValue() {
      return Math.min(...this.xNumericValues);
    },
    maxXValue() {
      const max = Math.max(...this.xNumericValues);
      return max === this.minXValue ? max + 1 : max;
    },
    xLabels() {
      if (this.chartType === "scatter") {
        return [
          { key: "min-x", text: this.formatCompact(this.minXValue), x: 48 },
          { key: "max-x", text: this.formatCompact(this.maxXValue), x: 608 }
        ];
      }
      const count = Math.max(1, this.rows.length - 1);
      return this.rows.map((row, index) => ({
        key: `${index}-${row[this.xKey]}`,
        text: compact(row[this.xKey]).slice(0, 12),
        x: 48 + (560 * index / count)
      })).filter((_, index) => index === 0 || index === this.rows.length - 1 || this.rows.length <= 6);
    },
    lineSeries() {
      if (this.chartType === "scatter") {
        return [];
      }
      return this.yKeys.map((key, seriesIndex) => ({
        key,
        color: PALETTE[seriesIndex % PALETTE.length],
        points: this.rows.map((row, index) => `${this.xForIndex(index)},${this.yForValue(numeric(row[key]) ?? 0)}`).join(" ")
      }));
    },
    scatterPoints() {
      return this.yKeys.flatMap((key, seriesIndex) =>
        this.rows.map((row, index) => ({
          id: `${key}-${index}`,
          row,
          xKey: this.xKey,
          yKey: key,
          value: numeric(row[key]) ?? 0,
          x: this.chartType === "scatter" ? this.xForValue(numeric(row[this.xKey]) ?? 0) : this.xForIndex(index),
          y: this.yForValue(numeric(row[key]) ?? 0),
          color: PALETTE[seriesIndex % PALETTE.length]
        }))
      );
    },
    barItems() {
      const seriesCount = Math.max(1, this.yKeys.length);
      const groupWidth = Math.min(70, 520 / Math.max(1, this.rows.length));
      const barWidth = Math.max(5, (groupWidth - 8) / seriesCount);
      return this.rows.flatMap((row, rowIndex) => {
        const groupX = this.xForIndex(rowIndex) - groupWidth / 2;
        return this.yKeys.map((key, seriesIndex) => {
          const value = numeric(row[key]) ?? 0;
          const y = this.yForValue(value);
          return {
            label: `${row[this.xKey]}-${key}`,
            row,
            xKey: this.xKey,
            yKey: key,
            value,
            x: groupX + 4 + seriesIndex * barWidth,
            y,
            width: barWidth - 2,
            height: Math.max(0, 214 - y),
            color: PALETTE[seriesIndex % PALETTE.length]
          };
        });
      });
    },
    pieCenter() {
      return { x: 168, y: 132 };
    },
    pieSlices() {
      const key = this.yKeys[0] || this.columns.find((column) => numeric(this.rows[0]?.[column]) !== null);
      const values = this.rows.map((row) => ({
        label: compact(row[this.xKey] ?? row.label ?? row.name),
        value: Math.max(0, numeric(row[key]) ?? 0)
      })).filter((item) => item.value > 0);
      const total = values.reduce((sum, item) => sum + item.value, 0) || 1;
      let start = -Math.PI / 2;
      return values.map((item, index) => {
        const angle = (item.value / total) * Math.PI * 2;
        const end = start + angle;
        const slice = {
          ...item,
          percent: Math.round((item.value / total) * 100),
          color: PALETTE[index % PALETTE.length],
          path: this.arcPath(82, start, end),
          row: this.rows[index]
        };
        start = end;
        return slice;
      });
    }
  },
  watch: {
    availableViews: {
      immediate: true,
      handler(views) {
        if (!views.includes(this.activeView)) {
          this.activeView = views.includes(this.defaultView) ? this.defaultView : (views[0] || "raw");
        }
      }
    },
    defaultView(view) {
      if (this.availableViews.includes(view)) {
        this.activeView = view;
      }
    },
    activeView() {
      this.renderEchart();
    },
    chartOption: {
      deep: true,
      handler() {
        this.renderEchart();
      }
    }
  },
  mounted() {
    this.renderEchart();
  },
  beforeUnmount() {
    this.disposeEchart();
  },
  methods: {
    renderEchart() {
      this.$nextTick(() => {
        const element = this.$refs.chartCanvas;
        if (!element || this.activeView !== "graph" || !this.chartOption) {
          this.disposeEchart();
          return;
        }
        if (!this.chartInstance) {
          this.chartInstance = markRaw(echarts.init(element, null, { renderer: "canvas" }));
          this.chartInstance.on("click", this.handleChartClick);
          if (typeof ResizeObserver !== "undefined") {
            this.resizeObserver = new ResizeObserver(() => this.chartInstance?.resize());
            this.resizeObserver.observe(element);
          } else if (typeof window !== "undefined") {
            window.addEventListener("resize", this.resizeEchart);
          }
        }
        this.chartInstance.setOption(this.chartOption, true);
        this.chartInstance.resize();
      });
    },
    resizeEchart() {
      this.chartInstance?.resize();
    },
    downloadBlob(content, filename, type = "text/plain;charset=utf-8") {
      if (typeof document === "undefined") {
        return;
      }
      const blob = new Blob([content], { type });
      const url = URL.createObjectURL(blob);
      this.downloadUrl(url, filename);
      window.setTimeout(() => URL.revokeObjectURL(url), 1200);
    },
    downloadUrl(url, filename) {
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    },
    async exportCurrentView() {
      const baseName = fileSafeName(this.title);
      if (this.activeView === "graph") {
        if (this.chartOption) {
          await this.$nextTick();
          if (!this.chartInstance) {
            this.renderEchart();
            await this.$nextTick();
          }
          const url = this.chartInstance?.getDataURL({
            type: "png",
            pixelRatio: 2,
            backgroundColor: "#ffffff"
          });
          if (url) {
            this.downloadUrl(url, `${baseName}.png`);
          }
          return;
        }
        this.downloadBlob(JSON.stringify(this.metrics, null, 2), `${baseName}-metrics.json`, "application/json;charset=utf-8");
        return;
      }
      if (this.activeView === "table") {
        const header = this.columns.map(csvCell).join(",");
        const body = this.rows.map((row) => this.columns.map((column) => csvCell(row[column])).join(","));
        this.downloadBlob(`\ufeff${[header, ...body].join("\n")}`, `${baseName}.csv`, "text/csv;charset=utf-8");
        return;
      }
      this.downloadBlob(JSON.stringify(this.normalizedSpec || this.spec, null, 2), `${baseName}.json`, "application/json;charset=utf-8");
    },
    disposeEchart() {
      if (this.resizeObserver) {
        this.resizeObserver.disconnect();
        this.resizeObserver = null;
      }
      if (typeof window !== "undefined") {
        window.removeEventListener("resize", this.resizeEchart);
      }
      if (this.chartInstance) {
        this.chartInstance.off("click", this.handleChartClick);
        this.chartInstance.dispose();
        this.chartInstance = null;
      }
    },
    handleChartClick(params = {}) {
      const data = params.data || {};
      this.emitDrillDown({
        label: params.name,
        value: Array.isArray(data.value) ? data.value[1] : data.value,
        row: data.row,
        rowIndex: data.rowIndex,
        xKey: data.xKey || this.xKey,
        yKey: data.yKey || params.seriesName
      });
    },
    viewLabel(view) {
      return { graph: "图表", table: "表格", raw: "原始数据" }[view] || view;
    },
    formatTooltip(params, seriesNameByKey = {}) {
      const items = Array.isArray(params) ? params : [params];
      if (!items.length) {
        return "";
      }
      const first = items[0] || {};
      const firstData = first.data || {};
      const row = firstData.row || {};
      const xValue = row[this.xKey] ?? first.name ?? "";
      const lines = [`<strong>${escapeHtml(this.xAxisLabel)}：${escapeHtml(xValue)}</strong>`];
      items.forEach((item) => {
        const data = item.data || {};
        const key = data.yKey || item.seriesName || "";
        const name = seriesNameByKey[key] || item.seriesName || key;
        const value = Array.isArray(data.value) ? data.value[1] : data.value;
        lines.push(`${escapeHtml(name)}：${escapeHtml(this.formatCompact(value))}`);
      });
      return lines.join("<br/>");
    },
    emitDrillDown(selection = {}) {
      this.$emit("drill-down", {
        title: this.title,
        chartType: this.chartType,
        analysisType: this.normalizedSpec?.analysisType || "",
        xKey: this.xKey,
        yKeys: this.yKeys,
        selection,
        spec: this.normalizedSpec || this.panelSpec || this.spec
      });
    },
    forwardDrillDown(block, event) {
      this.$emit("drill-down", {
        ...event,
        panelTitle: this.panelSpec?.title || "",
        blockId: block.id,
        blockTitle: block.title,
        blockType: block.type
      });
    },
    xForIndex(index) {
      const count = Math.max(1, this.rows.length - 1);
      return 48 + (560 * index / count);
    },
    xForValue(value) {
      const range = this.maxXValue - this.minXValue || 1;
      return 48 + ((value - this.minXValue) / range) * 560;
    },
    yForValue(value) {
      const range = this.maxValue - this.minValue || 1;
      return 214 - ((value - this.minValue) / range) * 186;
    },
    arcPath(radius, start, end) {
      const startPoint = [Math.cos(start) * radius, Math.sin(start) * radius];
      const endPoint = [Math.cos(end) * radius, Math.sin(end) * radius];
      const largeArc = end - start > Math.PI ? 1 : 0;
      return `M 0 0 L ${startPoint[0]} ${startPoint[1]} A ${radius} ${radius} 0 ${largeArc} 1 ${endPoint[0]} ${endPoint[1]} Z`;
    },
    formatCompact(value) {
      const number = numeric(value);
      if (number === null) {
        return compact(value);
      }
      return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(number);
    }
  }
};
