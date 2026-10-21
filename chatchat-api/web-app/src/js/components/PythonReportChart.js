import { defineComponent, ref, onMounted, onBeforeUnmount, watch } from "vue";
import plotlyRuntime from "plotly.js-dist-min/plotly.min.js?url";
import {
  readReportArtifact,
  checkedPlotlyFigure,
  plotlyFrameDocument,
} from "../utils/pythonVisualReport";

export default defineComponent({
  name: "PythonReportChart",
  props: { chart: { type: Object, required: true } },
  emits: ["status"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    exposeComponent();
    const props = componentProps;
    const emit = emitEvent;
    const container = ref(null),
      frame = ref(null),
      imageUrl = ref(""),
      frameDocument = ref(""),
      loading = ref(false),
      error = ref("");
    let controller,
      observer,
      figure,
      serial = 0,
      renderTimeout,
      renderStarted = 0;
    watch(error, (value) => emit("status", { id: props.chart.id, error: value }));
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
        const bytes = await readReportArtifact(props.chart.artifact, controller.signal);
        if (turn !== serial) return;
        if (props.chart.kind === "plotly") {
          figure = checkedPlotlyFigure(JSON.parse(new TextDecoder().decode(bytes)));
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
      frame.value?.contentWindow?.postMessage({ type: "runtime-plotly-figure", figure }, "*");
      clearTimeout(renderTimeout);
      renderTimeout = setTimeout(() => {
        if (loading.value) {
          error.value = "交互图渲染超时，原始分析结果仍保留";
          loading.value = false;
        }
      }, 20000);
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
      () => props.chart.artifact?.fileId,
      () => {
        clear();
        void load();
      }
    );
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
      figure,
      load,
      sendFigure,
    };
  },
});
