import { defineComponent } from "vue";

export default defineComponent({
  name: "AsyncViewState",
  props: {
    error: {
      type: Error,
      default: null,
    },
  },
});
