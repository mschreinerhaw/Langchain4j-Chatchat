import { defineComponent } from "vue";

export default defineComponent({
  name: "StudioCodePlan",
  props: { plan: Object, state: { type: String, default: "exploring" } },
});
