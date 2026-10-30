import { defineComponent } from "vue";

// Older conversations retain their original plan. Adapt only its display text.
function planText(value) {
  return String(value ?? "")
    .replace(/\bCline\b/gi, "AI 助手")
    .replace(/\bRuntime(?: OS)?\b/gi, "系统");
}

export default defineComponent({
  name: "StudioCodePlan",
  props: { plan: Object, state: { type: String, default: "exploring" } },
  methods: { planText },
});
