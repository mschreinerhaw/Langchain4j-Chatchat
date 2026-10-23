import { computed, onActivated, onMounted, ref, watch } from "vue";
import { apiRequest, getStoredAuthSession } from "../../services/api.js";
import { useTransientNotice } from "../utils/useTransientNotice.js";
import ToolPublicationsView from "../../views/ToolPublicationsView.vue";
import "../../styles/pages/permission-workspace.css";
import "../../styles/pages/template-capabilities.css";

const endpoint = "/tool-publications/capabilities";
export default {
  name: "TemplateToolsView",
  components: { ToolPublicationsView },
  setup() {
    const templates = ref([]),
      publications = ref([]),
      agents = ref([]),
      bound = ref([]);
    const busy = ref(false),
      loaded = ref(false),
      notice = ref(""),
      failed = ref(false);
    const tab = ref("publications"),
      query = ref(""),
      agentId = ref(""),
      selected = ref(null);
    const category = ref(""),
      page = ref(1),
      pageSize = ref(12);
    const stateFilter = ref("RUNNING");
    const codes = getStoredAuthSession()?.user?.permissionCodes || [];
    const canPublish = codes.includes("mcp:publication:publish"),
      canBind = codes.includes("platform:agents:workshop");
    const advanced = codes.includes("system:permission-center:manage");
    useTransientNotice(notice, failed);
    const tabs = [
      { id: "templates", label: "可用模板" },
      { id: "publications", label: "我的发布" },
      { id: "agents", label: "Agent 工具" },
    ];
    const sourceRows = computed(() => {
      let rows = tab.value === "templates" ? templates.value : publications.value;
      if (tab.value === "publications" && stateFilter.value)
        rows = rows.filter((item) => item.state === stateFilter.value);
      if (tab.value === "agents") {
        const latest = new Map();
        rows.forEach((item) => {
          if (!latest.has(item.toolName) || latest.get(item.toolName).version < item.version)
            latest.set(item.toolName, item);
        });
        rows = [...latest.values()];
      }
      return rows;
    });
    const categoryOf = (item) =>
      item.category ||
      templates.value.find((t) => t.templateKey === item.templateKey)?.category ||
      "未分类";
    const categories = computed(() =>
      [...new Set(sourceRows.value.map(categoryOf))].sort((a, b) => a.localeCompare(b, "zh-CN"))
    );
    const filtered = computed(() =>
      sourceRows.value.filter(
        (item) =>
          (!category.value || categoryOf(item) === category.value) &&
          `${item.title} ${item.description || ""} ${item.assetName}`
            .toLowerCase()
            .includes(query.value.toLowerCase())
      )
    );
    const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)));
    const visible = computed(() =>
      filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
    );
    const runningCount = computed(
      () => publications.value.filter((item) => item.state === "RUNNING").length
    );
    const currentRelease = (item) =>
      publications.value.find(
        (p) =>
          p.templateKey === item.templateKey &&
          p.revision === item.revision &&
          p.state === "RUNNING"
      );
    function navigate(value) {
      tab.value = value;
      query.value = "";
      category.value = "";
      page.value = 1;
      stateFilter.value = "RUNNING";
    }
    function assign(item) {
      navigate("agents");
      query.value = item.title;
    }
    async function load() {
      busy.value = true;
      notice.value = "";
      failed.value = false;
      try {
        const results = await Promise.all([
          apiRequest(endpoint + "/templates"),
          apiRequest(endpoint + "/publications"),
          canBind ? apiRequest(endpoint + "/agents") : Promise.resolve([]),
        ]);
        [templates.value, publications.value, agents.value] = results.map((value) =>
          Array.isArray(value) ? value : []
        );
        if (!agentId.value) agentId.value = agents.value[0]?.id || "";
        loaded.value = true;
      } catch (error) {
        notice.value = error.message || "模板目录读取失败，请重试";
        failed.value = true;
      } finally {
        busy.value = false;
      }
    }
    async function publish() {
      if (!selected.value || busy.value || !canPublish) return;
      busy.value = true;
      failed.value = false;
      try {
        await apiRequest(endpoint + "/publications", {
          method: "POST",
          body: JSON.stringify({ key: selected.value.key, revision: selected.value.revision }),
        });
        selected.value = null;
        await load();
        navigate("publications");
        notice.value = "模板工具已发布。下一步：点击“分配给 Agent”，选择 Agent 并勾选工具。";
      } catch (error) {
        failed.value = true;
        notice.value = error.message || "发布失败，可安全重试";
      } finally {
        busy.value = false;
      }
    }
    async function offline(item) {
      if (!window.confirm(`确认下线“${item.title}”？后续调用将被拒绝。`)) return;
      busy.value = true;
      try {
        await apiRequest(endpoint + "/publications/" + encodeURIComponent(item.id) + "/offline", {
          method: "POST",
        });
        await load();
        notice.value = "工具已下线";
      } catch (error) {
        failed.value = true;
        notice.value = error.message;
      } finally {
        busy.value = false;
      }
    }
    let bindingRequest = 0;
    async function loadBindings() {
      const version = ++bindingRequest;
      bound.value = [];
      if (!agentId.value) return;
      try {
        const value = await apiRequest(
          endpoint + "/agents/" + encodeURIComponent(agentId.value) + "/tools"
        );
        if (version === bindingRequest) bound.value = Array.isArray(value) ? value : [];
      } catch (error) {
        if (version === bindingRequest) {
          failed.value = true;
          notice.value = error.message;
        }
      }
    }
    const isBound = (item) =>
      bound.value.some((name) => name === item.toolName || name.endsWith("_" + item.toolName));
    async function toggleBinding(item, enabled) {
      if (!agentId.value || busy.value) return;
      busy.value = true;
      failed.value = false;
      try {
        const result = await apiRequest(
          endpoint +
            "/agents/" +
            encodeURIComponent(agentId.value) +
            "/tools/" +
            encodeURIComponent(item.toolName),
          { method: enabled ? "PUT" : "DELETE" }
        );
        bound.value = Array.isArray(result) ? result : [];
        notice.value = enabled ? "Agent 工具绑定已保存" : "已解除 Agent 工具绑定";
      } catch (error) {
        failed.value = true;
        notice.value = error.message;
        await loadBindings();
      } finally {
        busy.value = false;
      }
    }
    watch(agentId, loadBindings);
    watch(tab, (value) => {
      category.value = "";
      page.value = 1;
      if (value === "agents") loadBindings();
    });
    watch([query, category, stateFilter, pageSize], () => {
      page.value = 1;
    });
    watch(pages, (value) => {
      page.value = Math.min(page.value, value);
    });
    onMounted(load);
    onActivated(() => {
      if (loaded.value) load();
    });
    return {
      templates,
      publications,
      agents,
      busy,
      loaded,
      notice,
      failed,
      tab,
      query,
      stateFilter,
      agentId,
      selected,
      tabs,
      visible,
      canPublish,
      canBind,
      advanced,
      category,
      categories,
      categoryOf,
      page,
      pageSize,
      pages,
      filtered,
      runningCount,
      currentRelease,
      navigate,
      assign,
      load,
      publish,
      offline,
      isBound,
      toggleBinding,
      parameterSummary: (item) =>
        `${Object.keys(item.inputSchema?.properties || {}).length} 项业务参数`,
    };
  },
};
