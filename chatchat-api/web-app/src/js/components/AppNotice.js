import { defineComponent } from "vue";
import { CircleAlert, CircleCheck, Info, X } from "@lucide/vue";

export default defineComponent({
  name: "AppNotice",
  components: { CircleAlert, CircleCheck, Info, X },
  props: { kind: { type: String, default: "info" }, dismissible: Boolean },
  emits: ["close"],
});
