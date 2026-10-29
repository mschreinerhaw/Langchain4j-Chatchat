import { computed, defineComponent, h, ref, watch, onBeforeUnmount, useId } from "vue";
import { reviewArchivedVisualReport } from "../utils/pythonVisualReport.js";
import PythonReportChart from "../../components/PythonReportChart.vue";
import { renderReportNarrative } from "../utils/reportNarrative.js";
import { reportLayout } from '../utils/reportLayout.js';

const ReportTable = defineComponent({
  props: { table: Object },
  setup: (props) => () =>
    !Array.isArray(props.table?.columns) || !Array.isArray(props.table?.rows) || props.table.rows.some(row=>!Array.isArray(row)||row.length!==props.table.columns.length)
      ? h('p',{role:'alert'},'此表格的数据格式暂不可用，其他报告内容仍保留。')
      : h("div", { class: "python-report-table" }, [
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
    watch(() => [props.report,props.report?.execution?.resultSha256,props.report?.status,props.report?.layoutValidationStatus], ([value]) => {
      reviewedReport.value = null;
      reviewRequest?.abort();
      reviewRequest = null;
      if (!((value?.status === 'FAILED' && !value.artifactValidationStatus) || value?.layoutValidationStatus === 'FAILED')
          || !value.execution?.resultDatasetRef || !value.execution?.codeArtifact) return;
      const controller = new AbortController();
      reviewRequest = controller;
      reviewArchivedVisualReport(value, controller.signal).then(result => {
        if (reviewRequest === controller) reviewedReport.value = result;
      }).catch(error => { if(error.name!=='AbortError')window.dispatchEvent(new CustomEvent('runtime:report-presentation-diagnostic',{detail:{stage:'ARCHIVE_REVIEW',code:'REVIEW_UNAVAILABLE',recovered:false}})); });
    }, { immediate: true });
    onBeforeUnmount(() => reviewRequest?.abort());
    const showSectionText = section => Boolean(report.value?.layout) || String(section.title || '').trim() !== '图表分析';
    const chartFailures = ref({});
    const diagnosticKeys=new Set();
    watch(()=>layout.value.diagnostics,diagnostics=>{
      for(const diagnostic of diagnostics){const key=JSON.stringify(diagnostic);if(diagnosticKeys.has(key))continue;diagnosticKeys.add(key);
        window.dispatchEvent(new CustomEvent('runtime:report-presentation-diagnostic',{detail:diagnostic}));}
    },{immediate:true});
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
      (report.value?.charts || []).some(chart=>chartFailures.value[chart.id] || chart.renderError) && report.value?.status === "SUCCESS"
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
      showStatusNotice:computed(()=>['PARTIAL','FAILED','NO_DATA'].includes(report.value?.analysisStatus || report.value?.status)),
      chartStatus,
      deliveryStatus,
      statusLabel,
    };
  },
});
