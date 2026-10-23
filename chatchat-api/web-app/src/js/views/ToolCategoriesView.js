import { ref, computed, watch, nextTick, onMounted, onActivated } from "vue";
import { apiRequest, getStoredAuthSession } from "../../services/api.js";
import { useTransientNotice } from "../utils/useTransientNotice.js";
import "../../styles/pages/permission-workspace.css";
import "../../styles/pages/tool-categories.css";

export default {
  name: "ToolCategoriesView",
  emits: ["navigate"],
  setup() {
    const rows = ref([]),
      query = ref(""),
      page = ref(1),
      busy = ref(false),
      loaded = ref(false);
    const notice = ref(""),
      failed = ref(false),
      dialog = ref(null),
      mode = ref(""),
      selected = ref(null);
    const name = ref(""),
      targetId = ref(""),
      dialogError = ref(""),
      saving = ref(false);
    const editable = (getStoredAuthSession()?.user?.permissionCodes || []).includes(
      "mcp:publication:edit"
    );
    let trigger;
    useTransientNotice(notice, failed);
    const filtered = computed(() =>
      rows.value.filter((row) =>
        row.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())
      )
    );
    const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 10)));
    const visible = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10));
    const totalTools = computed(() => rows.value.reduce((sum, row) => sum + row.toolCount, 0));
    const targets = computed(() => rows.value.filter((row) => row.id !== selected.value?.id));
    const nameError = computed(() => {
      const value = name.value.trim();
      if (!value) return "请输入分类名称";
      if (value.length > 64) return "分类名称最多 64 个字符";
      if (["未分类", "待分类", "全部分类"].includes(value)) return "该名称由系统使用，请换一个名称";
      if (
        rows.value.some(
          (row) =>
            row.id !== selected.value?.id &&
            row.name.toLocaleLowerCase() === value.toLocaleLowerCase()
        )
      )
        return "该分类名称已存在";
      return "";
    });
    watch(query, () => {
      page.value = 1;
    });
    watch(pages, (value) => {
      page.value = Math.min(page.value, value);
    });
    async function refresh() {
      if (busy.value) return;
      busy.value = true;
      try {
        rows.value = await apiRequest("/tool-categories");
        loaded.value = true;
        if (failed.value) {
          failed.value = false;
          notice.value = "";
        }
        return true;
      } catch (error) {
        failed.value = true;
        notice.value = error.message || "分类加载失败，请重试";
        return false;
      } finally {
        busy.value = false;
      }
    }
    async function open(action, row = null, event) {
      if (!editable || saving.value) return;
      trigger = event?.currentTarget || document.activeElement;
      mode.value = action;
      selected.value = row;
      name.value = row?.name || "";
      targetId.value = "";
      dialogError.value = "";
      await nextTick();
      dialog.value?.showModal?.();
      dialog.value?.querySelector(action === "delete" ? "select, button" : "input")?.focus();
    }
    function close() {
      if (saving.value) return;
      dialog.value?.close?.();
      mode.value = "";
      dialogError.value = "";
      nextTick(() => {
        if (trigger?.isConnected) trigger.focus();
        else document.querySelector(".category-toolbar input")?.focus();
      });
    }
    async function submit() {
      if (saving.value || !editable) return;
      if (mode.value !== "delete" && nameError.value) {
        dialogError.value = nameError.value;
        return;
      }
      saving.value = true;
      dialogError.value = "";
      const action = mode.value,
        row = selected.value;
      try {
        await apiRequest(`/tool-categories${row ? `/${row.id}` : ""}`, {
          method: action === "delete" ? "DELETE" : row ? "PUT" : "POST",
          body: JSON.stringify(
            action === "delete"
              ? {
                  expectedVersion: row.version,
                  expectedToolCount: row.toolCount,
                  targetCategoryId: targetId.value || null,
                }
              : { name: name.value.trim(), ...(row ? { expectedVersion: row.version } : {}) }
          ),
        });
        const destination = rows.value.find((item) => item.id === targetId.value)?.name || "未分类";
        saving.value = false;
        close();
        failed.value = false;
        notice.value =
          action === "delete"
            ? `分类已删除${row.toolCount ? `，${row.toolCount} 个工具已移至“${destination}”` : ""}。`
            : row
              ? "分类名称已更新，关联工具已同步。"
              : "分类已创建，可在工具发布时选择。";
        await refresh();
      } catch (error) {
        dialogError.value = error.message || "操作未完成，请重试";
      } finally {
        saving.value = false;
      }
    }
    async function reloadDialog() {
      if (!(await refresh())) return;
      const current = rows.value.find((row) => row.id === selected.value?.id);
      if (selected.value && !current) {
        close();
        failed.value = true;
        notice.value = "该分类已被删除，列表已更新。";
      } else {
        selected.value = current || null;
        dialogError.value = "";
        if (targetId.value && !targets.value.some((row) => row.id === targetId.value))
          targetId.value = "";
      }
    }
    onMounted(refresh);
    onActivated(() => {
      if (loaded.value) refresh();
    });
    return {
      rows,
      query,
      page,
      busy,
      loaded,
      notice,
      failed,
      dialog,
      mode,
      selected,
      name,
      targetId,
      dialogError,
      saving,
      editable,
      filtered,
      pages,
      visible,
      totalTools,
      targets,
      nameError,
      refresh,
      open,
      close,
      submit,
      reloadDialog,
    };
  },
};
