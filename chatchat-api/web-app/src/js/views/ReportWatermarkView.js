import { computed, onMounted, ref, watch } from "vue";
import { apiRequest } from "../../services/api.js";
import "../../styles/pages/permission-workspace.css";

export default {
  setup() {
    const endpoint = "/permission-center/report-exports";
    const tab = ref("settings");
    const policy = ref(null);
    const roles = ref([]);
    const roleQuery = ref("");
    const rolePage = ref(1);
    const rolesPerPage = 20;
    const filteredRoles = computed(() => {
      const keyword = roleQuery.value.trim().toLowerCase();
      return roles.value.filter((role) => role.name.toLowerCase().includes(keyword));
    });
    const rolePageCount = computed(() => Math.max(1, Math.ceil(filteredRoles.value.length / rolesPerPage)));
    const visibleRoles = computed(() => {
      const offset = (rolePage.value - 1) * rolesPerPage;
      return filteredRoles.value.slice(offset, offset + rolesPerPage);
    });
    watch(roleQuery, () => { rolePage.value = 1; });
    watch(rolePageCount, (count) => { rolePage.value = Math.min(rolePage.value, count); });
    const audit = ref({ items: [], total: 0, pages: 0 });
    const page = ref(1);
    const busy = ref(false);
    const error = ref("");
    const success = ref("");

    async function request(operation) {
      busy.value = true;
      error.value = success.value = "";
      try { await operation(); }
      catch (failure) { error.value = failure.message; }
      finally { busy.value = false; }
    }

    function loadSettings() {
      return request(async () => {
        const result = await apiRequest(endpoint);
        policy.value = result.policy;
        roles.value = result.roles;
      });
    }

    function save() {
      return request(async () => {
        policy.value = await apiRequest(endpoint, { method: "PUT", body: JSON.stringify(policy.value) });
        success.value = "报告导出设置已保存";
      });
    }

    function loadAudit(next) {
      return request(async () => {
        audit.value = await apiRequest(`${endpoint}/audits?page=${next}`);
        page.value = next;
      });
    }

    function select(next) {
      tab.value = next;
      if (next === "audit") return loadAudit(1);
      return loadSettings();
    }

    onMounted(loadSettings);
    return {
      tab, policy, roles, roleQuery, rolePage, rolePageCount, filteredRoles, visibleRoles,
      audit, page, busy, error, success, save, loadAudit, select,
    };
  }
};
