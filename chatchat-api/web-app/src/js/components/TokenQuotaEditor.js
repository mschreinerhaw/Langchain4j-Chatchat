import { X } from "@lucide/vue";
import { computed, ref, useId } from "vue";
import { apiRequest } from "../../services/api.js";

export default {
  name: "TokenQuotaEditor",
  components: { X },
  props: {
    type: { type: String, required: true },
    principal: { type: String, required: true },
  },
  setup(props) {
    const personal = computed(() => props.type === "USER");
    const headingId = useId();
    const container = ref(null);
    const trigger = ref(null);
    const path = computed(() => `/permission-center/token-pools/${props.type}/${encodeURIComponent(props.principal)}`);
    const pool = ref(null);
    const unlimited = ref(true);
    const limit = ref("");
    const history = ref(null);
    const page = ref(1);
    const busy = ref(false);
    const error = ref("");
    const notice = ref("");
    const confirmReset = ref(false);
    const remaining = computed(() => Math.max(0, pool.value.limit - pool.value.spent - pool.value.reserved));
    const percentage = computed(() => pool.value.limit === 0
      ? (pool.value.spent > 0 ? "100%" : "0%")
      : new Intl.NumberFormat("zh-CN", { style: "percent", maximumFractionDigits: 1 })
          .format(pool.value.spent / pool.value.limit));

    function setPool(value) {
      pool.value = value;
      unlimited.value = value.limit == null;
      limit.value = value.limit == null ? "" : String(value.limit);
    }

    async function run(op) {
      busy.value = true;
      error.value = "";
      try {
        await op();
      } catch (failure) {
        error.value = failure.message;
      } finally {
        busy.value = false;
      }
    }

    async function loadHistory(nextPage) {
      history.value = await apiRequest(`${path.value}/changes?page=${nextPage}`);
      page.value = nextPage;
    }

    function loadQuota() {
      return run(async () => {
        setPool(await apiRequest(path.value));
        await loadHistory(1);
      });
    }

    function openRole(event) {
      if (!personal.value && event.target.open && !busy.value && !history.value) return loadQuota();
    }

    function openDialog() {
      container.value.showModal();
      pool.value = history.value = null;
      notice.value = "";
      confirmReset.value = false;
      return loadQuota();
    }

    function closeDialog() {
      if (busy.value) return;
      container.value.close();
      trigger.value?.focus();
    }

    function save(reset) {
      notice.value = "";
      const amount = reset ? pool.value.limit : (unlimited.value ? null : Number(limit.value));
      if (!reset && !unlimited.value && (!/^\d+$/.test(limit.value) || !Number.isSafeInteger(amount))) {
        error.value = "请输入有效的非负整数 Token 额度。";
        return;
      }
      return run(async () => {
        setPool(await apiRequest(path.value, {
          method: "PUT",
          body: JSON.stringify({ unlimited: amount === null, limit: amount, reset }),
        }));
        confirmReset.value = false;
        notice.value = reset ? "已开启新的额度周期。" : "Token 额度已保存。";
        await loadHistory(1);
      });
    }

    const displayTime = (value) => value ? new Date(value).toLocaleString("zh-CN") : "—";
    function displayChange(detail) {
      const match = /^limit=(null|\d+),period=(\d+)$/.exec(detail || "");
      if (!match) return "";
      const amount = match[1] === "null" ? "不限额" : `${match[1]} Token`;
      return ` · 周期 ${match[2]} · ${amount}`;
    }
    return { personal, headingId, container, trigger, pool, unlimited, limit, history, page, busy,
      error, notice, confirmReset, remaining, percentage, openRole, openDialog, closeDialog,
      loadQuota, save, loadHistory, run, displayTime, displayChange };
  },
};
