import { ref } from "vue";
import SystemManagementView from "../../views/SystemManagementView.vue";
import { apiRequest } from "../../services/api.js";
import "../../styles/pages/permission-workspace.css";
export default {
  name: "PermissionAuditView",
  components: { SystemManagementView },
  props: {},
  emits: [],
  setup(props, { emit }) {
    const mode = ref("login"),
      query = ref(""),
      action = ref(""),
      page = ref(1),
      busy = ref(false),
      notice = ref(""),
      result = ref({ items: [], total: 0, pages: 0 });
    const actionNames = {
      rejected: "拒绝或失败的授权操作",
      "role-authorization": "角色授权",
      save: "保存工具授权",
      delete: "撤销工具授权",
      grant: "添加资源授权",
      "update-grant": "更新资源授权",
      revoke: "撤销资源授权",
    };
    const typeNames = {
      PERMISSION_OPERATION: "授权操作",
      ROLE: "角色",
      "mcp-tool-permission": "工具",
      SKILL: "领域技能",
      AGENT_SKILL: "查询服务",
      KNOWLEDGE: "文档",
      KNOWLEDGE_BASE: "知识库",
      MCP_TOOL: "工具",
      WORKFLOW: "工作流",
    };
    let requestId = 0;
    async function load() {
      const current = ++requestId;
      busy.value = true;
      notice.value = "";
      try {
        const data = await apiRequest(
          "/permission-center/audits?" +
            new URLSearchParams({
              query: query.value,
              action: action.value,
              page: page.value,
              pageSize: 20,
            })
        );
        if (current === requestId) result.value = data;
      } catch (e) {
        if (current === requestId) notice.value = e.message;
      } finally {
        if (current === requestId) busy.value = false;
      }
    }
    return {
      SystemManagementView,
      mode,
      query,
      action,
      page,
      busy,
      notice,
      result,
      actionNames,
      typeNames,
      requestId,
      load,
    };
  },
};
