import { ref, computed, onMounted } from "vue";
import { apiRequest } from "../../services/api.js";
import "../../styles/pages/unified-management.css";
export default {
  name: "PermissionToolsView",
  setup() {
    const catalog = ref({}),
      busy = ref(false),
      notice = ref(""),
      failed = ref(false);
    const blank = () => ({
      targetType: "ROLE",
      targetId: "",
      localToolName: "",
      scopeExpression: "",
      effect: "allow",
      enabled: true,
      remark: "",
    });
    const form = ref(blank()),
      subjects = computed(() =>
        form.value.targetType === "ROLE" ? catalog.value.roles : catalog.value.users
      );
    async function perform(op, message) {
      busy.value = true;
      failed.value = false;
      try {
        await op();
        notice.value = message;
      } catch (error) {
        failed.value = true;
        notice.value = error.message;
      } finally {
        busy.value = false;
      }
    }
    async function load() {
      await perform(async () => {
        catalog.value = await apiRequest("/permission-center");
      }, "授权目录已更新");
    }
    function reset() {
      form.value = blank();
    }
    function edit(grant) {
      form.value = { ...grant };
    }
    function subjectName(grant) {
      return (
        [...(catalog.value.roles || []), ...(catalog.value.users || [])].find(
          (item) => item.id === grant.targetId
        )?.roleName ||
        catalog.value.users?.find((item) => item.id === grant.targetId)?.displayName ||
        grant.targetId
      );
    }
    async function save() {
      await perform(async () => {
        await apiRequest("/permission-center/tool-grants", {
          method: "POST",
          body: JSON.stringify(form.value),
        });
        catalog.value = await apiRequest("/permission-center");
        reset();
      }, "授权已保存");
    }
    async function remove(grant) {
      if (!window.confirm("确认撤销这条授权？")) return;
      await perform(async () => {
        await apiRequest(`/permission-center/tool-grants/${encodeURIComponent(grant.id)}`, {
          method: "DELETE",
        });
        catalog.value = await apiRequest("/permission-center");
      }, "授权已撤销");
    }
    onMounted(load);
    return {
      catalog,
      busy,
      notice,
      failed,
      form,
      subjects,
      load,
      reset,
      edit,
      subjectName,
      save,
      remove,
    };
  },
};
