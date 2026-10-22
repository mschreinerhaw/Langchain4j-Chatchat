import { authApi } from "../../services/api";
import { ElMessageBox } from "element-plus";
import "../../styles/admin-users.css";
export default {
  name: "AdminUsersView",
  emits: ["notify", "error"],
  data: () => ({
    users: [],
    menus: [],
    selected: null,
    keyword: "",
    status: "",
    page: 1,
    total: 0,
    loading: false,
    saving: false,
    checked: [],
    enabled: true,
    password: "",
    menuKeyword: "",
    createOpen: false,
    newUsername: "",
    newPassword: "",
    serial: 0,
  }),
  computed: {
    filteredMenus() {
      const query = this.menuKeyword.trim().toLowerCase();
      return this.menus.filter(
        (row) => !query || (row.label + " " + row.description).toLowerCase().includes(query)
      );
    },
  },
  mounted() {
    this.load();
  },
  methods: {
    async load(preferred) {
      const serial = ++this.serial;
      this.loading = true;
      try {
        const [result, menus] = await Promise.all([
          authApi.users({
            keyword: this.keyword,
            status: this.status,
            page: this.page,
            pageSize: 20,
          }),
          authApi.menuCatalog(),
        ]);
        if (serial !== this.serial) return;
        this.users = result.items;
        this.total = result.total;
        this.menus = menus;
        this.select(
          this.users.find(
            (u) =>
              u.username === (typeof preferred === "string" ? preferred : this.selected?.username)
          ) ||
            this.users[0] ||
            null
        );
      } catch (error) {
        this.$emit("error", error);
      } finally {
        if (serial === this.serial) this.loading = false;
      }
    },
    select(user) {
      this.selected = user;
      this.enabled = user?.enabled ?? true;
      this.checked = [...(user?.menuKeys || [])];
      this.password = "";
      this.menuKeyword = "";
    },
    search() {
      this.page = 1;
      this.load();
    },
    changePage(value) {
      this.page = value;
      this.load();
    },
    inherited(row) {
      return row.parentKey && this.checked.includes(row.parentKey);
    },
    toggle(key, value) {
      this.checked = value
        ? [...new Set([...this.checked, key])]
        : this.checked.filter((item) => item !== key);
    },
    openCreate() {
      this.newUsername = "";
      this.newPassword = "";
      this.createOpen = true;
    },
    async create() {
      if (this.saving) return;
      this.saving = true;
      try {
        const user = await authApi.saveUser("", {
          username: this.newUsername,
          password: this.newPassword,
          enabled: true,
          menuKeys: [],
        });
        this.createOpen = false;
        this.keyword = user.username;
        this.status = "";
        this.page = 1;
        await this.load(user.username);
        this.$emit("notify", { title: "用户已创建", message: "请为该用户配置功能菜单权限。" });
      } catch (error) {
        this.$emit("error", error);
      } finally {
        this.saving = false;
      }
    },
    async save() {
      if (this.saving || !this.selected || this.selected.superAdmin) return;
      const username = this.selected.username;
      if (!this.enabled || this.password) {
        try {
          await ElMessageBox.confirm(
            "停用或重置密码将使 " + username + " 的现有会话失效，是否继续？",
            "确认修改",
            { type: "warning", confirmButtonText: "继续保存", cancelButtonText: "取消" }
          );
        } catch {
          return;
        }
      }
      this.saving = true;
      try {
        await authApi.saveUser(username, {
          enabled: this.enabled,
          password: this.password,
          menuKeys: this.checked,
        });
        await this.load(username);
        this.$emit("notify", { title: "用户与菜单授权已保存" });
      } catch (error) {
        this.$emit("error", error);
      } finally {
        this.saving = false;
      }
    },
  },
};
