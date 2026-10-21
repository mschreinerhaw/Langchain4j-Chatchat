import { defineComponent } from "vue";

export default defineComponent({
  name: "PythonCodeDiff",
  props: {
    diff: { type: Object, required: true },
    action: { type: String, default: "optimize" },
    request: { type: String, default: "" },
    scope: { type: String, default: "当前脚本" },
  },
});
