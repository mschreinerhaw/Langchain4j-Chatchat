import { computed, onMounted, ref } from "vue";
import AppConfirmDialog from "../../components/AppConfirmDialog.vue";
import { apiRequest } from "../../services/api";
import { useAppConfirmation } from "../utils/useAppConfirmation";

export default {
  components: { AppConfirmDialog },
  props: { panel: { type: String, required: true }, workspaceContext: Object },
  setup(props) {
    const page = ref({ notes: [], roles: [], canPublish: false });
    const editing = ref(false);
    const roleId = ref("");
    const title = ref("");
    const body = ref("");
    const busy = ref(false);
    const loadFailed = ref(false);
    const error = ref("");
    const notice = ref("");
    const { confirmation, confirmAction, settleConfirmation } = useAppConfirmation();
    const selected = computed(() => page.value.roles.find((role) => role.id === roleId.value));
    const published = computed(() => Boolean(selected.value?.title));
    const path = "/workspaces/business/role-notes/" + props.panel;

    function selectRole() {
      title.value = selected.value?.title || "";
      body.value = selected.value?.body || "";
    }

    function closeEditor() {
      editing.value = false;
      roleId.value = "";
      selectRole();
    }

    async function load() {
      busy.value = true;
      error.value = "";
      loadFailed.value = false;
      try {
        page.value = await apiRequest(path, { workspaceContext: props.workspaceContext });
        if (!page.value.canPublish) closeEditor();
      } catch (failure) {
        page.value = { notes: [], roles: [], canPublish: false };
        closeEditor();
        error.value = failure.message || "角色说明加载失败";
        loadFailed.value = true;
      } finally {
        busy.value = false;
      }
    }

    async function changePublication(method, draft, message) {
      busy.value = true;
      error.value = "";
      notice.value = "";
      try {
        await apiRequest("/enterprise" + path + "/" + encodeURIComponent(roleId.value), {
          method,
          workspaceContext: props.workspaceContext,
          ...(draft && { body: JSON.stringify(draft) }),
        });
        notice.value = message;
        closeEditor();
        await load();
      } catch (failure) {
        error.value = failure.message || "操作失败";
      } finally {
        busy.value = false;
      }
    }

    function save() {
      if (busy.value || !selected.value || !title.value.trim() || !body.value.trim()) return;
      return changePublication("PUT", { title: title.value, body: body.value }, "角色说明已发布");
    }

    async function withdraw() {
      if (!published.value || busy.value) return;
      if (await confirmAction("确认撤回发给「" + selected.value.name + "」的说明？", {
        title: "撤回角色说明", confirmLabel: "撤回说明", tone: "danger",
      })) await changePublication("DELETE", null, "角色说明已撤回");
    }

    onMounted(load);
    return {
      page, editing, roleId, title, body, published, busy, loadFailed, error, notice,
      load, selectRole, closeEditor, save, withdraw, confirmation, settleConfirmation,
    };
  },
};
