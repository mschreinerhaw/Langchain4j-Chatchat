import { defineComponent, computed } from "vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";

export default defineComponent({
  name: "AnalyticalReport",
  components: { VisualizationRenderer },
  props: {
    report: { type: Object, default: () => ({}) },
    preferences: { type: Object, default: () => ({}) },
  },
  emits: ["drill-down", "preference-change"],
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;

    const blocks = computed(() =>
      props.report?.schemaVersion === "analytical_report.v1" && Array.isArray(props.report.blocks)
        ? props.report.blocks
        : []
    );
    const primaryBlocks = computed(() =>
      blocks.value
        .filter(
          (block) =>
            block.presentation?.primaryConclusion &&
            block.presentation?.validationStatus === "VERIFIED_DATA_BOUND"
        )
        .slice(0, 5)
    );
    const sectionLabel = (section) =>
      ({
        CORE: "核心发现",
        DEEP_DIVE: "关键发现",
        OVERALL: "结构拆解",
        KEY_DRIVER: "驱动因素",
        RISK_OPPORTUNITY: "业务含义",
        LIMITATION: "数据边界",
        ACTION: "下一步",
      })[section] || "分析发现";
    return { blocks, primaryBlocks, sectionLabel };
  },
});
