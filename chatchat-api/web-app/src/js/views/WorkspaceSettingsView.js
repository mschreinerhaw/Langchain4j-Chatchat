import { ref, onMounted } from "vue";
import { apiRequest } from "../../services/api.js";
import { permissionSections } from "../utils/permissionWorkspace.js";
import "../../styles/pages/permission-workspace.css";
export default {
  name: "WorkspaceSettingsView",
  components: {},
  props: {},
  emits: ["reload-workspaces"],
  setup(props, { emit }) {
    const rows = ref([]),
      busy = ref(false),
      notice = ref(""),
      failed = ref(false),
      draft = ref(null),
      formError = ref("");
    const pageLabels = {
      ...Object.fromEntries(permissionSections.map((p) => [p.id, p.label])),
      chat: "智能对话",
      search: "文档检索",
      agents: "查询服务管理",
      workspaces: "工作空间设置",
      toolPublications: "工具发布",
      sqlStudio: "数据开发",
      dataScienceData: "数据工作台",
      dataScienceEnvironment: "运行环境",
    };
    async function load() {
      busy.value = true;
      try {
        rows.value = await apiRequest("/enterprise/workspaces");
        failed.value = false;
      } catch (e) {
        failed.value = true;
        notice.value = e.message;
      } finally {
        busy.value = false;
      }
    }
    function edit(row) {
      draft.value = JSON.parse(JSON.stringify(row));
      formError.value = "";
    }
    async function save() {
      if (
        draft.value.status === "disabled" &&
        !window.confirm("确认停用此工作空间？用户将无法进入。")
      )
        return;
      busy.value = true;
      formError.value = "";
      try {
        const value = draft.value;
        const manifest = { ...value.manifest, configVersion: value.manifest.configVersion + 1 };
        await apiRequest("/enterprise/workspaces/" + encodeURIComponent(manifest.workspaceId), {
          method: "PUT",
          body: JSON.stringify({
            manifest,
            status: value.status,
            accessPermissionCode: value.accessPermissionCode,
            expectedVersion: value.version,
          }),
        });
        draft.value = null;
        await load();
        notice.value = "工作空间配置已保存";
        emit("reload-workspaces");
      } catch (e) {
        formError.value = e.message || "保存失败；配置可能已被其他管理员修改，请重新加载";
      } finally {
        busy.value = false;
      }
    }
    async function setDefault(row) {
      if (!window.confirm("将“" + row.manifest.name + "”设为平台默认登录空间？")) return;
      busy.value = true;
      try {
        await apiRequest("/enterprise/workspaces/default", {
          method: "PUT",
          body: JSON.stringify({ workspaceId: row.manifest.workspaceId }),
        });
        await load();
        notice.value = "平台默认工作空间已更新";
        failed.value = false;
        emit("reload-workspaces");
      } catch (e) {
        notice.value = e.message;
        failed.value = true;
      } finally {
        busy.value = false;
      }
    }
    onMounted(load);
    return {
      rows,
      busy,
      notice,
      failed,
      draft,
      formError,
      pageLabels,
      load,
      edit,
      save,
      setDefault,
    };
  },
};
