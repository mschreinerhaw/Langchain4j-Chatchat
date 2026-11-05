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
import { apiRequest } from "../../services/api";

const emptyForm = () => ({
  name: "",
  alias: "",
  description: "",
  type: "chat",
  providerModel: "",
  baseUrl: "",
  protocol: "auto",
  dimension: 1024,
  requestTimeoutSeconds: 300,
  maxTokens: -1,
  maxRetries: 3,
  apiKey: "",
  enabled: true,
  defaultModel: false,
  internetSearchEnabled: false,
  searchProviderId: "",
  searchProviderIds: [],
});
const emptyProvider = () => ({id:"",name:"",kind:"TAVILY",endpoint:"",apiKey:"",secretId:"",enabled:true,
  timeoutMillis:10000,requestsPerMinute:30,maxTaskCalls:12,maxDailyCalls:1000});

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
    searchProviders: [],
    providerPage: 1,
    providerPageSize: 3,
    providerManagerOpen: false,
    providerForm: emptyProvider(),
    providerSaving: false,
    providerError: "",
    providerMessage: "",
    pendingProviderDelete: null,
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
    providerPageCount() {
      return Math.max(1, Math.ceil(this.searchProviders.length / this.providerPageSize));
    },
    pagedSearchProviders() {
      const start = (this.providerPage - 1) * this.providerPageSize;
      return this.searchProviders.slice(start, start + this.providerPageSize);
    },
    readySearchProviders() { return this.searchProviders.filter(p=>p.enabled&&p.configured); },
    unavailableSearchProviders() {
      return this.form.searchProviderIds.filter(id=>!this.readySearchProviders.some(p=>p.id===id))
        .map(id=>({id,name:this.searchProviders.find(p=>p.id===id)?.name||id}));
    },
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
    providerPageCount() {
      this.providerPage = Math.min(this.providerPage, this.providerPageCount);
    },
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
        try { this.searchProviders = await apiRequest('/platform/models/search-providers'); }
        catch { this.searchProviders = []; this.providerError="检索引擎列表加载失败，请重试"; }
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
      this.form = { ...emptyForm(), ...model, requestTimeoutSeconds: model.requestTimeoutSeconds ?? 300, apiKey: "" };
      this.form.searchProviderIds = [...(model.searchProviderIds ?? (model.searchProviderId ? [model.searchProviderId] : []))];
      this.isNew = false;
      this.editing = true;
    },
    async save() {
      if(this.form.type==='chat'&&this.form.internetSearchEnabled&&(!this.form.searchProviderIds.length||this.unavailableSearchProviders.length)){
        this.message="请选择至少一个已配置并启用的检索引擎";this.error=true;return;
      }
      if (!Number.isInteger(this.form.requestTimeoutSeconds) || this.form.requestTimeoutSeconds < 1 || this.form.requestTimeoutSeconds > 1200) {
        this.message = "模型请求超时时间必须为 1 至 1200 秒的整数";
        this.error = true;
        return;
      }
      this.saving = true;
      try {
        await savePlatformModel({...this.form,searchProviderId:this.form.searchProviderIds[0]||""});
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
    async openProviderManager() {
      this.providerPage = 1;
      this.providerManagerOpen=true;this.providerError="";this.providerMessage="";this.providerForm=emptyProvider();
      try {this.searchProviders=await apiRequest('/platform/models/search-providers');}
      catch(error){this.providerError=error.message;}
    },
    editProvider(provider) {
      this.providerForm={...emptyProvider(),...provider,apiKey:"",secretId:""};
      this.providerError="";this.providerMessage="";
    },
    newProvider() {this.providerForm=emptyProvider();this.providerError="";this.providerMessage="";},
    closeProviderManager() {this.providerManagerOpen=false;this.providerForm=emptyProvider();this.providerError="";this.providerMessage="";},
    async saveProvider() {
      this.providerSaving=true;this.providerError="";this.providerMessage="";
      const keys=['id','name','kind','endpoint','apiKey','secretId','enabled','timeoutMillis','requestsPerMinute','maxTaskCalls','maxDailyCalls'];
      try {
        const saved=await apiRequest('/platform/models/search-providers',{method:'POST',body:JSON.stringify(Object.fromEntries(keys.map(k=>[k,this.providerForm[k]])))});
        this.providerForm.apiKey="";this.providerForm.secretId="";
        this.searchProviders=await apiRequest('/platform/models/search-providers');
        const savedIndex = this.searchProviders.findIndex(provider => provider.id === saved.id);
        if (savedIndex >= 0) this.providerPage = Math.floor(savedIndex / this.providerPageSize) + 1;
        this.providerForm={...emptyProvider(),...saved,apiKey:"",secretId:""};this.providerMessage="检索引擎已保存";
      }catch(error){this.providerError=error.message;}
      finally{this.providerSaving=false;}
    },
    async confirmProviderDelete() {
      const provider=this.pendingProviderDelete;if(!provider)return;
      this.pendingProviderDelete=null;this.providerError="";this.providerMessage="";this.providerSaving=true;
      try{
        await apiRequest('/platform/models/search-providers/'+encodeURIComponent(provider.id),{method:'DELETE'});
        this.searchProviders=await apiRequest('/platform/models/search-providers');
        if(this.providerForm.id===provider.id)this.providerForm=emptyProvider();
        this.providerMessage="检索引擎已删除";
      }catch(error){this.providerError=error.message;}finally{this.providerSaving=false;}
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
