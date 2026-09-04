import * as echarts from "echarts";
import { markRaw } from "vue";
import { isDirectionalMetric, trendColor, TREND_SEMANTICS_UPDATED_EVENT } from "../utils/trendSemantics.js";

export { isDirectionalMetric, trendColor, TREND_COLORS } from "../utils/trendSemantics.js";

const PALETTE = ["#2563eb", "#7c3aed", "#0891b2", "#d97706", "#db2777", "#4f46e5", "#0f766e", "#9333ea"];
const CHART_TYPES = new Set(["line", "bar", "pie", "scatter"]);
const PANEL_LAYOUTS = new Set(["grid", "stack"]);
const MAX_PANEL_BLOCKS = 6;
const AXIS_LABEL_FONT = "12px Inter, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const AXIS_LABEL_MARGIN = 10;
const AXIS_TITLE_CLEARANCE = 20;
const X_AXIS_LABEL_LIMIT = 18;
const LEGEND_LABEL_LIMIT = 24;

let axisMeasureCanvas;

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

function formatAxisTick(value) {
  return formatDataValue(value);
}

export function formatDataValue(value) {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      return String(value);
    }
    return new Intl.NumberFormat("zh-CN", {
      useGrouping: true,
      maximumFractionDigits: 20
    }).format(value);
  }
  if (typeof value === "bigint") {
    return new Intl.NumberFormat("zh-CN", { useGrouping: true }).format(value);
  }
  return compact(value);
}

function measureAxisLabelWidth(value) {
  const text = formatAxisTick(value);
  return measureTextWidth(text);
}

function measureTextWidth(value) {
  const text = compact(value);
  if (typeof document !== "undefined") {
    axisMeasureCanvas ||= document.createElement("canvas");
    const context = axisMeasureCanvas.getContext("2d");
    if (context) {
      context.font = AXIS_LABEL_FONT;
      return context.measureText(text).width;
    }
  }
  return Array.from(text).reduce((width, character) => width + (/[\u2e80-\uffff]/.test(character) ? 12 : 7), 0);
}

function truncateDisplayLabel(value, limit, maxWidth = Number.POSITIVE_INFINITY) {
  const characters = Array.from(compact(value));
  let visible = characters.slice(0, limit);
  while (visible.length > 1 && measureTextWidth(`${visible.join("")}…`) > maxWidth) {
    visible = visible.slice(0, -1);
  }
  const truncated = visible.length < characters.length;
  return `${visible.join("")}${truncated ? "…" : ""}`;
}

function resolveXAxisLayout(values = [], viewportWidth = 520, numericAxis = false, hasDataZoom = false) {
  const labels = values.map((value) => numericAxis ? formatAxisTick(value) : truncateDisplayLabel(value, X_AXIS_LABEL_LIMIT, 120));
  const maxLabelWidth = Math.max(12, ...labels.map(measureTextWidth));
  const estimatedPlotWidth = Math.max(240, viewportWidth - 150);
  const visibleLabelCount = Math.max(1, Math.min(labels.length, 12));
  const availableWidth = estimatedPlotWidth / visibleLabelCount;
  const rotate = maxLabelWidth <= availableWidth
    ? 0
    : (maxLabelWidth <= availableWidth * 1.8 ? 30 : 45);
  const radians = rotate * Math.PI / 180;
  const labelHeight = Math.sin(radians) * maxLabelWidth + Math.cos(radians) * 12;
  return {
    axisLabel: {
      color: "#667085",
      hideOverlap: true,
      margin: AXIS_LABEL_MARGIN,
      rotate,
      formatter: numericAxis
        ? formatAxisTick
        : (value) => truncateDisplayLabel(value, X_AXIS_LABEL_LIMIT, 120)
    },
    nameGap: Math.max(28, Math.ceil(labelHeight + AXIS_LABEL_MARGIN + 14)),
    gridBottom: hasDataZoom ? 84 : 54
  };
}

