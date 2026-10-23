import { ref, computed, onMounted, onActivated, watch } from "vue";
import { apiRequest, getStoredAuthSession } from "../../services/api.js";
import { useTransientNotice } from "../utils/useTransientNotice.js";
import "../../styles/pages/permission-workspace.css";
import "../../styles/pages/tool-publications.css";

export default {
  name: "ToolPublicationsView",
  setup() {
    const rows = ref([]),
      parents = ref([]),
      roles = ref([]),
      templates = ref([]);
    const busy = ref(false),
      templatesLoading = ref(false),
      notice = ref(""),
      failed = ref(false),
      loaded = ref(false);
    const query = ref(""),
      stateFilter = ref("RUNNING"),
      page = ref(1),
      pageSize = ref(15);
    const category = ref("");
    const templateCategory = ref(""),
      templatePage = ref(1),
      templatePageSize = ref(10);
    const templateCategoryOf = (item) => item.businessCategoryName || item.category || "未分类";
    const templateCategories = computed(() =>
      [...new Set(templates.value.map(templateCategoryOf))].sort((a, b) =>
        a.localeCompare(b, "zh-CN")
      )
    );
    const categoryOf = (row) => parentName(parse(row.definitionJson, {}).parentToolName);
    const categories = computed(() =>
      [...new Set(rows.value.map(categoryOf))].sort((a, b) => a.localeCompare(b, "zh-CN"))
    );
    const pendingCount = computed(
      () =>
        new Set(
          rows.value
            .filter((row) => ["DRAFT", "VALIDATED", "TESTED"].includes(row.state))
            .map((row) => row.logicalId)
        ).size
    );
    useTransientNotice(notice, failed);
    const selectedId = ref(""),
      editing = ref(false),
      activeTab = ref("overview"),
      templateQuery = ref("");
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
      outputSchema = ref('{"type":"object","properties":{}}'),
      inputSample = ref("{}");
    const permissions = getStoredAuthSession()?.user?.permissionCodes || [];
    const editable = permissions.includes("mcp:publication:edit"),
      publishable = permissions.includes("mcp:publication:publish");
    const states = {
      DRAFT: "草稿",
      VALIDATED: "已校验",
      TESTED: "测试通过",
      RUNNING: "运行中",
      OFFLINE: "已下线",
    };
    const selected = computed(() => rows.value.find((r) => r.id === selectedId.value) || null);
    const selectedDefinition = computed(() => parse(selected.value?.definitionJson, {}));
    const versions = computed(() =>
      rows.value
        .filter((r) => r.logicalId === selected.value?.logicalId)
        .sort((a, b) => b.releaseNumber - a.releaseNumber)
    );
    const tools = computed(() => {
      const groups = new Map();
      for (const row of rows.value) {
        if (
          stateFilter.value === "PENDING"
            ? !["DRAFT", "VALIDATED", "TESTED"].includes(row.state)
            : stateFilter.value && row.state !== stateFilter.value
        )
          continue;
        if (category.value && categoryOf(row) !== category.value) continue;
        const entry = groups.get(row.logicalId);
        if (!entry || row.releaseNumber > entry.releaseNumber) groups.set(row.logicalId, row);
      }
      const search = query.value.trim().toLowerCase();
      return [...groups.values()]
        .filter(
          (r) =>
            !search ||
            [r.title, r.description].some((v) =>
              String(v || "")
                .toLowerCase()
                .includes(search)
            )
        )
        .sort((a, b) => a.title.localeCompare(b.title, "zh-CN"));
    });
    const pages = computed(() => Math.max(1, Math.ceil(tools.value.length / pageSize.value)));
    const visibleTools = computed(() =>
      tools.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
    );
    const filteredTemplates = computed(() =>
      templates.value.filter(
        (t) =>
          (!templateCategory.value || templateCategoryOf(t) === templateCategory.value) &&
          String(`${t.title || ""} ${t.description || ""}`)
            .toLowerCase()
            .includes(templateQuery.value.trim().toLowerCase())
      )
    );
    const templatePages = computed(() =>
      Math.max(1, Math.ceil(filteredTemplates.value.length / templatePageSize.value))
    );
    const visibleTemplates = computed(() =>
      filteredTemplates.value.slice(
        (templatePage.value - 1) * templatePageSize.value,
        templatePage.value * templatePageSize.value
      )
    );
    const selectedTemplates = computed(() =>
      templates.value.filter((t) => definition.value.templateKeys.includes(t.key))
    );
    function selectTemplatePage() {
      definition.value.templateKeys = [
        ...new Set([...definition.value.templateKeys, ...visibleTemplates.value.map((t) => t.key)]),
      ];
    }
    function clearTemplateSelection() {
      definition.value.templateKeys = [];
    }
    const unavailableTemplates = computed(() =>
      definition.value.templateKeys.filter((key) => !templates.value.some((t) => t.key === key))
    );
    const testable = computed(
      () => editable && selected.value && ["VALIDATED", "TESTED"].includes(selected.value.state)
    );
    const snapshot = () =>
      JSON.stringify([form.value, definition.value, inputSchema.value, outputSchema.value]);
    const originalDraft = ref("");
    const dirty = computed(() => editing.value && snapshot() !== originalDraft.value);
    watch([query, stateFilter, pageSize, category], () => {
      page.value = 1;
      if (!editing.value && !tools.value.some((row) => row.logicalId === selected.value?.logicalId))
        selectedId.value = tools.value[0]?.id || "";
    });
    watch(pages, (n) => {
      page.value = Math.min(page.value, n);
    });
    watch([templateQuery, templateCategory, templatePageSize], () => {
      templatePage.value = 1;
    });
    watch(templatePages, (n) => {
      templatePage.value = Math.min(templatePage.value, n);
    });
    function parse(value, fallback) {
      try {
        return typeof value === "string" ? JSON.parse(value) : value || fallback;
      } catch {
        return fallback;
      }
    }
    function pretty(value) {
      try {
        return JSON.stringify(typeof value === "string" ? JSON.parse(value) : value, null, 2);
      } catch {
        return value || "";
      }
    }
    const roleName = (id) => roles.value.find((r) => r.id === id)?.roleName || "已配置角色";
    const parentName = (name) =>
      parents.value.find((p) => p.toolName === name)?.title || "受控模板能力";
    const dateLabel = (value) => (value ? new Date(value).toLocaleString("zh-CN") : "—");
    function discard() {
      return !dirty.value || window.confirm("有尚未保存的工具定义，确认放弃？");
    }
    async function perform(operation, message) {
      busy.value = true;
      failed.value = false;
      notice.value = "";
      try {
        const result = await operation();
        if (message) notice.value = message;
        return result;
      } catch (error) {
        failed.value = true;
        notice.value = error.message || "操作失败，请重试";
        return null;
      } finally {
        busy.value = false;
      }
    }
    async function loadRows(preferredId = selectedId.value) {
      rows.value = await apiRequest("/tool-publications");
      selectedId.value = rows.value.some((r) => r.id === preferredId)
        ? preferredId
        : tools.value[0]?.id || "";
    }
    async function refresh() {
      await perform(async () => {
        await loadRows();
        const values = await Promise.allSettled([
          apiRequest("/tool-publications/catalog/parents"),
          apiRequest("/tool-publications/catalog/roles"),
        ]);
        if (values[0].status === "fulfilled") parents.value = values[0].value;
        if (values[1].status === "fulfilled") roles.value = values[1].value;
        if (values.some((r) => r.status === "rejected")) {
          failed.value = true;
          notice.value = "版本列表已加载，执行配置目录读取失败，请刷新后再编辑。";
        }
        loaded.value = true;
      });
    }
    function choose(row) {
      if (!discard()) return;
      selectedId.value = row.id;
      editing.value = false;
      activeTab.value = "overview";
      notice.value = "";
      inputSample.value = "{}";
    }
    function chooseVersion(event) {
      const row = rows.value.find((r) => r.id === event.target.value);
      if (row) choose(row);
      event.target.value = selectedId.value;
    }
    let templateSerial = 0;
    async function loadTemplates(clearSelection = true) {
      const serial = ++templateSerial;
      templates.value = [];
      templateQuery.value = "";
      templateCategory.value = "";
      templatePage.value = 1;
      if (clearSelection) definition.value.templateKeys = [];
      if (!definition.value.roleId || !definition.value.parentToolName) {
        templatesLoading.value = false;
        return;
      }
      templatesLoading.value = true;
      try {
        const values = await apiRequest(
          `/tool-publications/catalog/templates?roleId=${encodeURIComponent(definition.value.roleId)}&parentToolName=${encodeURIComponent(definition.value.parentToolName)}`
        );
        if (serial === templateSerial) templates.value = values;
      } catch (error) {
        if (serial === templateSerial) {
          failed.value = true;
          notice.value = error.message || "模板目录加载失败";
        }
      } finally {
        if (serial === templateSerial) templatesLoading.value = false;
      }
    }
    function reset() {
      if (!discard()) return;
      ++templateSerial;
      templatesLoading.value = false;
      form.value = { logicalId: null, title: "", description: "" };
      definition.value = blank();
      templates.value = [];
      templateQuery.value = "";
      templateCategory.value = "";
      templatePage.value = 1;
      inputSchema.value = outputSchema.value = '{"type":"object","properties":{}}';
      inputSample.value = "{}";
      editing.value = true;
      failed.value = false;
      notice.value = "";
      originalDraft.value = snapshot();
    }
    async function startRevision() {
      if (!selected.value || !editable || !discard()) return;
      const row = selected.value;
      form.value = {
        logicalId: row.logicalId,
        title: row.title,
        description: row.description || "",
      };
      definition.value = { ...blank(), ...parse(row.definitionJson, {}), enabled: false };
      inputSchema.value = pretty(row.inputSchemaJson);
      outputSchema.value = pretty(row.outputSchemaJson);
      inputSample.value = "{}";
      editing.value = true;
      notice.value = "";
      originalDraft.value = snapshot();
      await loadTemplates(false);
    }
    function cancel() {
      if (discard()) editing.value = false;
    }
    async function save() {
      if (!editable || busy.value || templatesLoading.value) return;
      if (
        !form.value.title ||
        !definition.value.templateKeys.length ||
        unavailableTemplates.value.length
      ) {
        failed.value = true;
        notice.value = "请填写工具名称并选择当前角色可用的模板。";
        return;
      }
      await perform(async () => {
        let input, output;
        try {
          input = JSON.parse(inputSchema.value);
          output = JSON.parse(outputSchema.value);
        } catch {
          throw new Error("输入或输出结构不是有效 JSON，请在高级结构配置中修正。");
        }
        const created = await apiRequest("/tool-publications", {
          method: "POST",
          body: JSON.stringify({
            ...form.value,
            kind: "CONTROLLED_TEMPLATE",
            definition: definition.value,
            inputSchema: input,
            outputSchema: output,
          }),
        });
        stateFilter.value = "DRAFT";
        await loadRows(created.id);
        editing.value = false;
        activeTab.value = "overview";
      }, "草稿已保存。完成校验和合约测试后即可发布。");
    }
    async function action(row, actionName, body) {
      if (!row || busy.value) return;
      await perform(async () => {
        await apiRequest(`/tool-publications/${row.id}/${actionName}`, {
          method: "POST",
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
        await loadRows(row.id);
        stateFilter.value =
          actionName === "publish" || actionName === "rollback"
            ? "RUNNING"
            : actionName === "offline"
              ? "OFFLINE"
              : "PENDING";
        activeTab.value = actionName === "validate" ? "testing" : "overview";
      }, { validate: "定义校验通过，可以运行合约测试。", test: "合约测试通过，结果已记录。", publish: "工具已发布，运行时业务权限仍然生效。", offline: "工具已下线，版本与测试记录已保留。", rollback: "已恢复此历史版本。" }[actionName]);
    }
    async function test(row = selected.value) {
      let input;
      try {
        input = JSON.parse(inputSample.value);
      } catch {
        failed.value = true;
        notice.value = "测试输入不是有效 JSON，请修正后重试。";
        return;
      }
      await action(row, "test", { input });
    }
    function confirmAction(row, actionName) {
      if (
        window.confirm(
          actionName === "offline"
            ? "确认下线此工具版本？新调用将停止使用它。"
            : "确认恢复此历史版本？系统会重新检查当前授权，并切换运行版本。"
        )
      )
        return action(row, actionName);
    }
    onMounted(refresh);
    onActivated(() => {
      if (loaded.value && !editing.value) refresh();
    });
    return {
      rows,
      category,
      categories,
      categoryOf,
      pendingCount,
      templateCategory,
      templateCategoryOf,
      templateCategories,
      templatePage,
      templatePageSize,
      templatePages,
      visibleTemplates,
      selectedTemplates,
      selectTemplatePage,
      clearTemplateSelection,
      parents,
      roles,
      templates,
      busy,
      templatesLoading,
      notice,
      failed,
      query,
      stateFilter,
      page,
      pageSize,
      pages,
      tools,
      visibleTools,
      selected,
      selectedId,
      selectedDefinition,
      versions,
      editing,
      activeTab,
      templateQuery,
      filteredTemplates,
      unavailableTemplates,
      testable,
      form,
      definition,
      inputSchema,
      outputSchema,
      inputSample,
      editable,
      publishable,
      states,
      roleName,
      parentName,
      dateLabel,
      pretty,
      refresh,
      choose,
      chooseVersion,
      loadTemplates,
      reset,
      startRevision,
      cancel,
      save,
      action,
      test,
      confirmAction,
    };
  },
};
