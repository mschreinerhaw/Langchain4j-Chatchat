import { defineComponent, ref, onMounted, onBeforeUnmount, onActivated, onDeactivated, nextTick, watch } from "vue";
import plotlyRuntime from "plotly.js-dist-min/plotly.min.js?url";
import { explorationFigure, explorationChartStyle, explorationChartTypes, explorationChartTable } from '../utils/explorationPresentation';
import {
  readReportArtifact,
  checkedPlotlyFigure,
  plotlyDataTable,
  plotlyFrameDocument,
} from "../utils/pythonVisualReport";

export default defineComponent({
  name: "PythonReportChart",
  props: { chart: { type: Object, required: true }, presentation: { type: String, default: '' }, compact:{type:Boolean,default:false} },
  emits: ["status","ready"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    exposeComponent();
    const props = componentProps;
    const emit = emitEvent;
    const container = ref(null),
      frame = ref(null),
      imageUrl = ref(""),
      frameDocument = ref(""),
      loading = ref(false),
      error = ref(""),fallbackTable=ref(null),empty=ref(false);
    let controller,
      observer,
      figure,originalFigure,
      serial = 0,
      renderTimeout,
      renderStarted = 0;
    watch(error, (value,previous) => {
      emit("status", { id: props.chart.id, error: value });
      if(value || previous)window.dispatchEvent(new CustomEvent('runtime:report-presentation-diagnostic',{detail:{stage:'CHART_RENDER',
        code:value?'CHART_RENDER_FAILED':'CHART_RENDER_RECOVERED',chartId:props.chart.id,recovered:!value,message:value}}));
    });
    function recordTiming() {
      if (!renderStarted) return;
      performance.measure?.("runtime-report-chart-render", {
        start: renderStarted,
        end: performance.now(),
      });
      window.dispatchEvent(
        new CustomEvent("runtime:report-chart-rendered", {
          detail: {
            fileId: props.chart.artifact?.fileId,
            kind: props.chart.kind,
            durationMs: Math.round(performance.now() - renderStarted),
          },
        })
      );
      renderStarted = 0;
    }
    function clear() {
      serial++;
      controller?.abort();
      clearTimeout(renderTimeout);
      if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
      imageUrl.value = "";
      frameDocument.value = "";
      figure = null;
      originalFigure=null;
      fallbackTable.value=null;
      empty.value=false;
      loading.value = false;
    }
    async function load() {
      if (loading.value) return;
      clear();
      const turn = serial;
      controller = new AbortController();
      error.value = "";
      loading.value = true;
      renderStarted = performance.now();
      try {
        if(props.chart.renderError)throw new Error('此图表产物暂不可用，可查看正文及数据表。');
        const bytes = await readReportArtifact(props.chart.artifact, controller.signal);
        if (turn !== serial) return;
        if (props.chart.kind === "plotly") {
          originalFigure = checkedPlotlyFigure(JSON.parse(new TextDecoder().decode(bytes)));
          emit('ready',{id:props.chart.id,fileId:props.chart.artifact?.fileId,figure:originalFigure,compatibleTypes:explorationChartTypes(originalFigure),table:explorationChartTable(originalFigure)});
          figure = present(originalFigure);
          fallbackTable.value=plotlyDataTable(figure);
          if(figure.data.every(trace=>['scatter','bar','pie','histogram','box'].includes(trace.type || 'scatter'))
              && !figure.data.some(trace=>['x','y','values'].some(key=>Array.isArray(trace[key])&&trace[key].length))){empty.value=true;loading.value=false;recordTiming();return;}
          frameDocument.value = plotlyFrameDocument(plotlyRuntime, window.location.href);
        } else {
          const media = { png: "image/png", svg: "image/svg+xml" }[props.chart.kind];
          if (!media || props.chart.artifact.mediaType !== media)
            throw new Error("不支持的静态图表");
          imageUrl.value = URL.createObjectURL(new Blob([bytes], { type: media }));
          loading.value = false;
          recordTiming();
        }
      } catch (ex) {
        if (turn === serial && ex.name !== "AbortError") {
          error.value = ex.message || "图表读取失败";
          loading.value = false;
        }
      }
    }
    function sendFigure() {
      if (!figure) return;
      if(frame.value)delete frame.value.dataset.pdfImage;
      frame.value?.contentWindow?.postMessage({ type: "runtime-plotly-figure", figure }, "*");
      clearTimeout(renderTimeout);
      renderTimeout = setTimeout(() => {
        if (loading.value) {
          error.value = "交互图渲染超时，原始分析结果仍保留";
          loading.value = false;
        }
      }, 20000);
    }
    function present(original){const value=explorationFigure(original,props.presentation);return props.compact?explorationChartStyle(value):value;}
    function updatePresentation(){
      if(props.chart.kind!=='plotly'||!originalFigure)return;
      try{figure=present(originalFigure);error.value='';loading.value=true;renderStarted=performance.now();sendFigure();}
      catch(ex){error.value=ex.message;loading.value=false;}
    }
    function rendered(event) {
      if (event.source !== frame.value?.contentWindow) return;
      if (event.data?.type === "runtime-plotly-rendered") {
        clearTimeout(renderTimeout);
        loading.value = false;
        recordTiming();
      }
      if (
        event.data?.type === "runtime-plotly-snapshot" &&
        /^data:image\/png;base64,/.test(event.data.image || "")
      ) {
        frame.value.dataset.pdfImage = event.data.image;
      }
      if (event.data?.type === "runtime-plotly-failed") {
        clearTimeout(renderTimeout);
        error.value = "交互图渲染失败，原始分析结果仍保留";
        loading.value = false;
        frameDocument.value='';
      }
      if(event.data?.type === 'runtime-plotly-unsupported'){
        clearTimeout(renderTimeout);loading.value=false;frameDocument.value='';
        error.value=fallbackTable.value?'此图表类型暂不支持，已保留同一数据的表格。':'此图表类型暂不支持，其他报告内容仍保留。';
      }
    }
    function prepareExport(event) {
      event.detail.tasks.push(
        (async () => {
          if (!imageUrl.value && !frameDocument.value) await load();
          const deadline = Date.now() + 25000;
          while (loading.value || (frameDocument.value && !frame.value?.dataset.pdfImage)) {
            if (error.value || Date.now() > deadline) throw new Error("图表暂时无法导出，请重试");
            await new Promise((resolve) => setTimeout(resolve, 100));
          }
          if (error.value) throw new Error("图表暂时无法导出，请重试");
        })()
      );
    }
    onMounted(() => container.value?.addEventListener("report:prepare-export", prepareExport));
    onBeforeUnmount(() =>
      container.value?.removeEventListener("report:prepare-export", prepareExport)
    );
    onMounted(() => {
      window.addEventListener("message", rendered);
      if (typeof IntersectionObserver === "function") {
        observer = new IntersectionObserver(
          (entries) => {
            if (entries.some((item) => item.isIntersecting)) {
              observer.disconnect();
              void load();
            }
          },
          { rootMargin: "120px" }
        );
        observer.observe(container.value);
      } else void load();
    });
    watch(
      () => JSON.stringify([props.chart.id,props.chart.kind,props.chart.renderError,props.chart.artifact?.fileId,props.chart.artifact?.size,props.chart.artifact?.sha256,props.chart.artifact?.mediaType]),
      () => {
        clear();
        void load();
      }
    );
    watch(()=>[props.presentation,props.compact],updatePresentation);
    onDeactivated(()=>clearTimeout(renderTimeout));
    onActivated(()=>{if(figure&&frameDocument.value){error.value='';loading.value=true;nextTick(sendFigure);}});
    onBeforeUnmount(() => {
      clear();
      observer?.disconnect();
      window.removeEventListener("message", rendered);
    });
    return {
      ref,
      container,
      frame,
      imageUrl,
      frameDocument,
      loading,
      error,
      fallbackTable,
      empty,
      figure,
      load,
      sendFigure,
    };
  },
});