function resolveYAxisLayout(values = []) {
  const numericValues = values.map(numeric).filter((value) => value !== null);
  const minimum = Math.min(0, ...(numericValues.length ? numericValues : [0]));
  const maximumValue = Math.max(...(numericValues.length ? numericValues : [0]));
  const maximum = maximumValue === minimum ? maximumValue + 1 : maximumValue;
  const samples = Array.from({ length: 7 }, (_, index) => minimum + ((maximum - minimum) * index / 6));
  const labelWidth = Math.ceil(Math.max(...samples.map(measureAxisLabelWidth)));
  return {
    axisLabel: {
      color: "#667085",
      margin: AXIS_LABEL_MARGIN,
      formatter: formatAxisTick
    },
    nameGap: Math.max(48, labelWidth + AXIS_LABEL_MARGIN + AXIS_TITLE_CLEARANCE)
  };
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

export function matchesConfiguredVisualizationKeyword(spec = {}) {
  const dataset = spec?.dataset || {};
  const series = Array.isArray(dataset.series) ? dataset.series : [];
  const rows = Array.isArray(dataset.rows) ? dataset.rows : [];
  const columns = [...new Set([
    ...(Array.isArray(dataset.columns) ? dataset.columns : []),
    ...rows.flatMap((row) => Object.keys(row || {})),
    ...series.map((item) => item?.yKey).filter(Boolean)
  ])];
  return columns.some((column) => isDirectionalMetric(column))
    || series.some((item) => isDirectionalMetric(`${item?.name || ""} ${item?.label || ""} ${item?.yKey || ""}`));
}

function isKeywordControlledAutomaticChart(spec = {}) {
  return spec?.automation?.autoGenerated === true
    && String(spec?.automation?.selectionMode || "").toLowerCase() === "configured_keyword";
}

export function selectConfiguredVisualizationDimensions(spec = {}) {
  if (!isKeywordControlledAutomaticChart(spec)) {
    return spec;
  }
  const dataset = spec?.dataset || {};
  const rows = Array.isArray(dataset.rows) ? dataset.rows : [];
  const existingSeries = Array.isArray(dataset.series) ? dataset.series : [];
  const columns = [...new Set([
    ...(Array.isArray(dataset.columns) ? dataset.columns : []),
    ...rows.flatMap((row) => Object.keys(row || {})),
    ...existingSeries.map((item) => item?.yKey).filter(Boolean)
  ])];
  const matchedColumns = columns.filter((column) => isDirectionalMetric(column));
  const matchedSeries = existingSeries.filter((item) =>
    isDirectionalMetric(`${item?.name || ""} ${item?.label || ""} ${item?.yKey || ""}`));
  const matchedMetricKeys = [...new Set([
    ...matchedColumns.filter((column) => rows.some((row) => numeric(row?.[column]) !== null)),
    ...matchedSeries.map((item) => item?.yKey)
      .filter((key) => key && rows.some((row) => numeric(row?.[key]) !== null))
  ])].slice(0, 4);
  const matchedDimensionKeys = matchedColumns.filter((column) =>
    !matchedMetricKeys.includes(column) && rows.some((row) => compact(row?.[column]) !== ""));
  if (!matchedMetricKeys.length && !matchedDimensionKeys.length) {
    return null;
  }

  const requestedXKey = dataset.xKey || spec.xKey || spec.x;
  const fallbackDimension = columns.find((column) =>
    !matchedMetricKeys.includes(column) && rows.some((row) => numeric(row?.[column]) === null));
  const xKey = matchedDimensionKeys[0]
    || (requestedXKey && !matchedMetricKeys.includes(requestedXKey) ? requestedXKey : fallbackDimension);
  if (!xKey) {
    return null;
  }

  const series = matchedMetricKeys.length
    ? matchedMetricKeys.map((yKey) => existingSeries.find((item) => item?.yKey === yKey) || { name: yKey, yKey })
    : existingSeries.filter((item) => item?.yKey && rows.some((row) => numeric(row?.[item.yKey]) !== null)).slice(0, 4);
  if (!series.length) {
    return null;
  }

  const distribution = String(spec.analysisType || "").toLowerCase() === "distribution";
  return {
    ...spec,
    chartType: distribution ? spec.chartType : (isTimeKey(xKey, rows) ? "line" : "bar"),
    automation: {
      ...(spec.automation || {}),
      selectedDimensions: [...matchedDimensionKeys, ...matchedMetricKeys],
      primaryDimension: matchedDimensionKeys[0] || matchedMetricKeys[0]
    },
    dataset: {
      ...dataset,
      columns,
      xKey,
      series
    }
  };
}

function normalizeRenderableSpec(spec = {}) {
  const selected = selectConfiguredVisualizationDimensions(spec);
  return selected ? normalizeSingleRenderableSpec(selected) : null;
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
  const selected = selectConfiguredVisualizationDimensions(raw);
  if (!selected) return null;
  const enriched = {
    ...selected,
    type: block?.type || selected?.type,
    title: block?.title || selected?.title
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

export function isRawDataBlock(block = {}) {
  const spec = block?.spec || block?.data || block?.visualizationSpec || block;
  return String(block?.type || spec?.type || "").toLowerCase() === "table"
    || spec?.ui?.role === "raw_data";
}

export function visualizationRowCount(spec = {}) {
  const declared = Number(spec?.dataset?.rowCount);
  if (Number.isFinite(declared) && declared >= 0) {
    return declared;
  }
  return Array.isArray(spec?.dataset?.rows) ? spec.dataset.rows.length : 0;
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
      resizeObserver: null,
      chartViewportWidth: 520,
      trendSemanticsRevision: 0
    };
  },
  computed: {
    panelSpec() {
      this.trendSemanticsRevision;
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
        rawDataDefaultCollapsed: this.spec?.ui?.rawDataDefaultCollapsed !== false,
        blocks
      };
    },
    normalizedSpec() {
      if (this.panelSpec || !this.spec || typeof this.spec !== "object") {
        return null;
      }
      return normalizeRenderableSpec(this.spec);
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
      this.trendSemanticsRevision;
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
    yAxisLayout() {
      return resolveYAxisLayout(this.numericValues);
    },
    directionalKeys() {
      return this.seriesMeta
        .filter((item) => isDirectionalMetric(`${item.yKey} ${item.name}`))
        .map((item) => item.yKey);
    },
    hasDirectionalSeries() {
      return this.chartType !== "pie"
        && (this.directionalKeys.length > 0 || (this.chartType === "line" && this.rows.length > 1));
    },
    xAxisLayout() {
      const values = this.chartType === "scatter"
        ? this.xNumericValues
        : this.rows.map((row, index) => compact(row[this.xKey] ?? `Row ${index + 1}`));
      return resolveXAxisLayout(
        values,
        this.chartViewportWidth,
        this.chartType === "scatter",
        this.rows.length > 20
      );
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
      this.trendSemanticsRevision;
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
          valueFormatter: (value) => this.formatDataValue(Array.isArray(value) ? value[1] : value)
        },
        legend: {
          type: "scroll",
          top: 4,
          left: 12,
          right: 8,
          tooltip: { show: true },
          formatter: (name) => truncateDisplayLabel(name, LEGEND_LABEL_LIMIT, 220),
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
          grid: { left: 68, right: 18, top: 54, bottom: this.xAxisLayout.gridBottom, containLabel: true },
          xAxis: {
            type: "value",
            name: this.xAxisLabel,
            nameLocation: "middle",
            nameGap: this.xAxisLayout.nameGap,
            axisLabel: this.xAxisLayout.axisLabel
          },
          yAxis: {
            type: "value",
            name: this.yAxisLabel,
            nameLocation: "middle",
            nameGap: this.yAxisLayout.nameGap,
            nameRotate: 90,
            axisLabel: this.yAxisLayout.axisLabel
          },
          series: this.yKeys.map((key) => ({
            name: seriesNameByKey[key] || key,
            type: "scatter",
            symbolSize: 8,
            data: this.rows.map((row, rowIndex) => ({
              value: [numeric(row[this.xKey]) ?? 0, numeric(row[key]) ?? 0],
              itemStyle: this.directionalKeys.includes(key)
                ? { color: trendColor(row[key]), borderColor: "#ffffff", borderWidth: 1 }
                : undefined,
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
        grid: { left: 68, right: 18, top: 54, bottom: this.xAxisLayout.gridBottom, containLabel: true },
        xAxis: {
          type: "category",
          name: this.xAxisLabel,
          nameLocation: "middle",
          nameGap: this.xAxisLayout.nameGap,
          data: this.rows.map((row, index) => compact(row[this.xKey] ?? `Row ${index + 1}`)),
          axisLabel: this.xAxisLayout.axisLabel
        },
        yAxis: {
          type: "value",
          name: this.yAxisLabel,
          nameLocation: "middle",
          nameGap: this.yAxisLayout.nameGap,
          nameRotate: 90,
          axisLabel: this.yAxisLayout.axisLabel
        },
        dataZoom: this.rows.length > 20 ? [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }] : [],
        series: this.yKeys.map((key, seriesIndex) => {
          const directional = this.directionalKeys.includes(key);
          const values = this.rows.map((row) => numeric(row[key]) ?? 0);
          const minimum = Math.min(0, ...values);
          const maximum = Math.max(0, ...values);
          return {
          name: seriesNameByKey[key] || key,
          type: this.chartType,
          smooth: this.chartType === "line",
          showSymbol: this.chartType === "line",
          symbolSize: this.chartType === "line" ? 7 : undefined,
          lineStyle: this.chartType === "line" ? { width: 3, color: PALETTE[seriesIndex % PALETTE.length] } : undefined,
          areaStyle: this.chartType === "line" && !directional
            ? { opacity: 0.08, color: PALETTE[seriesIndex % PALETTE.length] }
            : undefined,
          barMaxWidth: 34,
          emphasis: { focus: "series" },
          data: this.rows.map((row, rowIndex) => ({
            value: numeric(row[key]) ?? 0,
            itemStyle: directional || this.chartType === "line"
              ? {
                  color: directional
                    ? trendColor(row[key])
                    : trendColor(rowIndex === 0
                      ? 0
                      : (numeric(row[key]) ?? 0) - (numeric(this.rows[rowIndex - 1]?.[key]) ?? 0)),
                  borderColor: this.chartType === "line" ? "#ffffff" : undefined,
                  borderWidth: this.chartType === "line" ? 1.5 : undefined,
                  borderRadius: this.chartType === "bar" ? [4, 4, 0, 0] : 0
                }
              : { color: PALETTE[seriesIndex % PALETTE.length] },
            row,
            rowIndex,
            xKey: this.xKey,
            yKey: key
          })),
          markLine: directional ? {
            silent: true,
            symbol: "none",
            label: { show: true, formatter: "零轴", color: "#667085", fontSize: 11 },
            lineStyle: { color: "#98a2b3", width: 1, type: "dashed" },
            data: [{ yAxis: 0 }]
          } : undefined,
          markArea: directional && this.chartType === "line" ? {
            silent: true,
            data: [
              ...(maximum > 0 ? [[
                { yAxis: 0, itemStyle: { color: "rgba(229, 72, 77, 0.07)" } },
                { yAxis: maximum }
              ]] : []),
              ...(minimum < 0 ? [[
                { yAxis: minimum, itemStyle: { color: "rgba(22, 163, 106, 0.07)" } },
                { yAxis: 0 }
              ]] : [])
            ]
          } : undefined
        };
        })
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
          { key: "min-x", text: this.formatDataValue(this.minXValue), x: 48 },
          { key: "max-x", text: this.formatDataValue(this.maxXValue), x: 608 }
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
    if (typeof window !== "undefined") {
      window.addEventListener(TREND_SEMANTICS_UPDATED_EVENT, this.handleTrendSemanticsUpdated);
    }
    this.renderEchart();
  },
  beforeUnmount() {
    if (typeof window !== "undefined") {
      window.removeEventListener(TREND_SEMANTICS_UPDATED_EVENT, this.handleTrendSemanticsUpdated);
    }
    this.disposeEchart();
  },
  methods: {
    isRawDataPanelBlock(block = {}) {
      return isRawDataBlock(block);
    },
    rawDataBlockToggleLabel(block = {}) {
      const count = visualizationRowCount(block?.spec || {});
      return count > 0 ? `查看原始数据（${count} 行）` : "查看原始数据";
    },
    handleTrendSemanticsUpdated() {
      this.trendSemanticsRevision += 1;
      this.renderEchart();
    },
    renderEchart() {
      this.$nextTick(() => {
        const element = this.$refs.chartCanvas;
        if (!element || this.activeView !== "graph" || !this.chartOption) {
          this.disposeEchart();
          return;
        }
        this.syncChartViewportWidth(element.clientWidth);
        if (!this.chartInstance) {
          this.chartInstance = markRaw(echarts.init(element, null, { renderer: "canvas" }));
          this.chartInstance.on("click", this.handleChartClick);
          if (typeof ResizeObserver !== "undefined") {
            this.resizeObserver = new ResizeObserver((entries) => {
              this.syncChartViewportWidth(entries[0]?.contentRect?.width);
              this.chartInstance?.resize();
            });
            this.resizeObserver.observe(element);
          } else if (typeof window !== "undefined") {
            window.addEventListener("resize", this.resizeEchart);
          }
        }
        this.chartInstance.setOption(this.chartOption, true);
        this.chartInstance.resize();
      });
    },
    syncChartViewportWidth(width) {
      const normalizedWidth = Math.round(Number(width) || 0);
      if (normalizedWidth > 0 && Math.abs(normalizedWidth - this.chartViewportWidth) > 2) {
        this.chartViewportWidth = normalizedWidth;
      }
    },
    resizeEchart() {
      this.syncChartViewportWidth(this.$refs.chartCanvas?.clientWidth);
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
        const plottedValue = Array.isArray(data.value) ? data.value[1] : data.value;
        const value = Object.prototype.hasOwnProperty.call(row, key) ? row[key] : plottedValue;
        lines.push(`${escapeHtml(name)}：${escapeHtml(this.formatDataValue(value))}`);
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
    formatDataValue(value) {
      return formatDataValue(value);
    }
  }
};
