import {
  defineComponent,
  ref,
  computed,
  watch,
  onBeforeUnmount,
  onActivated,
  onDeactivated,
} from "vue";
import * as api from "../../../services/sqlStudioApi";
import { readWorkspaceDraft, saveWorkspaceDraft } from "../../utils/workspaceDrafts";
import { SqlMetadataSession, StaleMetadataRequest } from "../../utils/sqlMetadataSession";
import {
  namespaceKey,
  qualifiedSqlTable,
  quoteSqlIdentifier,
  schemaSuggestions,
} from "../../editor/sqlSchemaCompletion";

export default defineComponent({
  name: "SqlSchemaExplorer",
  props: { projectId: String, fileId: String, controlsTarget: Object, identity: Object, dialect: { type: String, default: "spark" } },
  emits: ["insert", "dialect", "selection-count"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    const props = componentProps;
    const emit = emitEvent;
    const providers = ref([]),
      serviceId = ref(""),
      assets = ref([]),
      assetRef = ref(""),
      namespaces = ref([]),
      selectedNamespace = ref("");
    const context = ref(null),
      sourceDialect = ref(""),
      tables = ref([]),
      fields = ref({}),
      expanded = ref({});
    const busy = ref(false),
      error = ref(""),
      query = ref(""),
      total = ref(0),
      tablesMore = ref(false),
      tablePage = ref(1),
      assetsMore = ref(false),
      namespacesMore = ref(false);
    const selectedTables = ref([]);
    const assetSearch = ref(""), namespaceSearch = ref("");
    const namespaceLabel = computed(() => {
      const dialect = sourceDialect.value.toLowerCase();
      if (['oracle', 'postgres', 'postgresql'].includes(dialect)) return '数据库 / Schema';
      if (['mysql', 'hive', 'spark', 'clickhouse', 'doris'].includes(dialect)) return '数据库';
      return '数据库 / Schema';
    });
    const filteredAssets = computed(() => assets.value.filter(item => item.assetRef === assetRef.value ||
      (item.displayName || item.name || "").toLowerCase().includes(assetSearch.value.trim().toLowerCase())));
    const filteredNamespaces = computed(() => namespaces.value.filter(item => namespaceKey(item) === selectedNamespace.value ||
      [item.database, item.schema].filter(Boolean).join(" / ").toLowerCase().includes(namespaceSearch.value.trim().toLowerCase())));
    const savedSelectionKey = () => `sql-studio-source-${props.projectId}-${props.fileId}`;
    function rememberSelection() {
      if (props.fileId && context.value) saveWorkspaceDraft(savedSelectionKey(), props.identity, {
        serviceId: serviceId.value, assetRef: assetRef.value, namespace: selectedNamespace.value,
      });
    }
    function forgetSelection() {
      if (props.fileId) saveWorkspaceDraft(savedSelectionKey(), props.identity, null);
    }
    watch(selectedTables, (value) => emit("selection-count", value.length));
    const tablePageSize = ref(50),
      tableList = ref(null),
      submittedQuery = ref("");
    const tablePages = computed(() => Math.max(1, Math.ceil(total.value / tablePageSize.value)));
    let serial = 0,
      assetPage = 1,
      assetSnapshot,
      namespacePage = 1,
      active = true;
    function clearRows() {
      tables.value = [];
      fields.value = {};
      expanded.value = {};
      total.value = 0;
      tablesMore.value = false;
      tablePage.value = 1;
    }
    const session = new SqlMetadataSession(
      (scope, args) =>
        api.sqlMetadataOperation(scope.projectId, scope.discoveryId, scope.bindingId, args),
      () => {
        clearRows();
        namespaces.value = [];
        namespacesMore.value = false;
      }
    );
    const namespaceSession = new SqlMetadataSession(
      (scope, args) =>
        api.sqlMetadataOperation(scope.projectId, scope.discoveryId, scope.bindingId, args),
      () => {
        namespaces.value = [];
        namespacesMore.value = false;
        clearRows();
      }
    );
    function scopeOf(value) {
      return {
        identity: JSON.stringify(props.identity || {}),
        projectId: props.projectId,
        discoveryId: value.discoveryId,
        ...value.bindings[0],
      };
    }
    function bind(value) {
      context.value = value;
      const binding = value?.bindings?.[0];
      sourceDialect.value = binding?.sourceDialect || "";
      session.bind(
        binding
          ? {
              identity: JSON.stringify(props.identity || {}),
              projectId: props.projectId,
              discoveryId: value.discoveryId,
              ...binding,
            }
          : null
      );
      clearRows();
    }
    async function run(action) {
      const turn = serial;
      error.value = "";
      busy.value = true;
      try {
        await action(turn);
      } catch (ex) {
        if (turn === serial && !(ex instanceof StaleMetadataRequest)) {
          error.value = ex.message || "元数据读取失败";
          session.invalidate();
        }
      } finally {
        if (turn === serial) busy.value = false;
      }
    }
    async function initialize() {
      selectedTables.value = [];
      serial++;
      session.bind(null);
      namespaceSession.bind(null);
      context.value = null;
      providers.value = [];
      serviceId.value = "";
      assets.value = [];
      assetRef.value = "";
      namespaces.value = [];
      selectedNamespace.value = "";
      query.value = "";
      assetSearch.value = "";
      namespaceSearch.value = "";
      submittedQuery.value = "";
      clearRows();
      if (!props.projectId) return;
      await run(async (turn) => {
        const values = await api.sqlMetadataProviders(props.projectId);
        if (turn !== serial) return;
        providers.value = values;
        const stored = props.fileId && readWorkspaceDraft(savedSelectionKey(), props.identity);
        const provider = values.find(item => item.serviceId === stored?.serviceId) || (values.length === 1 ? values[0] : null);
        if (provider) {
          serviceId.value = provider.serviceId;
          await fetchAssets(turn, false);
          if (stored?.serviceId !== provider.serviceId) return;
          // Restore identifiers only after the current user's authorized directory confirms them.
          while (turn === serial && stored?.assetRef && assetsMore.value && !assets.value.some(item => item.assetRef === stored.assetRef)) await fetchAssets(turn, true);
          if (turn !== serial || !assets.value.some(item => item.assetRef === stored?.assetRef)) return;
          assetRef.value = stored.assetRef;
          if (!await createContext(turn, {})) return;
          namespaceSession.bind(scopeOf(context.value));
          await fetchNamespaces(turn, false);
          while (turn === serial && stored.namespace && namespacesMore.value && !namespaces.value.some(item => namespaceKey(item) === stored.namespace)) await fetchNamespaces(turn, true);
          if (turn !== serial) return;
          const namespace = namespaces.value.find(item => namespaceKey(item) === stored.namespace);
          if (stored.namespace && !namespace) {
            bind(null);
            throw new Error('原数据库或 Schema 已不可用，请重新选择数据源。');
          }
          if (namespace) {
            selectedNamespace.value = stored.namespace;
            if (!await createContext(turn, namespace)) return;
          }
          await fetchTables(turn, 1);
        }
      });
    }
    async function fetchAssets(turn, more) {
      const page = more ? assetPage + 1 : 1;
      const result = await api.sqlMetadataAssets(
        props.projectId,
        serviceId.value,
        page,
        more ? assetSnapshot : undefined
      );
      if (turn !== serial) return;
      if (result.metadata?.status !== "SUCCEEDED") throw new Error("授权数据源目录不可用");
      assets.value = more ? [...assets.value, ...result.metadata.items] : result.metadata.items;
      assetPage = page;
      assetSnapshot = result.metadata.snapshotId;
      assetsMore.value = result.metadata.hasMore;
    }
    async function chooseService() {
      forgetSelection();
      serial++;
      bind(null);
      namespaceSession.bind(null);
      assetRef.value = "";
      assets.value = [];
      namespaces.value = [];
      selectedNamespace.value = "";
      if (serviceId.value) await run((turn) => fetchAssets(turn, false));
    }
    async function loadAssets(more) {
      await run((turn) => fetchAssets(turn, more));
    }
    async function createContext(turn, namespace) {
      const result = await api.createSqlMetadataContext(props.projectId, {
        purpose: "QUERY",
        targetDialect: props.dialect,
        bindings: [
          { serviceId: serviceId.value, assetRef: assetRef.value, role: "SOURCE", ...namespace },
        ],
      });
      if (turn !== serial) return false;
      bind(result);
      return true;
    }
    async function chooseAsset() {
      forgetSelection();
      namespaceSearch.value = "";
      serial++;
      bind(null);
      namespaceSession.bind(null);
      namespaces.value = [];
      selectedNamespace.value = "";
      query.value = "";
      submittedQuery.value = "";
      if (!assetRef.value) return;
      await run(async (turn) => {
        if (await createContext(turn, {})) {
          namespaceSession.bind(scopeOf(context.value));
          await fetchNamespaces(turn, false);
          await fetchTables(turn, 1);
          if (turn === serial) rememberSelection();
        }
      });
    }
    async function fetchNamespaces(turn, more) {
      const page = more ? namespacePage + 1 : 1;
      const result = await namespaceSession.read({
        operation: "LIST_NAMESPACES",
        page,
        pageSize: 50,
        includeColumns: false,
      });
      if (turn !== serial) return;
      namespaces.value = more ? [...namespaces.value, ...result.items] : result.items;
      namespacePage = page;
      namespacesMore.value = result.hasMore;
    }
    async function loadNamespaces(more) {
      await run((turn) => fetchNamespaces(turn, more));
    }
    async function chooseNamespace() {
      const namespace =
        namespaces.value.find((item) => namespaceKey(item) === selectedNamespace.value) || {};
      serial++;
      session.bind(null);
      clearRows();
      await run(async (turn) => {
        if (await createContext(turn, namespace)) { await fetchTables(turn, 1); if (turn === serial) rememberSelection(); }
      });
    }
    async function fetchTables(turn, page) {
      const args = {
        operation: "SEARCH",
        page,
        pageSize: tablePageSize.value,
        includeColumns: false,
      };
      if (submittedQuery.value) args.query = submittedQuery.value;
      const result = await session.read(args);
      if (turn !== serial) return;
      tables.value = result.items;
      tablePage.value = page;
      tablesMore.value = result.hasMore;
      total.value = result.totalMatched;
      fields.value = {};
      expanded.value = {};
      if (tableList.value) tableList.value.scrollTop = 0;
    }
    async function loadTables(page = 1) {
      if (context.value && !busy.value) await run((turn) => fetchTables(turn, page));
    }
    async function searchTables() {
      if (busy.value) return;
      submittedQuery.value = query.value.trim();
      await loadTables();
    }
    async function expand(table) {
      if (expanded.value[table.tableRef]) {
        expanded.value = { ...expanded.value, [table.tableRef]: false };
        return;
      }
      await run(async (turn) => {
        const args = { operation: "DESCRIBE", tableName: table.tableName, page: 1, pageSize: 1 };
        if (table.database != null) args.database = table.database;
        if (table.schema != null) args.schema = table.schema;
        const result = await session.read(args);
        if (turn !== serial) return;
        fields.value = { ...fields.value, [table.tableRef]: result.items[0]?.columns || [] };
        expanded.value = { ...expanded.value, [table.tableRef]: true };
      });
    }
    async function refresh() {
      if (context.value) {
        session.invalidate();
        namespaceSession.invalidate();
        await run(async (turn) => {
          await fetchNamespaces(turn, false);
          await fetchTables(turn, 1);
        });
      } else if (serviceId.value) await loadAssets(false);
      else await initialize();
    }
    async function insert(table, column) {
      if (!active || busy.value) return;
      await run(async (turn) => {
        await session.authorize();
        if (turn === serial && active)
          emit(
            "insert",
            column
              ? quoteSqlIdentifier(column.name, props.dialect)
              : qualifiedSqlTable(table, props.dialect)
          );
      });
    }
    async function complete(source, position, dialect, cancelled) {
      if (!context.value || !active) return [];
      const turn = serial;
      try {
        return await schemaSuggestions({
          source,
          position,
          dialect,
          session,
          isCancelled: () => turn !== serial || !active || cancelled?.(),
        });
      } catch (ex) {
        if (turn === serial && !(ex instanceof StaleMetadataRequest))
          error.value = ex.message || "元数据需要重新验证";
        return [];
      }
    }
    function suspend() {
      if (!active) return;
      serial++;
      active = false;
      busy.value = false;
      session.suspend();
      namespaceSession.suspend();
    }
    function resume() {
      if (active) return;
      active = true;
      if (context.value)
        void run(async () => {
          await session.authorize();
          await namespaceSession.authorize();
        });
    }
    function visibility() {
      if (document.hidden) suspend();
      else resume();
    }
    window.addEventListener("blur", suspend);
    window.addEventListener("focus", resume);
    document.addEventListener("visibilitychange", visibility);
    watch([() => props.projectId, () => props.fileId, () => JSON.stringify(props.identity || {})], initialize, {
      immediate: true,
    });
    onDeactivated(suspend);
    onActivated(() => {
      if (!active) resume();
    });
    onBeforeUnmount(() => {
      serial++;
      session.bind(null);
      namespaceSession.bind(null);
      window.removeEventListener("blur", suspend);
      window.removeEventListener("focus", resume);
      document.removeEventListener("visibilitychange", visibility);
    });
    function tableKey(table) {
      const binding = context.value?.bindings?.[0];
      return JSON.stringify([
        binding?.serviceId,
        binding?.assetRef,
        table.database ?? "",
        table.schema ?? "",
        table.tableName,
      ]);
    }
    function isSelected(table) {
      return selectedTables.value.some((item) => item.key === tableKey(table));
    }
    function removeSelection(key) {
      selectedTables.value = selectedTables.value.filter((item) => item.key !== key);
    }
    async function toggleSelection(table) {
      const key = tableKey(table);
      if (isSelected(table)) {
        removeSelection(key);
        return;
      }
      if (busy.value) return;
      await run(async (turn) => {
        if (selectedTables.value.length >= 16) throw new Error("单次最多选择 16 张表");
        const binding = context.value.bindings[0];
        const sources = new Set(
          selectedTables.value.map((item) => JSON.stringify([item.serviceId, item.assetRef]))
        );
        sources.add(JSON.stringify([binding.serviceId, binding.assetRef]));
        if (sources.size > 8) throw new Error("单次最多选择 8 个数据源");
        const args = { operation: "DESCRIBE", tableName: table.tableName, page: 1, pageSize: 1 };
        if (table.database != null) args.database = table.database;
        if (table.schema != null) args.schema = table.schema;
        const result = await session.read(args);
        if (turn !== serial) return;
        const described = result.items?.[0];
        if (
          described?.tableName !== table.tableName ||
          (table.database != null && described.database !== table.database) ||
          (table.schema != null && described.schema !== table.schema) ||
          !described.columns?.length
        )
          throw new Error("无法确认所选表的可见结构");
        const asset = assets.value.find((item) => item.assetRef === binding.assetRef);
        selectedTables.value = [
          ...selectedTables.value,
          {
            key,
            serviceId: binding.serviceId,
            assetRef: binding.assetRef,
            label: [
              asset?.displayName || asset?.name || binding.assetRef,
              table.database,
              table.schema,
              table.tableName,
            ]
              .filter(Boolean)
              .join(" / "),
            discoveryId: context.value.discoveryId,
            bindingId: binding.bindingId,
            expectedSnapshotId: session.snapshot,
            table: { tableName: table.tableName, database: table.database, schema: table.schema },
          },
        ];
      });
    }
    function codingSelection() {
      if (!selectedTables.value.length) return null;
      const groups = new Map();
      for (const item of selectedTables.value) {
        const key = JSON.stringify([item.discoveryId, item.bindingId, item.expectedSnapshotId]);
        if (!groups.has(key))
          groups.set(key, {
            discoveryId: item.discoveryId,
            bindingId: item.bindingId,
            expectedSnapshotId: item.expectedSnapshotId,
            tables: [],
          });
        groups.get(key).tables.push(item.table);
      }
      return { sources: [...groups.values()] };
    }

    exposeComponent({ complete, codingSelection, assistantContext:()=>!busy.value && !error.value ? context.value?.discoveryId || null : null });
    return {
      ref,
      namespaceKey,
      emit,
      providers,
      assetSearch, namespaceSearch, filteredAssets, filteredNamespaces, namespaceLabel,
      serviceId,
      assets,
      assetRef,
      namespaces,
      selectedNamespace,
      context,
      sourceDialect,
      tables,
      fields,
      expanded,
      busy,
      error,
      query,
      total,
      tablesMore,
      tablePage,
      assetsMore,
      namespacesMore,
      selectedTables,
      tablePageSize,
      tableList,
      tablePages,
      chooseService,
      loadAssets,
      chooseAsset,
      loadNamespaces,
      chooseNamespace,
      loadTables,
      searchTables,
      expand,
      refresh,
      insert,
      isSelected,
      removeSelection,
      toggleSelection,
    };
  },
});
