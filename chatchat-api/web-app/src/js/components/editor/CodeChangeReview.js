import { defineComponent, computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from "vue";
import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import { X, ChevronUp, ChevronDown } from "@lucide/vue";
import { buildPythonLineDiff } from "../../utils/pythonCodeDiff";

export default defineComponent({
  name: "CodeChangeReview",
  components: { X, ChevronUp, ChevronDown },
  props: {
    files: { type: Array, required: true },
    language: { type: String, default: "python" },
    title: String,
    initialPath: String,
    blockedReason: String,
    busy: Boolean,
    applyLabel: { type: String, default: "应用变更" },
    hint: {
      type: String,
      default: "绿色为新增，红色为删除。确认后写入编辑器，可通过撤销恢复；保存后才会持久化。",
    },
  },
  emits: ["close", "apply"],
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;

    const selected = ref(
        Math.max(
          0,
          props.files.findIndex((file) => file.path === props.initialPath)
        )
      ),
      sideBySide = ref(true),
      host = ref(null),
      dialog = ref(null);
    const current = computed(() => props.files[selected.value] || props.files[0]);
    const summary = computed(() =>
      buildPythonLineDiff(current.value?.before || "", current.value?.after || "")
    );
    const hasChanges = computed(() => props.files.some((file) => file.before !== file.after));
    let editor,
      original,
      modified,
      previousFocus,
      changes,
      changeIndex = -1,
      app,
      previousInert;
    function showFile() {
      if (!editor || !current.value) return;
      editor.setModel(null);
      original?.dispose();
      modified?.dispose();
      original = monaco.editor.createModel(current.value.before || "", props.language);
      modified = monaco.editor.createModel(current.value.after || "", props.language);
      editor.setModel({ original, modified });
      changeIndex = -1;
    }
    function navigate(direction) {
      const items = editor?.getLineChanges() || [];
      if (!items.length) return;
      changeIndex =
        changeIndex < 0
          ? direction > 0
            ? 0
            : items.length - 1
          : (changeIndex + direction + items.length) % items.length;
      editor
        .getModifiedEditor()
        .revealLineInCenter(Math.max(1, items[changeIndex].modifiedStartLineNumber));
      editor
        .getOriginalEditor()
        .revealLineInCenter(Math.max(1, items[changeIndex].originalStartLineNumber));
    }
    function trapFocus(event) {
      if (event.key !== "Tab") return;
      const nodes = [
        ...dialog.value.querySelectorAll('button:not(:disabled),[tabindex="0"],textarea'),
      ].filter((el) => el.getClientRects().length);
      const first = nodes[0],
        last = nodes.at(-1);
      if (
        event.shiftKey &&
        (document.activeElement === first || document.activeElement === dialog.value)
      ) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
    watch(current, () => nextTick(showFile));
    watch(sideBySide, (value) => editor?.updateOptions({ renderSideBySide: value }));
    onMounted(() => {
      app = document.getElementById("app");
      previousInert = app?.inert;
      if (app) app.inert = true;
      previousFocus = document.activeElement;
      dialog.value.focus();
      dialog.value.addEventListener("keydown", trapFocus);
      editor = monaco.editor.createDiffEditor(host.value, {
        automaticLayout: true,
        readOnly: true,
        originalEditable: false,
        renderSideBySide: true,
        theme: "vs",
        fontSize: 14,
        scrollBeyondLastLine: false,
        renderIndicators: true,
        renderGutterMenu: false,
        renderMarginRevertIcon: false,
        occurrencesHighlight: "off",
        ignoreTrimWhitespace: false,
        minimap: { enabled: false },
      });
      changes = editor.onDidUpdateDiff(() => {
        changeIndex = -1;
      });
      showFile();
    });
    onBeforeUnmount(() => {
      dialog.value?.removeEventListener("keydown", trapFocus);
      changes?.dispose();
      editor?.dispose();
      original?.dispose();
      modified?.dispose();
      if (app) app.inert = previousInert;
      previousFocus?.isConnected && previousFocus.focus();
    });
    return {
      ref,
      selected,
      sideBySide,
      host,
      dialog,
      current,
      summary,
      hasChanges,
      editor,
      navigate,
    };
  },
});
