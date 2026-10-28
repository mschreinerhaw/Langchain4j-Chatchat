import { computed, defineComponent, h, ref, watch, onBeforeUnmount, useId } from "vue";
import { reviewArchivedVisualReport } from "../utils/pythonVisualReport.js";
import PythonReportChart from "../../components/PythonReportChart.vue";
import { renderReportNarrative } from "../utils/reportNarrative.js";
import { reportLayout } from '../utils/reportLayout.js';

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
  name: "ReportRenderer",
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
    const layout = computed(() => reportLayout(report.value || {}));
    const reportId = `report-${useId()}`;
    const contents = computed(() => layout.value.blocks.filter(block=>block.type==='section'&&block.value?.title));
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
    const showSectionText = section => Boolean(report.value?.layout) || String(section.title || '').trim() !== '图表分析';
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
    return {
      renderReportNarrative,
      report,
      layout,
      reportId,
      contents,
      scrollToBlock: block => document.getElementById(`${reportId}-${block.id}`)?.scrollIntoView({behavior:'smooth',block:'start'}),
      showSectionText,
      chartStatus,
      deliveryStatus,
      statusLabel,
    };
  },
});
