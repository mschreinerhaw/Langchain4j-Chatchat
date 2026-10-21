import { defineComponent, computed, nextTick, onMounted, onBeforeUnmount, ref } from "vue";
import { apiRequest } from "../../../services/api";

export default defineComponent({
  name: "SqlUserDocuments",
  props: { modelValue: { type: Array, default: () => [] }, busy: Boolean },
  emits: ["update:modelValue", "uploading"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    exposeComponent();
    const props = componentProps;
    const emit = emitEvent;
    const uploadInput = ref(null);
    const documents = ref([]),
      query = ref(""),
      loading = ref(false),
      uploading = ref(false),
      error = ref("");
    let disposed = false;
    const accept = ".pdf,.docx,.xlsx,.csv,.txt,.md,.json,.jsonl,.sql";
    const supported = (file) =>
      file.kind === "DOCUMENT" &&
      /\.(pdf|docx|xlsx|csv|txt|md|json|jsonl|sql)$/i.test(file.fileName);
    const selected = (id) => props.modelValue.find((item) => item.fileId === id);
    const visible = computed(() =>
      documents.value.filter((file) =>
        file.fileName.toLowerCase().includes(query.value.trim().toLowerCase())
      )
    );
    const unavailable = computed(() =>
      loading.value
        ? []
        : props.modelValue.filter(
            (item) => !documents.value.some((file) => file.id === item.fileId)
          )
    );
    function remove(id) {
      emit(
        "update:modelValue",
        props.modelValue.filter((item) => item.fileId !== id)
      );
    }
    function choose(file, purpose = "RULES") {
      if (selected(file.id)) return;
      if (props.modelValue.length >= 8) throw new Error("每次最多引用 8 个文档");
      if (
        documents.value
          .filter((item) => selected(item.id))
          .reduce((total, item) => total + item.size, 0) +
          file.size >
        16 * 1024 * 1024
      )
        throw new Error("本次文档合计不能超过 16 MiB");
      emit("update:modelValue", [
        ...props.modelValue,
        { fileId: file.id, fileName: file.fileName, purpose },
      ]);
    }
    function toggle(file) {
      error.value = "";
      try {
        if (selected(file.id))
          emit(
            "update:modelValue",
            props.modelValue.filter((item) => item.fileId !== file.id)
          );
        else choose(file);
      } catch (ex) {
        error.value = ex.message;
      }
    }
    function setPurpose(id, purpose) {
      emit(
        "update:modelValue",
        props.modelValue.map((item) => (item.fileId === id ? { ...item, purpose } : item))
      );
    }
    async function refresh() {
      loading.value = true;
      error.value = "";
      try {
        const latest = await apiRequest("/data-science/user-assets?kind=DOCUMENT&limit=100");
        // Selected older files remain available even when outside the latest 100 uploads.
        const missing = props.modelValue.filter(
          (item) => !latest.some((file) => file.id === item.fileId)
        );
        const existing = await Promise.all(
          missing.map((item) =>
            apiRequest(`/data-science/user-assets/${item.fileId}`).catch(() => null)
          )
        );
        if (disposed) return;
        documents.value = [...latest, ...existing.filter(Boolean)].filter(
          (file) => file.state === "AVAILABLE" && supported(file)
        );
      } catch (ex) {
        if (!disposed) error.value = ex.message;
      } finally {
        if (!disposed) loading.value = false;
      }
    }
    async function uploadFiles(event) {
      const incoming = Array.from(event.target.files || []);
      event.target.value = "";
      if (!incoming.length || props.busy || uploading.value) return;
      error.value = "";
      uploading.value = true;
      emit("uploading", true);
      try {
        if (incoming.length + props.modelValue.length > 8) throw new Error("每次最多引用 8 个文档");
        const chosen = documents.value.filter((file) => selected(file.id));
        if (
          [...chosen, ...incoming].reduce((total, file) => total + file.size, 0) >
          16 * 1024 * 1024
        )
          throw new Error("本次文档合计不能超过 16 MiB");
        if (
          incoming.some(
            (file) => !supported({ kind: "DOCUMENT", fileName: file.name }) || !file.size
          )
        )
          throw new Error("请选择支持的非空文档");
        for (const file of incoming) {
          const form = new FormData();
          form.append("file", file);
          const saved = await apiRequest("/data-science/user-assets", {
            method: "POST",
            body: form,
          });
          if (disposed) return;
          documents.value = [saved, ...documents.value.filter((item) => item.id !== saved.id)];
          choose(saved);
          // Let the parent apply each selection before the next file finishes uploading.
          await nextTick();
        }
      } catch (ex) {
        if (!disposed) error.value = ex.message;
      } finally {
        if (!disposed) {
          uploading.value = false;
          emit("uploading", false);
        }
      }
    }
    onMounted(refresh);
    onBeforeUnmount(() => {
      disposed = true;
      emit("uploading", false);
    });
    return {
      ref,
      uploadInput,
      documents,
      query,
      loading,
      uploading,
      error,
      accept,
      selected,
      visible,
      unavailable,
      remove,
      toggle,
      setPurpose,
      refresh,
      uploadFiles,
    };
  },
});
