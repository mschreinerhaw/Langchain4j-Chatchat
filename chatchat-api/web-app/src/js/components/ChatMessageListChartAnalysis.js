import { ChevronDown, ChevronRight, Maximize2, Minimize2, Pin, PinOff, X } from "@lucide/vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";

function parseChartNumber(value) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const parsed = Number(String(value ?? "").replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

function makeDatasetId(index) {
  return `dataset_${index + 1}`;
}

function normalizeChartDataset(data = {}, index = 0) {
  const columns = Array.isArray(data.columns) ? data.columns : [];
  return {
    id: data.id || makeDatasetId(index),
    title: data.title || `数据集 ${index + 1}`,
    columns,
    rows: Array.isArray(data.rows) ? data.rows : [],
    chartType: data.chartType || "bar",
    xKey: data.xKey || columns[0] || "",
    yKey: data.yKey || columns[1] || columns[0] || "",
    groupKey: data.groupKey || "",
    selectedColumns: Array.isArray(data.selectedColumns) ? data.selectedColumns.filter((column) => columns.includes(column)) : [...columns]
  };
}

export default {
  components: {
    ChevronDown,
    ChevronRight,
    Maximize2,
    Minimize2,
    Pin,
    PinOff,
    VisualizationRenderer,
    X
  },
  data() {
    return {
      chartAnalysisModal: null,
      chartAnalysisFloating: false,
      chartAnalysisFullscreen: false,
      chartAnalysisSettingsOpen: false,
      chartAnalysisColumnsOpen: false,
      chartAnalysisFloatingPosition: { x: 0, y: 0 },
      chartAnalysisDragState: null
    };
  },
  beforeUnmount() {
    this.stopChartAnalysisDrag();
  },
  methods: {
    openChartAnalysisModal(payload) {
      try {
        const data = JSON.parse(decodeURIComponent(payload || ""));
        const rawDatasets = Array.isArray(data.datasets) && data.datasets.length
          ? data.datasets
          : [data];
        const datasets = rawDatasets
          .map((item, index) => normalizeChartDataset(item, index))
          .filter((item) => item.columns.length && item.rows.length);
        if (!datasets.length) {
          return;
        }
        this.chartAnalysisModal = {
          title: data.title || "查询结果图形化分析",
          datasets,
          activeDatasetId: data.activeDatasetId || datasets[0].id
        };
        this.chartAnalysisFloating = false;
        this.chartAnalysisFullscreen = false;
        this.chartAnalysisSettingsOpen = false;
        this.chartAnalysisColumnsOpen = false;
        this.chartAnalysisFloatingPosition = { x: 0, y: 0 };
        this.stopChartAnalysisDrag();
      } catch (error) {
        console.warn("Open chart analysis failed", error);
      }
    },
    closeChartAnalysisModal() {
      this.chartAnalysisModal = null;
      this.chartAnalysisFloating = false;
      this.chartAnalysisFullscreen = false;
      this.chartAnalysisSettingsOpen = false;
      this.chartAnalysisColumnsOpen = false;
      this.chartAnalysisFloatingPosition = { x: 0, y: 0 };
      this.stopChartAnalysisDrag();
    },
    toggleChartAnalysisFloating() {
      this.chartAnalysisFloating = !this.chartAnalysisFloating;
      if (this.chartAnalysisFloating) {
        this.chartAnalysisFullscreen = false;
        this.ensureChartAnalysisFloatingPosition();
      } else {
        this.stopChartAnalysisDrag();
      }
    },
    toggleChartAnalysisFullscreen() {
      this.chartAnalysisFullscreen = !this.chartAnalysisFullscreen;
      if (this.chartAnalysisFullscreen) {
        this.chartAnalysisFloating = false;
        this.stopChartAnalysisDrag();
      }
    },
    toggleChartAnalysisSettings() {
      this.chartAnalysisSettingsOpen = !this.chartAnalysisSettingsOpen;
    },
    toggleChartAnalysisColumnsPanel() {
      this.chartAnalysisColumnsOpen = !this.chartAnalysisColumnsOpen;
    },
    chartAnalysisActiveDataset() {
      const datasets = this.chartAnalysisModal?.datasets || [];
      return datasets.find((item) => item.id === this.chartAnalysisModal?.activeDatasetId) || datasets[0] || null;
    },
    chartAnalysisDatasetCount() {
      return this.chartAnalysisModal?.datasets?.length || 0;
    },
    setChartAnalysisDataset(datasetId) {
      if (!this.chartAnalysisModal || this.chartAnalysisModal.activeDatasetId === datasetId) {
        return;
      }
      this.chartAnalysisModal = {
        ...this.chartAnalysisModal,
        activeDatasetId: datasetId
      };
      this.chartAnalysisSettingsOpen = false;
      this.chartAnalysisColumnsOpen = false;
    },
    chartAnalysisWindowStyle() {
      if (!this.chartAnalysisFloating || this.chartAnalysisFullscreen) {
        return {};
      }
      return {
        left: `${this.chartAnalysisFloatingPosition.x}px`,
        top: `${this.chartAnalysisFloatingPosition.y}px`
      };
    },
    ensureChartAnalysisFloatingPosition() {
      const width = Math.min(760, Math.max(460, window.innerWidth - 36));
      const x = Math.max(18, window.innerWidth - width - 18);
      const y = 18;
      this.chartAnalysisFloatingPosition = this.clampChartAnalysisFloatingPosition({ x, y });
    },
    startChartAnalysisDrag(event) {
      if (!this.chartAnalysisFloating || this.chartAnalysisFullscreen || event.button !== 0) {
        return;
      }
      if (event.target?.closest?.("button, select, input, label, a")) {
        return;
      }
      this.chartAnalysisDragState = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        originX: this.chartAnalysisFloatingPosition.x,
        originY: this.chartAnalysisFloatingPosition.y
      };
      event.currentTarget?.setPointerCapture?.(event.pointerId);
      window.addEventListener("pointermove", this.moveChartAnalysisDrag);
      window.addEventListener("pointerup", this.stopChartAnalysisDrag);
      window.addEventListener("pointercancel", this.stopChartAnalysisDrag);
      event.preventDefault();
    },
    moveChartAnalysisDrag(event) {
      const state = this.chartAnalysisDragState;
      if (!state) {
        return;
      }
      const next = {
        x: state.originX + event.clientX - state.startX,
        y: state.originY + event.clientY - state.startY
      };
      this.chartAnalysisFloatingPosition = this.clampChartAnalysisFloatingPosition(next);
    },
    stopChartAnalysisDrag() {
      this.chartAnalysisDragState = null;
      window.removeEventListener("pointermove", this.moveChartAnalysisDrag);
      window.removeEventListener("pointerup", this.stopChartAnalysisDrag);
      window.removeEventListener("pointercancel", this.stopChartAnalysisDrag);
    },
    clampChartAnalysisFloatingPosition(position = {}) {
      const margin = 12;
      const width = Math.min(760, Math.max(460, window.innerWidth - 36));
      const height = Math.min(620, Math.max(420, window.innerHeight - 36));
      return {
        x: Math.min(Math.max(margin, Number(position.x) || margin), Math.max(margin, window.innerWidth - width - margin)),
        y: Math.min(Math.max(margin, Number(position.y) || margin), Math.max(margin, window.innerHeight - height - margin))
      };
    },
    toggleChartAnalysisColumn(column) {
      const dataset = this.chartAnalysisActiveDataset();
      if (!dataset) {
        return;
      }
      const current = new Set(dataset.selectedColumns || []);
      if (current.has(column)) {
        current.delete(column);
      } else {
        current.add(column);
      }
      this.updateChartAnalysisDataset({
        selectedColumns: dataset.columns.filter((item) => current.has(item))
      });
    },
    selectAllChartAnalysisColumns() {
      const dataset = this.chartAnalysisActiveDataset();
      if (!dataset) {
        return;
      }
      this.updateChartAnalysisDataset({
        selectedColumns: [...dataset.columns]
      });
    },
    clearChartAnalysisColumns() {
      this.updateChartAnalysisDataset({ selectedColumns: [] });
    },
    chartAnalysisProjectedRow(row = {}) {
      const dataset = this.chartAnalysisActiveDataset();
      const selected = dataset?.selectedColumns?.length
        ? dataset.selectedColumns
        : (dataset?.columns || []);
      return Object.fromEntries(selected.map((column) => [column, row?.[column]]));
    },
    buildChartAnalysisDrillEvent(event = {}) {
      const modal = this.chartAnalysisModal;
      const dataset = this.chartAnalysisActiveDataset();
      if (!modal || !dataset) {
        return event;
      }
      const selection = event.selection || {};
      const row = selection.row && typeof selection.row === "object" ? selection.row : null;
      const selectedColumns = dataset.selectedColumns.length
        ? dataset.selectedColumns
        : dataset.columns;
      const rows = row
        ? [this.chartAnalysisProjectedRow(row)]
        : dataset.rows.slice(0, 20).map((item) => this.chartAnalysisProjectedRow(item));
      return {
        ...event,
        title: event.title || dataset.title || modal.title,
        panelTitle: dataset.title || modal.title || event.panelTitle || "查询结果明细",
        analysisType: event.analysisType || "tool_result_rows",
        datasetId: dataset.id,
        datasetName: dataset.title,
        selectedColumns,
        selectedRows: rows,
        selection: {
          ...selection,
          datasetId: dataset.id,
          datasetName: dataset.title,
          row: row ? this.chartAnalysisProjectedRow(row) : selection.row
        }
      };
    },
    handleChartAnalysisDrillDown(event = {}) {
      this.$emit("visualization-drill-down", {
        message: null,
        event: this.buildChartAnalysisDrillEvent(event)
      });
    },
    drillDownChartAnalysis() {
      const dataset = this.chartAnalysisActiveDataset();
      this.handleChartAnalysisDrillDown({
        title: dataset?.title || this.chartAnalysisModal?.title || "查询结果明细",
        panelTitle: dataset?.title || this.chartAnalysisModal?.title || "查询结果明细",
        analysisType: "tool_result_rows",
        selection: {}
      });
    },
    updateChartAnalysisDataset(patch = {}) {
      const modal = this.chartAnalysisModal;
      const dataset = this.chartAnalysisActiveDataset();
      if (!modal || !dataset) {
        return;
      }
      this.chartAnalysisModal = {
        ...modal,
        datasets: modal.datasets.map((item) => item.id === dataset.id ? { ...item, ...patch } : item)
      };
    },
    setChartField(key, value) {
      if (!this.chartAnalysisActiveDataset()) {
        return;
      }
      this.updateChartAnalysisDataset({ [key]: value });
    },
    chartTypeOptions() {
      return [
        { value: "bar", label: "柱状图" },
        { value: "line", label: "折线图" },
        { value: "scatter", label: "散点图" },
        { value: "pie", label: "饼图" },
        { value: "table", label: "表格" }
      ];
    },
    chartAnalysisSpec() {
      const modal = this.chartAnalysisActiveDataset();
      if (!modal) {
        return null;
      }
      const chartType = modal.chartType || "bar";
      const xKey = modal.xKey || modal.columns[0] || "";
      const yKey = modal.yKey || modal.columns.find((column) => column !== xKey) || modal.columns[0] || "";
      if (chartType === "table") {
        return {
          version: "v1",
          type: "table",
          title: modal.title,
          dataset: {
            columns: modal.columns,
            rows: modal.rows
          },
          ui: { defaultView: "table" }
        };
      }
      return {
        version: "v1",
        type: "chart",
        chartType,
        title: modal.title,
        dataset: {
          columns: modal.columns,
          xKey,
          xLabel: xKey,
          series: this.chartAnalysisSeries(modal, chartType, xKey, yKey),
          rows: this.chartAnalysisRows(modal, chartType, xKey, yKey)
        },
        insight: {
          summary: this.chartAnalysisSemanticSummary()
        },
        ui: { defaultView: "chart" }
      };
    },
    chartAnalysisSeries(modal, chartType, xKey, yKey) {
      if (!modal || !yKey) {
        return [];
      }
      if (modal.groupKey && !["pie", "scatter"].includes(chartType)) {
        return [...new Set(modal.rows.map((row) => String(row[modal.groupKey] ?? "未分组")))]
          .map((group) => ({ name: `${modal.groupKey}=${group} / ${yKey}`, yKey: group }));
      }
      return [{ name: yKey, yKey }];
    },
    chartAnalysisSemanticSummary() {
      const modal = this.chartAnalysisActiveDataset();
      if (!modal) {
        return "";
      }
      const chartTypeLabel = this.chartTypeOptions().find((item) => item.value === modal.chartType)?.label || modal.chartType;
      if (modal.chartType === "table") {
        return `当前以表格查看：${modal.rows.length} 行，${modal.columns.length} 个字段。`;
      }
      const groupText = modal.groupKey ? `；分组：${modal.groupKey}` : "；不分组";
      const yText = modal.chartType === "pie" ? `扇区大小：${modal.yKey}` : `Y 轴 / 指标：${modal.yKey}`;
      return `图表类型：${chartTypeLabel}；X 轴 / 维度：${modal.xKey}；${yText}${groupText}`;
    },
    chartAnalysisRows(modal, chartType, xKey, yKey) {
      if (!modal || !Array.isArray(modal.rows)) {
        return [];
      }
      if (!xKey || !yKey) {
        return modal.rows;
      }
      if (modal.groupKey && !["pie", "scatter"].includes(chartType)) {
        const rowsByX = new Map();
        modal.rows.forEach((row) => {
          const xValue = String(row[xKey] ?? "未命名");
          const group = String(row[modal.groupKey] ?? "未分组");
          const target = rowsByX.get(xValue) || { [xKey]: xValue };
          target[group] = (parseChartNumber(target[group]) ?? 0) + (parseChartNumber(row[yKey]) ?? 0);
          rowsByX.set(xValue, target);
        });
        return [...rowsByX.values()];
      }
      if (chartType === "scatter") {
        return modal.rows.map((row) => ({
          ...row,
          [xKey]: parseChartNumber(row[xKey]) ?? 0,
          [yKey]: parseChartNumber(row[yKey]) ?? 0
        }));
      }
      if (chartType === "pie") {
        const totals = new Map();
        modal.rows.forEach((row) => {
          const label = String(row[xKey] ?? "未分组");
          totals.set(label, (totals.get(label) || 0) + (parseChartNumber(row[yKey]) ?? 0));
        });
        return [...totals.entries()].map(([label, value]) => ({ [xKey]: label, [yKey]: value }));
      }
      return modal.rows.map((row) => ({
        ...row,
        [yKey]: parseChartNumber(row[yKey]) ?? 0
      }));
    }
  }
};
