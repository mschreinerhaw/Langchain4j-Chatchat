import { ref, computed, onMounted } from "vue";
import { apiRequest, getStoredAuthSession } from "../../services/api.js";
import "../../styles/pages/unified-management.css";
export default {
  name: "ToolPublicationsView",
  setup() {
    const rows = ref([]),
      parents = ref([]),
      roles = ref([]),
      templates = ref([]),
      busy = ref(false),
      notice = ref(""),
      failed = ref(false);
    const form = ref({ logicalId: null, title: "", description: "" });
    const blank = () => ({
      domainCode: "",
      parentToolName: "",
      roleId: "",
      subjectType: "ROLE",
      userId: null,
      templateKeys: [],
      enabled: false,
    });
    const definition = ref(blank()),
      inputSchema = ref('{"type":"object","properties":{}}'),
      outputSchema = ref('{"type":"object","properties":{}}');
    const inputSample = ref("{}");
    const permissions = getStoredAuthSession()?.user?.permissionCodes || [];
    const editable = computed(() => permissions.includes("mcp:publication:edit"));
    const publishable = computed(() => permissions.includes("mcp:publication:publish"));
    const states = {
      DRAFT: "草稿",
      VALIDATED: "已校验",
      TESTED: "合约测试通过",
      RUNNING: "运行中",
      OFFLINE: "已下线",
    };
    async function perform(operation, message = "操作完成") {
      busy.value = true;
      failed.value = false;
      notice.value = "";
      try {
        await operation();
        notice.value = message;
      } catch (error) {
        failed.value = true;
        notice.value = error.message;
      } finally {
        busy.value = false;
      }
    }
    async function refresh() {
      await perform(async () => {
        const result = await Promise.all([
          apiRequest("/tool-publications"),
          apiRequest("/tool-publications/catalog/parents"),
          apiRequest("/tool-publications/catalog/roles"),
        ]);
        [rows.value, parents.value, roles.value] = result;
      }, "目录已更新");
    }
    async function loadTemplates() {
      templates.value = [];
      if (!definition.value.roleId || !definition.value.parentToolName) return;
      await perform(async () => {
        templates.value = await apiRequest(
          `/tool-publications/catalog/templates?roleId=${encodeURIComponent(definition.value.roleId)}&parentToolName=${encodeURIComponent(definition.value.parentToolName)}`
        );
      });
    }
    function pretty(value) {
      try {
        return JSON.stringify(JSON.parse(value), null, 2);
      } catch {
        return value;
      }
    }
    async function select(row) {
      form.value = {
        logicalId: row.logicalId,
        title: row.title,
        description: row.description || "",
      };
      definition.value = JSON.parse(row.definitionJson);
      inputSchema.value = pretty(row.inputSchemaJson);
      outputSchema.value = pretty(row.outputSchemaJson);
      await loadTemplates();
    }
    function reset() {
      form.value = { logicalId: null, title: "", description: "" };
      definition.value = blank();
      templates.value = [];
    }
    async function save() {
      await perform(async () => {
        await apiRequest("/tool-publications", {
          method: "POST",
          body: JSON.stringify({
            ...form.value,
            kind: "CONTROLLED_TEMPLATE",
            definition: definition.value,
            inputSchema: JSON.parse(inputSchema.value),
            outputSchema: JSON.parse(outputSchema.value),
          }),
        });
        rows.value = await apiRequest("/tool-publications");
      }, "草稿已保存，尚未向模型发布");
    }
    async function action(row, action, body) {
      await perform(async () => {
        await apiRequest(`/tool-publications/${row.id}/${action}`, {
          method: "POST",
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
        rows.value = await apiRequest("/tool-publications");
      }, "操作完成，版本记录已更新");
    }
    async function test(row) {
      try {
        await action(row, "test", {
          input: JSON.parse(inputSample.value),
        });
      } catch (error) {
        failed.value = true;
        notice.value = error.message;
      }
    }
    function confirmAction(row, actionName) {
      if (
        window.confirm(
          actionName === "offline"
            ? "确认下线此工具版本？新调用将停止使用它。"
            : "确认重新启用此历史版本？系统会重新检查当前授权。"
        )
      )
        action(row, actionName);
    }
    onMounted(refresh);
    return {
      rows,
      parents,
      roles,
      templates,
      busy,
      notice,
      failed,
      form,
      definition,
      inputSchema,
      outputSchema,
      inputSample,
      editable,
      publishable,
      states,
      refresh,
      loadTemplates,
      pretty,
      select,
      reset,
      save,
      action,
      test,
      confirmAction,
    };
  },
};
