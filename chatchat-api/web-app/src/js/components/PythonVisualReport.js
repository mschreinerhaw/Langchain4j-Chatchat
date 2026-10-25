import { computed, defineComponent, h, ref, watch, onBeforeUnmount } from "vue";
import { reviewArchivedVisualReport } from "../utils/pythonVisualReport.js";
import PythonReportChart from "../../components/PythonReportChart.vue";
import { renderReportNarrative } from "../utils/reportNarrative.js";

const ReportTable = defineComponent({
  props: { table: Object },
  setup: (props) => () =>
    h("div", { class: "python-report-table" }, [
      h("table", {}, [
        h("caption", {}, props.table.title),
        h("thead", {}, [
          h(
            "tr",
            {},
            props.table.columns.map((column) => h("th", {}, String(column)))
          ),
        ]),
        h(
          "tbody",
          {},
          props.table.rows.map((row) =>
            h(
              "tr",
              {},
              row.map((cell) => h("td", {}, cell == null ? "—" : String(cell)))
            )
          )
        ),
      ]),
      props.table.scope ? h("p", {}, props.table.scope) : null,
    ]),
});

export default defineComponent({
  name: "PythonVisualReport",
  components: { PythonReportChart, ReportTable },
  props: {
    report: { type: Object, default: null },
    fallbackMarkdown: { type: String, default: "" },
  },
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;
    const reviewedReport = ref(null);
    const report = computed(() => reviewedReport.value || props.report);
    let reviewRequest;
    watch(() => props.report, value => {
      reviewedReport.value = null;
      reviewRequest?.abort();
      reviewRequest = null;
      if (value?.status !== 'FAILED' || value.artifactValidationStatus
          || !value.execution?.resultDatasetRef || !value.execution?.codeArtifact) return;
      const controller = new AbortController();
      reviewRequest = controller;
      reviewArchivedVisualReport(value, controller.signal).then(result => {
        if (reviewRequest === controller) reviewedReport.value = result;
      }).catch(() => { /* Preserve the original result when archive verification fails. */ });
    }, { immediate: true });
    onBeforeUnmount(() => reviewRequest?.abort());
    const showSectionText = section => String(section.title || '').trim() !== '图表分析';
    const chartFailures = ref({});
    function chartStatus(event) {
      if (event.error) chartFailures.value = { ...chartFailures.value, [event.id]: event.error };
      else {
        const next = { ...chartFailures.value };
        delete next[event.id];
        chartFailures.value = next;
      }
    }
    watch(
      () => props.report,
      () => {
        chartFailures.value = {};
      }
    );
    const deliveryStatus = computed(() =>
      Object.keys(chartFailures.value).length && report.value?.status === "SUCCESS"
        ? "PARTIAL"
        : report.value?.status || "LOADING"
    );
    const statusLabel = computed(
      () =>
        ({
          SUCCESS: "分析完成",
          PARTIAL: "部分完成",
          FAILED: "图文报告暂不可用",
          NO_DATA: "没有可分析数据",
        })[deliveryStatus.value] || "正在生成"
    );
    const chartsFor = (ids) =>
      (report.value?.charts || []).filter((chart) => (ids || []).includes(chart.id));
    const tablesFor = (ids) =>
      (report.value?.tables || []).filter((table) => (ids || []).includes(table.id));
    const looseCharts = computed(() =>
      (report.value?.charts || []).filter(
        (chart) =>
          !(report.value?.sections || []).some((section) => section.chartIds?.includes(chart.id))
      )
    );
    const looseTables = computed(() =>
      (report.value?.tables || []).filter(
        (table) =>
          !(report.value?.sections || []).some((section) => section.tableIds?.includes(table.id))
      )
    );
    return {
      renderReportNarrative,
      report,
      showSectionText,
      chartStatus,
      deliveryStatus,
      statusLabel,
      chartsFor,
      tablesFor,
      looseCharts,
      looseTables,
    };
  },
});
