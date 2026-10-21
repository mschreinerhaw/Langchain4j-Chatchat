import { computed, defineComponent, h, ref, watch } from "vue";
import MarkdownIt from "markdown-it";
import ReportMarkdown from "../../components/ReportMarkdown.vue";
import PythonReportChart from "../../components/PythonReportChart.vue";

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
  components: { PythonReportChart, ReportTable, ReportMarkdown },
  props: {
    report: { type: Object, default: null },
    fallbackMarkdown: { type: String, default: "" },
  },
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;
    const markdown = computed(() => props.report?.markdown || props.fallbackMarkdown);
    const md = new MarkdownIt({ html: false, breaks: true, linkify: false });
    md.disable("image");
    const renderMarkdown = (value) => md.render(String(value || ""));
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
      Object.keys(chartFailures.value).length && props.report?.status === "SUCCESS"
        ? "PARTIAL"
        : props.report?.status || "LOADING"
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
      (props.report?.charts || []).filter((chart) => (ids || []).includes(chart.id));
    const tablesFor = (ids) =>
      (props.report?.tables || []).filter((table) => (ids || []).includes(table.id));
    const looseCharts = computed(() =>
      (props.report?.charts || []).filter(
        (chart) =>
          !(props.report?.sections || []).some((section) => section.chartIds?.includes(chart.id))
      )
    );
    const looseTables = computed(() =>
      (props.report?.tables || []).filter(
        (table) =>
          !(props.report?.sections || []).some((section) => section.tableIds?.includes(table.id))
      )
    );
    return {
      markdown,
      renderMarkdown,
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
