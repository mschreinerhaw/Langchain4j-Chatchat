import { computed, ref, onMounted, onActivated, watch, onBeforeUnmount } from "vue";
import PermissionResourceEditor from "../../components/PermissionResourceEditor.vue";
import { saveResourceAuthorization } from "../utils/permissionWrites.js";
import {
  apiRequest,
  fetchResearchLibrary,
  fetchDomainSkills,
  fetchMcpRegisteredTools,
  fetchAgentOptions,
  fetchOrgs,
  fetchRoleAuthorization,
  saveRoleAuthorization,
  createRole,
  updateRole,
  deleteRole,
  getStoredAuthSession,
} from "../../services/api.js";
import {
  resourceGroups,
  resourceCatalog,
  roleResourceState,
  mergePermissionSelection,
} from "../utils/permissionWorkspace.js";
import "../../styles/pages/permission-workspace.css";

export default {
  name: "PermissionConsoleView",
  components: { PermissionResourceEditor },
  props: { mode: { type: String, default: "roles" } },
  setup(props) {
    const catalog = ref({
        roles: [],
        users: [],
        roleAuthorizations: [],
        resourceGrants: [],
        grants: [],
        permissions: [],
        tools: [],
      }),
      extras = ref({ services: [], documents: [], categories: [], skills: [], tools: [] });
    const ready = ref(false),
      busy = ref(false),
      notice = ref(""),
      failed = ref(false),
      roleId = ref(""),
      resourceKey = ref("");
    const masterQuery = ref(""),
      rowQuery = ref(""),
      resourceGroup = ref(""),
      roleGroup = ref("workspace"),
      stateFilter = ref(""),
      masterPageNumber = ref(1),
      rowPage = ref(1),
      pageSize = ref(20);
    const originalPermissions = ref([]),
      permissionDraft = ref([]),
      roleForm = ref(null),
      formError = ref(""),
      memberDraft = ref([]),
      serviceDraft = ref([]),
      scopeDraft = ref([]),
      orgs = ref([]);
    const codes = getStoredAuthSession()?.user?.permissionCodes || [];
    const has = (code) => codes.includes(code);
    const canManageRoles = has("system:role");
    const bulkSelected = ref([]);
    const canEditResource = (row) =>
      row.backend === "tool"
        ? has("system:permission-center:manage")
        : has("system:resource-grants:manage");
    const canAuthorizeRoles = has("system:role:authorize");
    const resources = computed(() => resourceCatalog(catalog.value, extras.value));
    const selectedRole = computed(() => catalog.value.roles.find((r) => r.id === roleId.value));
    const selectedAuthorization = computed(
      () =>
        catalog.value.roleAuthorizations.find((a) => a.role.id === roleId.value) || {
          permissionIds: [],
          userIds: [],
          agentIds: [],
          orgScopes: [],
        }
    );
    const selectedResource = computed(() =>
      resources.value.find((r) => r.key === resourceKey.value)
    );
    const canEdit = computed(() =>
      selectedResource.value?.backend === "tool"
        ? has("system:permission-center:manage")
        : selectedResource.value?.backend === "resource"
          ? has("system:resource-grants:manage")
          : has("system:role:authorize")
    );
    const groupLabel = (id) => resourceGroups.find((g) => g.id === id)?.label || "其他资源";
    const masterItems = computed(() => {
      const query = masterQuery.value.toLowerCase();
      return (
        props.mode === "roles"
          ? catalog.value.roles
          : resources.value.filter((r) => !resourceGroup.value || r.group === resourceGroup.value)
      ).filter((r) => [r.roleName,r.name,r.code,r.roleCode].filter(Boolean).join(' ').toLowerCase().includes(query));
    });
    const masterPages = computed(() => Math.max(1, Math.ceil(masterItems.value.length / 20)));
    const masterPage = computed(() =>
      masterItems.value.slice((masterPageNumber.value - 1) * 20, masterPageNumber.value * 20)
    );
    const stateFor = (row) => roleResourceState(row, catalog.value, roleId.value);
    const roleRows = computed(() =>
      resources.value.filter((row) => {
        if (
          row.group !== roleGroup.value ||
          !row.name.toLowerCase().includes(rowQuery.value.toLowerCase())
        )
          return false;
        const state = stateFor(row);
        return (
          !stateFilter.value ||
          (stateFilter.value === "direct" && state.direct.length) ||
          (stateFilter.value === "inherited" && state.inherited.length) ||
          (stateFilter.value === "none" && !state.rules.length)
        );
      })
    );
    const rowPages = computed(() => Math.max(1, Math.ceil(roleRows.value.length / pageSize.value)));
    const visibleRoleRows = computed(() =>
      roleRows.value.slice((rowPage.value - 1) * pageSize.value, rowPage.value * pageSize.value)
    );
    const permissionDirty = computed(
      () =>
        [...permissionDraft.value].sort().join("|") !==
        [...originalPermissions.value].sort().join("|")
    );
    const allVisibleSelected = computed(
      () =>
        roleRows.value.length > 0 &&
        roleRows.value.every((r) => permissionDraft.value.includes(r.id))
    );
    const allResourceRowsSelected = computed(
      () =>
        roleRows.value.length > 0 &&
        roleRows.value.every((row) => bulkSelected.value.includes(row.key))
    );
    function selectResourceRows(selected) {
      bulkSelected.value = selected
        ? [
            ...new Set([
              ...bulkSelected.value,
              ...roleRows.value.filter(canEditResource).map((r) => r.key),
            ]),
          ]
        : bulkSelected.value.filter((key) => !roleRows.value.some((r) => r.key === key));
    }
    async function allowSelectedResources() {
      const rows = resources.value.filter((r) => bulkSelected.value.includes(r.key));
      if (!rows.length) return;
      if (rows.some((row) => stateFor(row).direct.some((g) => g.scopeExpression || g.agentId))) {
        notice.value =
          "所选资源包含已有数据范围或查询服务限定。请逐项管理，批量操作不会扩大这些范围。";
        failed.value = true;
        return;
      }
      if (
        !window.confirm(
          `为“${selectedRole.value.roleName}”添加 ${rows.length} 项资源的允许规则？现有拒绝、继承及资源原有约束仍生效。`
        )
      )
        return;
      busy.value = true;
      failed.value = false;
      let saved = 0;
      try {
        for (const row of rows) {
          await saveResourceAuthorization(row, catalog.value, {
            targetType: "ROLE",
            targetId: roleId.value,
            effect: "allow",
            scopeExpression: "",
          });
          saved++;
          bulkSelected.value = bulkSelected.value.filter((key) => key !== row.key);
        }
        await loadCatalog();
        notice.value = `已保存 ${saved} 项允许规则，并已记录审计`;
      } catch (e) {
        failed.value = true;
        notice.value = `已保存 ${saved}/${rows.length} 项，其余保留选中。保存失败：${e.message}`;
        if (saved) await loadCatalog();
      } finally {
        busy.value = false;
      }
    }
    function syncSelection() {
      originalPermissions.value = [...selectedAuthorization.value.permissionIds];
      permissionDraft.value = [...originalPermissions.value];
    }
    function discard() {
      return !permissionDirty.value || window.confirm("有尚未保存的权限变更，确认放弃？");
    }
    function chooseMaster(item) {
      if (!discard()) return;
      resourceKey.value = props.mode === "resources" ? item.key : "";
      if (props.mode === "roles") {
        roleId.value = item.id;
        syncSelection();
      }
      notice.value = "";
    }
    function togglePermission(id, selected) {
      permissionDraft.value = selected
        ? [...new Set([...permissionDraft.value, id])]
        : permissionDraft.value.filter((p) => p !== id);
    }
    function selectVisible(selected) {
      for (const row of roleRows.value) togglePermission(row.id, selected);
    }
    let serial = 0;
    async function loadCatalog() {
      const current = ++serial;
      const data = await apiRequest("/permission-center");
      if (current !== serial) return;
      catalog.value = data;
      if (!data.roles.some((r) => r.id === roleId.value)) roleId.value = data.roles[0]?.id || "";
      if (!permissionDirty.value) syncSelection();
      ready.value = true;
    }
    async function load() {
      if (!discard()) return;
      busy.value = true;
      failed.value = false;
      notice.value = "";
      try {
        permissionDraft.value = [...originalPermissions.value];
        await loadCatalog();
        const extraResults = await Promise.allSettled([
          fetchMcpRegisteredTools(),
          fetchResearchLibrary({ tenantId: catalog.value.tenantId, page: 1, pageSize: 100 }),
          fetchDomainSkills({ page: 0, pageSize: 100 }),
          fetchAgentOptions(),
          fetchOrgs(catalog.value.tenantId),
        ]);
        const [tools, library, skills, services, organizations] = extraResults.map((r) =>
          r.status === "fulfilled" ? r.value : null
        );
        if (tools) extras.value.tools = Array.isArray(tools) ? tools : [];
        if (library) {
          extras.value.documents = library.documents || [];
          extras.value.categories = library.categories || [];
          for (let page = 2; page <= Math.max(1, library.totalPages || 1); page++) {
            const next = await fetchResearchLibrary({
              tenantId: catalog.value.tenantId,
              page,
              pageSize: 100,
            });
            extras.value.documents.push(...(next.documents || []));
          }
        }
        if (skills) {
          extras.value.skills = skills.skills || [];
          for (let page = 1; page < (skills.totalPages || 1); page++) {
            const next = await fetchDomainSkills({ page, pageSize: 100 });
            extras.value.skills.push(...(next.skills || []));
          }
        }
        if (services) extras.value.services = services;
        if (organizations) orgs.value = organizations;
        if (props.mode === "resources" && !selectedResource.value)
          resourceKey.value = resources.value[0]?.key || "";
        const missing = ["工具", "知识库", "领域技能", "查询服务", "组织"].filter((_, index) => extraResults[index].status === "rejected");
        notice.value = missing.length
          ? `权限目录已加载。${missing.join("、")}名称目录不可读取，现有规则仍保留，可在详情查看标识。`
          : "";
      } catch (e) {
        failed.value = true;
        notice.value = e.message || "目录加载失败，请重试";
      } finally {
        busy.value = false;
      }
    }
    async function reloadAfterChange() {
      try {
        await loadCatalog();
        notice.value = "授权目录已刷新";
        failed.value = false;
      } catch (e) {
        failed.value = true;
        notice.value = "操作已提交，但刷新失败：" + e.message;
      }
    }
    async function savePermissions() {
      const removed = originalPermissions.value.some((id) => !permissionDraft.value.includes(id));
      if (removed && !window.confirm("确认撤销已取消勾选的权限？保存后将影响角色成员的操作。"))
        return;
      busy.value = true;
      failed.value = false;
      try {
        const latest = await fetchRoleAuthorization(roleId.value);
        const merged = mergePermissionSelection(
          latest,
          originalPermissions.value,
          permissionDraft.value
        );
        await saveRoleAuthorization(roleId.value, merged);
        originalPermissions.value = [...merged.permissionIds];
        permissionDraft.value = [...merged.permissionIds];
        await loadCatalog();
        notice.value = "角色权限已保存，并已记录审计";
      } catch (e) {
        failed.value = true;
        notice.value = e.message || "保存失败，变更未丢弃";
      } finally {
        busy.value = false;
      }
    }
    function openRole(role) {
      roleForm.value = role
        ? { ...role }
        : {
            id: "",
            roleName: "",
            roleCode: "",
            roleType: "business",
            description: "",
            status: "enabled",
          };
      const auth = role
        ? catalog.value.roleAuthorizations.find((a) => a.role.id === role.id)
        : null;
      memberDraft.value = [...(auth?.userIds || [])];
      serviceDraft.value = [...(auth?.agentIds || [])];
      scopeDraft.value = (auth?.orgScopes || []).map(({ scopeType, orgId }) => ({
        scopeType,
        orgId,
      }));
      formError.value = "";
    }
    function closeRole() {
      if (!busy.value) roleForm.value = null;
    }
    async function saveRole() {
      busy.value = true;
      formError.value = "";
      try {
        const payload = { ...roleForm.value, tenantId: catalog.value.tenantId };
        const saved = payload.id
          ? await updateRole(payload.id, payload)
          : await createRole(payload);
        const id = saved.id;
        roleForm.value.id = id;
        if (canAuthorizeRoles) {
          const latest = await fetchRoleAuthorization(id);
          await saveRoleAuthorization(id, {
            permissionIds: latest.permissionIds,
            userIds: memberDraft.value,
            agentIds: serviceDraft.value,
            orgScopes: scopeDraft.value.map((s) => ({
              scopeType: s.scopeType,
              orgId: s.scopeType === "all" ? null : s.orgId,
            })),
          });
        }
        roleId.value = id;
        roleForm.value = null;
        await loadCatalog();
        syncSelection();
        notice.value = "角色与成员已保存";
        failed.value = false;
      } catch (e) {
        formError.value = e.message || "保存失败，请检查角色信息";
      } finally {
        busy.value = false;
      }
    }
    async function removeRole() {
      if (
        !window.confirm(
          `确认删除“${selectedRole.value.roleName}”？角色成员将失去该角色授予的权限。`
        )
      )
        return;
      busy.value = true;
      try {
        await deleteRole(roleId.value);
        roleId.value = "";
        await loadCatalog();
        syncSelection();
        notice.value = "角色已删除";
        failed.value = false;
      } catch (e) {
        notice.value = e.message;
        failed.value = true;
      } finally {
        busy.value = false;
      }
    }
    watch([masterQuery, resourceGroup], () => (masterPageNumber.value = 1));
    watch([roleGroup, rowQuery, stateFilter, pageSize], () => (rowPage.value = 1));
    watch(
      () => props.mode,
      () => {
        masterQuery.value = "";
        resourceKey.value = "";
        if (props.mode === "resources") resourceKey.value = resources.value[0]?.key || "";
      }
    );
    const preventUnload = (e) => {
      if (permissionDirty.value) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    onMounted(() => {
      load();
      window.addEventListener("beforeunload", preventUnload);
    });
    onActivated(() => {
      if (ready.value) reloadAfterChange();
    });
    onBeforeUnmount(() => {
      serial++;
      window.removeEventListener("beforeunload", preventUnload);
    });
    return {
      resourceGroups,
      catalog,
      extras,
      ready,
      busy,
      notice,
      failed,
      roleId,
      resourceKey,
      masterQuery,
      rowQuery,
      resourceGroup,
      roleGroup,
      stateFilter,
      masterPageNumber,
      rowPage,
      pageSize,
      originalPermissions,
      permissionDraft,
      roleForm,
      formError,
      memberDraft,
      serviceDraft,
      scopeDraft,
      orgs,
      canManageRoles,
      bulkSelected,
      canEditResource,
      allResourceRowsSelected,
      selectResourceRows,
      allowSelectedResources,
      canAuthorizeRoles,
      canEdit,
      selectedRole,
      selectedAuthorization,
      selectedResource,
      masterItems,
      masterPage,
      masterPages,
      roleRows,
      visibleRoleRows,
      rowPages,
      permissionDirty,
      allVisibleSelected,
      groupLabel,
      stateFor,
      chooseMaster,
      togglePermission,
      selectVisible,
      load,
      savePermissions,
      reloadAfterChange,
      openRole,
      closeRole,
      saveRole,
      removeRole,
    };
  },
};
