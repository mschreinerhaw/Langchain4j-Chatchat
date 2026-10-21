import { defineComponent, computed } from "vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";
import { splitReportVisualizations } from "../utils/reportVisualization.js";
import { applyReportChartPreference } from "../utils/reportChartPreferences.js";
import { splitThinkBlocks } from "../utils/thinkBlocks.js";

export default defineComponent({
  name: "ReportMarkdown",
  components: { VisualizationRenderer },
  props: {
    content: { type: String, default: "" },
    renderMarkdown: { type: Function, required: true },
    collapseThinking: { type: Boolean, default: false },
    preferences: { type: Object, default: () => ({}) },
  },
  emits: ["drill-down", "preference-change"],
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;

    const parts = computed(() =>
      (props.collapseThinking
        ? splitThinkBlocks(props.content)
        : [{ type: "text", content: props.content }]
      ).flatMap((block) =>
        block.type === "thinking" ? [block] : splitReportVisualizations(block.content)
      )
    );
    return { applyReportChartPreference, parts };
  },
});
