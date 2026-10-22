import { saveResourceAuthorization } from "../utils/permissionWrites.js";
import { computed, ref, watch } from "vue";
import { apiRequest, fetchRoleAuthorization, saveRoleAuthorization } from "../../services/api.js";
import { activeGrant, resourceRules, resourceGroups } from "../utils/permissionWorkspace.js";
export default {
  name: "PermissionResourceEditor",
  components: {},
  props: {
    resource: { type: Object, required: true },
    catalog: { type: Object, required: true },
    services: { type: Array, default: () => [] },
    roleId: { type: String, default: "" },
    canEdit: { type: Boolean, default: false },
  },
  emits: ["changed"],
  setup(props, { emit }) {
    const busy = ref(false),
      notice = ref(""),
      failed = ref(false),
      source = ref("");
    const form = ref({
      targetType: "ROLE",
      targetId: props.roleId,
      effect: "allow",
      scopeExpression: "",
    });
    watch(
      () => props.resource.key,
      () => {
        form.value = {
          targetType: "ROLE",
          targetId: props.roleId,
          effect: "allow",
          scopeExpression: "",
        };
        notice.value = "";
        source.value = "";
      }
    );
    const rules = computed(() => resourceRules(props.resource, props.catalog));
    const filteredRules = computed(() =>
      rules.value.filter((r) => !source.value || r.targetType === source.value)
    );
    const typeName = computed(
      () => resourceGroups.find((g) => g.id === props.resource.group)?.label || "系统资源"
    );
    const subjects = computed(() =>
      form.value.targetType === "ROLE" ? props.catalog.roles : props.catalog.users
    );
    const serviceName = (id) => props.services.find((s) => s.id === id)?.name || "指定查询服务";
    const subjectName = (r) =>
      r.targetType === "ROLE"
        ? props.catalog.roles.find((s) => s.id === r.targetId)?.roleName || "已归档角色"
        : r.targetType === "USER"
          ? props.catalog.users.find((s) => s.id === r.targetId)?.displayName || "已归档用户"
          : "当前租户";
    const scopeName = (r) =>
      r.agentId
        ? "仅限 " + serviceName(r.agentId)
        : r.scopeExpression
          ? "指定数据范围"
          : r.resourceId === "*"
            ? "同类全部资源"
            : "当前租户 · 当前资源";
    const inheritedUsers = computed(() =>
      rules.value
        .filter(
          (r) => r.targetType === "ROLE" && r.effect.toLowerCase() === "allow" && activeGrant(r)
        )
        .flatMap((r) =>
          (
            props.catalog.roleAuthorizations.find((a) => a.role.id === r.targetId)?.userIds || []
          ).map((id) => ({
            key: r.id + id,
            name: props.catalog.users.find((u) => u.id === id)?.displayName || "已归档用户",
            role: subjectName(r),
          }))
        )
    );
    async function perform(op, message) {
      busy.value = true;
      failed.value = false;
      try {
        await op();
        notice.value = message;
        emit("changed");
      } catch (e) {
        failed.value = true;
        notice.value = e.message || "操作失败，请重试";
      } finally {
        busy.value = false;
      }
    }
    async function save() {
      const targetId =
        form.value.targetType === "TENANT" ? props.catalog.tenantId : form.value.targetId;
      if (!targetId) return;
      if (
        (form.value.targetType === "TENANT" || form.value.effect === "deny") &&
        !window.confirm(
          "确认保存此" +
            (form.value.effect === "deny" ? "拒绝" : "租户级") +
            "规则？它将影响该范围内的资源调用。"
        )
      )
        return;
      await perform(async () => {
        await saveResourceAuthorization(props.resource, props.catalog, { ...form.value, targetId });
      }, "授权已保存，目录正在刷新");
    }
    async function revoke(rule) {
      if (
        !window.confirm(
          `确认撤销“${subjectName(rule)}”的这条授权？其他角色或租户规则可能仍然生效。`
        )
      )
        return;
      await perform(async () => {
        if (rule.backend === "permission") {
          const auth = await fetchRoleAuthorization(rule.targetId);
          await saveRoleAuthorization(rule.targetId, {
            ...auth,
            permissionIds: auth.permissionIds.filter((id) => id !== props.resource.id),
            orgScopes: auth.orgScopes.map(({ scopeType, orgId }) => ({ scopeType, orgId })),
          });
        } else
          await apiRequest(
            (rule.backend === "tool"
              ? "/permission-center/tool-grants/"
              : "/enterprise/resource-grants/") + encodeURIComponent(rule.id),
            { method: "DELETE" }
          );
      }, "授权已撤销，目录正在刷新");
    }
    return {
      activeGrant,
      busy,
      notice,
      failed,
      source,
      form,
      rules,
      filteredRules,
      typeName,
      subjects,
      serviceName,
      subjectName,
      scopeName,
      inheritedUsers,
      perform,
      save,
      revoke,
    };
  },
};
