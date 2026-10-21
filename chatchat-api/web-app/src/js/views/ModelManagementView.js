import {
  Cpu,
  Database,
  KeyRound,
  MessageSquare,
  Pencil,
  Plus,
  Search,
  Star,
  Trash2,
  X,
} from "@lucide/vue";
import AppPagination from "../../components/AppPagination.vue";
import AppConfirmDialog from "../../components/AppConfirmDialog.vue";
import {
  deletePlatformModel,
  fetchPlatformModels,
  publishPlatformModel,
  savePlatformModel,
  setDefaultPlatformModel,
} from "../../services/api";
import "../../styles/pages/model-management.css";

const emptyForm = () => ({
  name: "",
  alias: "",
  description: "",
  type: "chat",
  providerModel: "",
  baseUrl: "",
  protocol: "auto",
  dimension: 1024,
  timeout: 30000,
  maxTokens: -1,
  maxRetries: 3,
  apiKey: "",
  enabled: true,
  defaultModel: false,
});

export default {
  name: "ModelManagementView",
  components: {
    AppPagination,
    AppConfirmDialog,
    Cpu,
    Database,
    KeyRound,
    MessageSquare,
    Pencil,
    Plus,
    Search,
    Star,
    Trash2,
    X,
  },
  data: () => ({
    models: [],
    loading: false,
    saving: false,
    editing: false,
    isNew: true,
    form: emptyForm(),
    message: "",
    error: false,
    publishingKey: "",
    pendingDelete: null,
    searchQuery: "",
    typeFilter: "",
    page: 1,
    pageSize: 9,
  }),
  computed: {
    filteredModels() {
      const query = this.searchQuery.toLocaleLowerCase();
      return this.models.filter((model) => {
        if (this.typeFilter && model.type !== this.typeFilter) return false;
        if (!query) return true;
        return [
          model.alias,
          model.name,
          model.description,
          model.providerModel,
          model.baseUrl,
        ].some((value) =>
          String(value || "")
            .toLocaleLowerCase()
            .includes(query)
        );
      });
    },
    pageCount() {
      return Math.max(1, Math.ceil(this.filteredModels.length / this.pageSize));
    },
    pagedModels() {
      const start = (this.page - 1) * this.pageSize;
      return this.filteredModels.slice(start, start + this.pageSize);
    },
  },
  watch: {
    searchQuery() {
      this.page = 1;
    },
    typeFilter() {
      this.page = 1;
    },
    filteredModels() {
      this.page = Math.min(this.page, this.pageCount);
    },
  },
  mounted() {
    this.load();
  },
  methods: {
    onEnabledChange() {
      if (!this.form.enabled) this.form.defaultModel = false;
    },
    clearFilters() {
      this.searchQuery = "";
      this.typeFilter = "";
      this.page = 1;
    },
    async load() {
      this.loading = true;
      try {
        this.models = await fetchPlatformModels();
        this.error = false;
      } catch (error) {
        this.message = error.message;
        this.error = true;
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.form = emptyForm();
      this.isNew = true;
      this.editing = true;
    },
    openEdit(model) {
      this.form = { ...model, apiKey: "" };
      this.isNew = false;
      this.editing = true;
    },
    async save() {
      this.saving = true;
      try {
        await savePlatformModel(this.form);
        this.form.apiKey = "";
        this.editing = false;
        this.message = "模型草稿已保存，请点击发布后使用";
        this.error = false;
        await this.load();
      } catch (error) {
        this.message = error.message;
        this.error = true;
      } finally {
        this.saving = false;
      }
    },
    async makeDefault(model) {
      try {
        await setDefaultPlatformModel(model.type, model.name);
        this.message = "默认模型已设为待发布，请点击发布后生效";
        this.error = false;
        await this.load();
      } catch (error) {
        this.message = error.message;
        this.error = true;
      }
    },
    async publish(model) {
      this.publishingKey = `${model.type}:${model.name}`;
      try {
        await publishPlatformModel(model.type, model.name);
        this.message = "模型已发布并生效";
        this.error = false;
        await this.load();
      } catch (error) {
        this.message = error.message;
        this.error = true;
      } finally {
        this.publishingKey = "";
      }
    },
    remove(model) {
      this.pendingDelete = model;
    },
    async confirmDelete() {
      const model = this.pendingDelete;
      if (!model) return;
      this.pendingDelete = null;
      try {
        await deletePlatformModel(model.type, model.name);
        this.message = "模型已删除";
        this.error = false;
        await this.load();
      } catch (error) {
        this.message = error.message;
        this.error = true;
      }
    },
  },
};
