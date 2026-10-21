import { defineComponent, computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import {
  Folder,
  FolderPlus,
  FolderInput,
  FolderOpen,
  Files,
  FileText,
  FileCode2,
  Image,
  Database,
  Upload,
  Download,
  Trash2,
  Search,
  RefreshCw,
  LayoutGrid,
  List,
  ChevronRight,
  HardDrive,
  Eye,
  Sparkles,
  X,
} from "@lucide/vue";
import AppNotice from "../../components/AppNotice.vue";
import AppConfirmDialog from "../../components/AppConfirmDialog.vue";
import ReportMarkdown from "../../components/ReportMarkdown.vue";
import { apiRequest, getStoredAuthSession, fetchPythonCodeModels } from "../../services/api";
import { workspaceHeaders } from "../utils/workspaceContext";
import {
  userSpaceEntries,
  folderTrail,
  canAnalyze,
  canDelete,
  validateAnalysisFiles,
} from "../utils/userSpaceFiles";

export default defineComponent({
  name: "UserSpaceBrowser",
  components: {
    FolderPlus,
    FileCode2,
    Upload,
    AppNotice,
    Files,
    Folder,
    HardDrive,
    ChevronRight,
    RefreshCw,
    List,
    LayoutGrid,
    Search,
    FolderInput,
    Sparkles,
    FolderOpen,
    Eye,
    Download,
    Trash2,
    X,
    ReportMarkdown,
    AppConfirmDialog,
  },
  props: {
    workspaceContext: Object,
    dataFiles: { type: Array, default: () => [] },
    scripts: { type: Array, default: () => [] },
    canCreateScript: Boolean,
  },
  emits: [
    "open-script",
    "new-script",
    "download-data",
    "remove-data",
    "remove-script",
    "refresh",
    "python-data-ready",
    "open-python-draft",
  ],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    exposeComponent();
    const props = componentProps;
    const emit = emitEvent;
    const page = ref(1),
      pageSize = ref(25),
      total = ref(0),
      totalFiles = ref(0),
      folderCounts = ref({}),
      pageItems = ref([]);
    let loadTicket = 0;
    const files = ref([]),
      organization = ref({ folders: [], locations: [] }),
      space = ref(null),
      selected = ref([]),
      currentFolder = ref("all"),
      query = ref(""),
      viewMode = ref("list"),
      sortBy = ref("updated");
    const busy = ref(false),
      loading = ref(false),
      analyzing = ref(false),
      error = ref(""),
      status = ref(""),
      uploadInput = ref(null);
    const folderDialog = ref(false),
      folderName = ref(""),
      moveDialog = ref(false),
      moveTarget = ref("root"),
      preview = ref(null),
      confirmation = ref(null);
    const analysisOpen = ref(false),
      instruction = ref(""),
      models = ref([]),
      modelName = ref(""),
      report = ref(""),
      analysisStatus = ref(""),
      knowledgeEnabled = ref(false),
      hits = ref(null);
    let disposed = false,
      confirmResolve;
    const accept =
      ".pdf,.docx,.xlsx,.xls,.csv,.parquet,.orc,.zip,.txt,.log,.md,.json,.jsonl,.py,.sql,.png,.jpg,.jpeg,.webp,.gif";
    const options = () => ({ workspaceContext: props.workspaceContext });
    const folders = computed(() => {
      const customIds = new Set(organization.value.folders.map((folder) => folder.id));
      return organization.value.folders.map((folder) => ({
        ...folder,
        parentId: customIds.has(folder.parentId) ? folder.parentId : "root",
      }));
    });
    const entries = computed(() =>
      hits.value
        ? userSpaceEntries(files.value, [], [], organization.value.locations)
        : pageItems.value.flatMap((item) =>
            userSpaceEntries(
              item.source === "asset" ? [item.original] : [],
              item.source === "python-data" ? [item.original] : [],
              item.source === "python-script" ? [item.original] : [],
              [{ source: item.source, sourceId: item.sourceId, folderId: item.folderId }]
            )
          )
    );
    const selectedEntries = computed(() =>
      entries.value.filter((file) => selected.value.includes(file.key))
    );
    const breadcrumb = computed(() => folderTrail(currentFolder.value, folders.value));
    const folderLabel = (id) =>
      folders.value.find((folder) => folder.id === id)?.name || "用户空间";
    const folderNameLabel = computed(() =>
      currentFolder.value === "all" ? "全部文件" : folderLabel(currentFolder.value)
    );
    const childFolders = computed(() =>
      folders.value.filter(
        (folder) =>
          folder.parentId === (currentFolder.value === "all" ? "root" : currentFolder.value)
      )
    );
    const isCustomFolder = computed(() =>
      organization.value.folders.some((folder) => folder.id === currentFolder.value)
    );
    const visible = computed(() =>
      hits.value
        ? entries.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
        : entries.value
    );
    const resultTotal = computed(() => (hits.value ? entries.value.length : total.value));
    const pageCount = computed(() => Math.max(1, Math.ceil(resultTotal.value / pageSize.value)));
    const allSelected = computed(
      () =>
        visible.value.length > 0 && visible.value.every((file) => selected.value.includes(file.key))
    );
    function countFolder(id) {
      return Object.entries(folderCounts.value).reduce(
        (sum, [folder, count]) =>
          sum +
          (folder === id || folderTrail(folder, folders.value).some((item) => item.id === id)
            ? count
            : 0),
        0
      );
    }
    function openFolder(id) {
      if (busy.value) return;
      currentFolder.value = id;
      selected.value = [];
      query.value = "";
      hits.value = null;
    }
    function goPage(value) {
      if (busy.value) return;
      page.value = Math.max(1, Math.min(value, pageCount.value));
      selected.value = [];
      if (!hits.value) refresh();
    }
    watch([currentFolder, query, sortBy, pageSize], () => {
      page.value = 1;
      selected.value = [];
      if (!hits.value) refresh();
    });
    watch([() => props.dataFiles, () => props.scripts], () => {
      if (!disposed) refresh();
    });
    function toggle(key) {
      if (selected.value.includes(key))
        selected.value = selected.value.filter((item) => item !== key);
      else if (selected.value.length < 100) selected.value.push(key);
      else error.value = "每次最多选择 100 个文件";
    }
    function toggleAll() {
      if (allSelected.value)
        selected.value = selected.value.filter(
          (key) => !visible.value.some((file) => file.key === key)
        );
      else
        selected.value = Array.from(
          new Set([...selected.value, ...visible.value.map((file) => file.key)])
        ).slice(0, 100);
    }
    const formatBytes = (n) =>
      n >= 1048576
        ? `${(n / 1048576).toFixed(1)} MB`
        : n >= 1024
          ? `${(n / 1024).toFixed(1)} KB`
          : `${n || 0} B`;
    const formatTime = (value) =>
      value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
    const stateLabel = (state) =>
      ({
        AVAILABLE: "可用",
        STAGED: "上传待重试",
        DRAFT: "草稿",
        PUBLISHED: "已发布",
        TRANSFERRING: "传输中",
        TRANSFER_FAILED: "传输失败",
        EXPIRED: "已过期",
        TESTED: "已测试",
        FAILED: "失败",
      })[state] ||
      state ||
      "—";
    const readable = (file) => file.source === "python-script" || file.state === "AVAILABLE";
    const isPythonData = (file) =>
      file.kind === "DOCUMENT" && /\.(csv|xls|xlsx|json|txt|log|parquet|orc|zip)$/i.test(file.name);
    async function usePythonData(file) {
      busy.value = true;
      error.value = "";
      try {
        const prepared =
          file.source === "python-data"
            ? file.original
            : await apiRequest(`/data-science/user-assets/${file.sourceId}/python-data`, {
                ...options(),
                method: "POST",
              });
        if (disposed) return;
        closeDialog();
        emit("python-data-ready", prepared);
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    function openPythonDraft() {
      const draft = { fileName: preview.value.file.name, sourceCode: preview.value.text };
      closeDialog();
      emit("open-python-draft", draft);
    }
    const fileIcon = (file) =>
      file.kind === "CODE" || /\.(py|sql)$/i.test(file.name)
        ? FileCode2
        : file.contentType?.startsWith("image/")
          ? Image
          : file.folderId === "data"
            ? Database
            : FileText;
    const hitFor = (file) =>
      hits.value?.find((item) => file.source === "asset" && item.fileId === file.sourceId);
    async function refresh() {
      const ticket = ++loadTicket;
      busy.value = true;
      loading.value = true;
      error.value = "";
      try {
        const params = new URLSearchParams({
          folder: currentFolder.value,
          q: query.value,
          sort: sortBy.value,
          page: String(page.value),
          pageSize: String(pageSize.value),
        });
        const [loaded, tree, quota] = await Promise.all([
          apiRequest(`/data-science/user-assets/browser/files?${params}`, options()),
          apiRequest("/data-science/user-assets/browser", options()),
          apiRequest("/data-science/user-assets/space", options()),
        ]);
        if (disposed || ticket !== loadTicket) return;
        pageItems.value = loaded.items;
        page.value = loaded.page;
        total.value = loaded.total;
        totalFiles.value = loaded.totalFiles;
        folderCounts.value = loaded.folderCounts;
        organization.value = tree;
        space.value = quota;
        selected.value = selected.value.filter((key) =>
          entries.value.some((file) => file.key === key)
        );
        try {
          await apiRequest("/data-science/user-assets/knowledge", options());
          knowledgeEnabled.value = true;
        } catch {
          knowledgeEnabled.value = false;
        }
      } catch (ex) {
        if (!disposed && ticket === loadTicket) error.value = ex.message;
      } finally {
        if (!disposed && ticket === loadTicket) {
          busy.value = false;
          loading.value = false;
        }
      }
    }
    async function refreshAll() {
      emit("refresh");
      hits.value = null;
      files.value = [];
      await refresh();
    }
    function dragOver(event) {
      if (Array.from(event.dataTransfer?.types || []).includes("Files")) event.preventDefault();
    }
    function dropFiles(event) {
      if (!event.dataTransfer?.files?.length) return;
      event.preventDefault();
      if (!busy.value) upload(Array.from(event.dataTransfer.files));
    }
    function uploadFiles(event) {
      const incoming = Array.from(event.target.files || []);
      event.target.value = "";
      if (incoming.length) upload(incoming);
    }
    async function upload(incoming) {
      if (busy.value) return;
      busy.value = true;
      error.value = "";
      status.value = "";
      const destination = currentFolder.value;
      let count = 0;
      try {
        if (incoming.length > 20) throw new Error("每次最多上传 20 个文件");
        for (const file of incoming) {
          if (disposed) return;
          const body = new FormData();
          body.append("file", file);
          const saved = await apiRequest("/data-science/user-assets", {
            ...options(),
            method: "POST",
            body,
          });
          if (disposed) return;
          if (destination !== "all")
            await apiRequest("/data-science/user-assets/browser/locations", {
              ...options(),
              method: "PUT",
              body: JSON.stringify({
                folderId: destination,
                entries: [{ source: "asset", sourceId: saved.id }],
              }),
            });
          count++;
        }
        status.value = `已上传 ${count} 个文件到用户空间`;
        await refresh();
      } catch (ex) {
        if (!disposed) error.value = `${count ? `已保存 ${count} 个文件。` : ""}${ex.message}`;
      } finally {
        if (!disposed) busy.value = false;
      }
    }
    async function createFolder() {
      busy.value = true;
      error.value = "";
      try {
        const folder = await apiRequest("/data-science/user-assets/browser/folders", {
          ...options(),
          method: "POST",
          body: JSON.stringify({
            parentId: currentFolder.value === "all" ? "root" : currentFolder.value,
            name: folderName.value,
          }),
        });
        organization.value.folders.push(folder);
        folderDialog.value = false;
        currentFolder.value = folder.id;
        status.value = "文件夹已创建";
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    async function moveFiles() {
      busy.value = true;
      error.value = "";
      try {
        await apiRequest("/data-science/user-assets/browser/locations", {
          ...options(),
          method: "PUT",
          body: JSON.stringify({
            folderId: moveTarget.value,
            entries: selectedEntries.value.map((file) => ({
              source: file.source,
              sourceId: file.sourceId,
            })),
          }),
        });
        moveDialog.value = false;
        selected.value = [];
        await refresh();
        status.value = "文件已归类到所选文件夹";
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    function confirm(title, message) {
      return new Promise((resolve) => {
        confirmation.value = { title, message };
        confirmResolve = resolve;
      });
    }
    function resolveConfirm(value) {
      confirmation.value = null;
      confirmResolve?.(value);
      confirmResolve = null;
    }
    async function removeCurrentFolder() {
      if (!(await confirm("删除空文件夹", "仅删除当前空文件夹；含有文件或子文件夹时不能删除。")))
        return;
      busy.value = true;
      error.value = "";
      try {
        await apiRequest(`/data-science/user-assets/browser/folders/${currentFolder.value}`, {
          ...options(),
          method: "DELETE",
        });
        currentFolder.value = "all";
        await refresh();
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    async function remove(file) {
      if (file.source === "python-data") {
        emit("remove-data", file.original);
        return;
      }
      if (file.source === "python-script") {
        emit("remove-script", file.original);
        return;
      }
      if (!(await confirm("删除用户文件", `确定删除“${file.name}”？已归档的任务文件会继续保留。`)))
        return;
      busy.value = true;
      try {
        await apiRequest(`/data-science/user-assets/${file.sourceId}`, {
          ...options(),
          method: "DELETE",
        });
        await refresh();
        status.value = "用户文件已删除";
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    async function content(file) {
      const token = getStoredAuthSession()?.token;
      const response = await fetch(`/api/v1/data-science/user-assets/${file.sourceId}/content`, {
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...workspaceHeaders(props.workspaceContext),
        },
      });
      if (!response.ok) throw new Error("文件读取失败，请刷新后重试");
      return response.blob();
    }
    async function download(file) {
      if (file.source === "python-data") {
        emit("download-data", file.original);
        return;
      }
      try {
        const blob =
          file.source === "python-script"
            ? new Blob([file.original.sourceCode], { type: "text/x-python" })
            : await content(file);
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = file.name;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch (ex) {
        error.value = ex.message;
      }
    }
    async function openFile(file) {
      if (file.source === "python-script") {
        emit("open-script", file.original);
        return;
      }
      if (file.source === "python-data") {
        preview.value = { file, text: null, url: null };
        return;
      }
      busy.value = true;
      error.value = "";
      try {
        const value = { file, text: null, url: null };
        if (
          file.state === "AVAILABLE" &&
          file.size <= 2 * 1024 * 1024 &&
          /\.(py|sql|txt|md|json|jsonl|csv|log)$/i.test(file.name)
        )
          value.text = await (await content(file)).text();
        else if (
          file.state === "AVAILABLE" &&
          file.contentType?.startsWith("image/") &&
          file.size <= 10 * 1024 * 1024
        )
          value.url = URL.createObjectURL(
            new Blob([await content(file)], { type: file.contentType })
          );
        if (!disposed) preview.value = value;
        else if (value.url) URL.revokeObjectURL(value.url);
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    function closeDialog() {
      if (preview.value?.url) URL.revokeObjectURL(preview.value.url);
      preview.value = null;
      folderDialog.value = false;
      moveDialog.value = false;
    }
    async function openAnalysis() {
      error.value = "";
      try {
        validateAnalysisFiles(selectedEntries.value);
        analysisOpen.value = true;
        if (!models.value.length) models.value = await fetchPythonCodeModels();
        modelName.value ||=
          models.value.find((model) => model.defaultModel)?.value || models.value[0]?.value || "";
      } catch (ex) {
        error.value = ex.message;
      }
    }
    async function analyze() {
      busy.value = true;
      analyzing.value = true;
      error.value = "";
      report.value = "";
      analysisStatus.value = "正在分析已选择的原件，请保持页面打开。";
      try {
        const selectedFiles = validateAnalysisFiles(selectedEntries.value);
        const receipt = await apiRequest("/data-science/user-assets/analyses", {
          ...options(),
          method: "POST",
          body: JSON.stringify({
            ...selectedFiles,
            requestId:
              globalThis.crypto?.randomUUID?.() ||
              `${Date.now()}-${Math.random().toString(36).slice(2)}`,
            instruction: instruction.value,
            modelName: modelName.value || undefined,
          }),
        });
        if (disposed) return;
        report.value = receipt.results?.[0]?.reportMarkdown || "";
        analysisStatus.value = receipt.resultAvailable
          ? "分析完成，代码与结果已归档。"
          : `分析未完成：${receipt.summary || receipt.status || "请查看任务记录"}`;
        await refresh();
      } catch (ex) {
        error.value = ex.message;
        analysisStatus.value = "请求未完成，请先核查任务和归档记录。";
      } finally {
        busy.value = false;
        analyzing.value = false;
      }
    }
    async function searchContent() {
      busy.value = true;
      error.value = "";
      try {
        const results = await apiRequest(
          `/data-science/user-assets/knowledge/search?q=${encodeURIComponent(query.value)}&personalOnly=true`,
          options()
        );
        const originals = await Promise.all(
          results.map((hit) => apiRequest(`/data-science/user-assets/${hit.fileId}`, options()))
        );
        if (disposed) return;
        files.value = originals;
        hits.value = results;
        currentFolder.value = "all";
        page.value = 1;
        selected.value = [];
      } catch (ex) {
        error.value = ex.message;
      } finally {
        busy.value = false;
      }
    }
    onMounted(refresh);
    onBeforeUnmount(() => {
      disposed = true;
      closeDialog();
      resolveConfirm(false);
    });
    return {
      ref,
      folderTrail,
      canAnalyze,
      canDelete,
      emit,
      page,
      pageSize,
      totalFiles,
      space,
      selected,
      currentFolder,
      query,
      viewMode,
      sortBy,
      busy,
      loading,
      analyzing,
      error,
      status,
      uploadInput,
      folderDialog,
      folderName,
      moveDialog,
      moveTarget,
      preview,
      confirmation,
      analysisOpen,
      instruction,
      models,
      modelName,
      report,
      analysisStatus,
      knowledgeEnabled,
      hits,
      accept,
      folders,
      selectedEntries,
      breadcrumb,
      folderLabel,
      folderNameLabel,
      childFolders,
      isCustomFolder,
      visible,
      resultTotal,
      pageCount,
      allSelected,
      countFolder,
      openFolder,
      goPage,
      toggle,
      toggleAll,
      formatBytes,
      formatTime,
      stateLabel,
      readable,
      isPythonData,
      usePythonData,
      openPythonDraft,
      fileIcon,
      hitFor,
      refreshAll,
      dragOver,
      dropFiles,
      uploadFiles,
      createFolder,
      moveFiles,
      confirm,
      resolveConfirm,
      removeCurrentFolder,
      remove,
      content,
      download,
      openFile,
      closeDialog,
      openAnalysis,
      analyze,
      searchContent,
    };
  },
});
