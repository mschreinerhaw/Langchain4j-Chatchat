import { defineComponent, ref, onMounted } from "vue";

export default defineComponent({
  name: "AppConfirmDialog",
  props: { title: String, message: String },
  emits: ["cancel", "confirm"],
  setup(_props, { expose: exposeComponent }) {
    exposeComponent();
    const dialog = ref(null);
    onMounted(() => dialog.value.showModal());
    return { ref, dialog };
  },
});
