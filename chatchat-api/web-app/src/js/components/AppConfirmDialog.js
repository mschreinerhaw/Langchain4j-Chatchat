import { defineComponent, ref, onMounted, onBeforeUnmount } from "vue";

export default defineComponent({
  name: "AppConfirmDialog",
  props: {
    title: String,
    message: String,
    busy: Boolean,
    error: String,
    cancelLabel: { type: String, default: "取消" },
  },
  emits: ["cancel", "confirm"],
  setup(props, { expose: exposeComponent, emit }) {
    exposeComponent();
    const dialog = ref(null);
    let trigger;
    onMounted(() => {
      trigger = document.activeElement;
      dialog.value?.showModal?.();
    });
    onBeforeUnmount(() => {
      dialog.value?.close?.();
      if (trigger?.isConnected) trigger.focus();
    });
    const cancel = () => {
      if (!props.busy) emit("cancel");
    };
    const confirm = () => {
      if (!props.busy && !props.error) emit("confirm");
    };
    return { dialog, cancel, confirm };
  },
});
