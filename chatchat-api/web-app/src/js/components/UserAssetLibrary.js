import { defineComponent, ref } from "vue";
import { apiRequest, getStoredAuthSession, fetchPythonCodeModels } from "../../services/api";
import { workspaceHeaders } from "../utils/workspaceContext";

export default defineComponent({
  name: "UserAssetLibrary",
  props: { workspaceContext: { type: Object, default: null } },
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;
    const models = ref([]),
      modelName = ref("");
    const files = ref([]),
      selected = ref([]),
      space = ref(null),
      busy = ref(false),
      error = ref(""),
      instruction = ref(""),
      report = ref(""),
      status = ref("");
    const knowledgeEnabled = ref(false),
      indexStates = ref({}),
      query = ref(""),
      historyOnly = ref(false),
      searchResults = ref([]);
    const indexLabel = (state) =>
      ({
        PENDING: "索引待处理",
        INDEXED: "可检索",
        FAILED: "索引失败",
        SKIPPED: "该格式暂不支持全文检索",
      })[state] || state;
    async function refreshIndex() {
      try {
        const states = await apiRequest("/data-science/user-assets/knowledge", options());
        indexStates.value = Object.fromEntries(states.map((item) => [item.fileId, item]));
        knowledgeEnabled.value = true;
      } catch {
        knowledgeEnabled.value = false;
        indexStates.value = {};
      }
    }
    async function searchKnowledge() {
      busy.value = true;
      error.value = "";
      searchResults.value = [];
      try {
        searchResults.value = await apiRequest(
          `/data-science/user-assets/knowledge/search?q=${encodeURIComponent(query.value)}&historyOnly=${historyOnly.value}`,
          options()
        );
        status.value = searchResults.value.length
          ? "请选择需要使用的检索结果。"
          : "没有可检索的匹配资料；可刷新查看索引状态。";
      } catch (e) {
        error.value = e.message;
      } finally {
        busy.value = false;
      }
    }
    async function retryIndex(file) {
      try {
        await apiRequest(`/data-science/user-assets/knowledge/${file.id}/retry`, {
          ...options(),
          method: "POST",
        });
        await refreshIndex();
      } catch (e) {
        error.value = e.message;
      }
    }
    async function rebuildKnowledge() {
      try {
        await apiRequest("/data-science/user-assets/knowledge/rebuild", {
          ...options(),
          method: "POST",
        });
        await refreshIndex();
        status.value = "已安排从原始资料重建索引，请稍后刷新。";
      } catch (e) {
        error.value = e.message;
      }
    }
    async function selectHit(hit) {
      try {
        const file = await apiRequest(`/data-science/user-assets/${hit.fileId}`, options());
        if (!files.value.some((item) => item.id === file.id)) files.value.push(file);
        if (!selected.value.includes(file.id)) selected.value.push(file.id);
      } catch (e) {
        error.value = e.message;
      }
    }
    let loaded = false;
    const label = (kind) =>
      ({
        DOCUMENT: "资料",
        DATASET: "数据快照",
        CODE: "代码",
        LOG: "日志",
        ARTIFACT: "分析产物",
        CONTEXT: "历史上下文",
      })[kind] || kind;
    const selectable = (file) => ["DOCUMENT", "DATASET", "ARTIFACT", "CONTEXT"].includes(file.kind);
    const size = (n) =>
      n >= 1048576 ? `${(n / 1048576).toFixed(1)} MB` : `${Math.ceil(n / 1024)} KB`;
    const options = () => ({ workspaceContext: props.workspaceContext });
    async function refresh() {
      try {
        error.value = "";
        [files.value, space.value, models.value] = await Promise.all([
          apiRequest("/data-science/user-assets?limit=100", options()),
          apiRequest("/data-science/user-assets/space", options()),
          fetchPythonCodeModels(),
        ]);
        await refreshIndex();
        if (!models.value.some((model) => model.value === modelName.value))
          modelName.value =
            models.value.find((model) => model.defaultModel)?.value || models.value[0]?.value || "";
        selected.value = selected.value.filter((id) => files.value.some((file) => file.id === id));
        loaded = true;
      } catch (e) {
        error.value = e.message;
      }
    }
    function opened(event) {
      if (event.target.open && !loaded) refresh();
    }
    async function upload(event) {
      const file = event.target.files?.[0];
      if (!file) return;
      busy.value = true;
      try {
        error.value = "";
        const body = new FormData();
        body.append("file", file);
        await apiRequest("/data-science/user-assets", { ...options(), method: "POST", body });
        await refresh();
      } catch (e) {
        error.value = e.message;
      } finally {
        busy.value = false;
        event.target.value = "";
      }
    }
    async function remove(file) {
      busy.value = true;
      try {
        await apiRequest(`/data-science/user-assets/${encodeURIComponent(file.id)}`, {
          ...options(),
          method: "DELETE",
        });
        searchResults.value = searchResults.value.filter((hit) => hit.fileId !== file.id);
        await refresh();
      } catch (e) {
        error.value = e.message;
      } finally {
        busy.value = false;
      }
    }
    async function download(file) {
      try {
        error.value = "";
        const token = getStoredAuthSession()?.token;
        const response = await fetch(
          `/api/v1/data-science/user-assets/${encodeURIComponent(file.id)}/content`,
          {
            headers: {
              ...(token ? { Authorization: `Bearer ${token}` } : {}),
              ...workspaceHeaders(props.workspaceContext),
            },
          }
        );
        if (!response.ok) throw new Error("下载失败，请刷新后重试");
        const url = URL.createObjectURL(await response.blob());
        const a = document.createElement("a");
        a.href = url;
        a.download = file.fileName;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch (e) {
        error.value = e.message;
      }
    }
    async function analyze() {
      busy.value = true;
      error.value = "";
      report.value = "";
      status.value = "正在分析选中的资料，请保持页面打开。";
      try {
        const chosen = files.value.filter((file) => selected.value.includes(file.id));
        const receipt = await apiRequest("/data-science/user-assets/analyses", {
          ...options(),
          method: "POST",
          body: JSON.stringify({
            requestId:
              globalThis.crypto?.randomUUID?.() ||
              `${Date.now()}-${Math.random().toString(36).slice(2)}`,
            fileIds: chosen.filter((file) => file.kind !== "CONTEXT").map((file) => file.id),
            contextIds: chosen.filter((file) => file.kind === "CONTEXT").map((file) => file.id),
            instruction: instruction.value,
            modelName: modelName.value || undefined,
          }),
        });
        report.value = receipt.results?.[0]?.reportMarkdown || "";
        status.value = receipt.resultAvailable
          ? "分析完成，代码与结果已归档。"
          : `分析未完成：${receipt.summary || receipt.status || "请查看任务记录"}`;
        await refresh();
      } catch (e) {
        error.value = e.message;
        status.value = "请求未完成，请先核查任务和归档记录。";
      } finally {
        busy.value = false;
      }
    }
    return {
      models,
      modelName,
      files,
      selected,
      space,
      busy,
      error,
      instruction,
      report,
      status,
      knowledgeEnabled,
      indexStates,
      query,
      historyOnly,
      searchResults,
      indexLabel,
      searchKnowledge,
      retryIndex,
      rebuildKnowledge,
      selectHit,
      label,
      selectable,
      size,
      refresh,
      opened,
      upload,
      remove,
      download,
      analyze,
    };
  },
});
