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

const defaultReasoningLevels = {low:'low',medium:'medium',xhigh:'xhigh'};
const standardWireLevels = ['none','minimal','low','medium','high','xhigh','max','no_think'];
const reasoningFormats = [
  {id:'standard',label:'OpenAI / 兼容接口',levels:defaultReasoningLevels,values:standardWireLevels},
  {id:'deepseek-thinking',label:'DeepSeek V4',levels:{low:'low',medium:'high',xhigh:'max'},values:['low','high','max']},
  {id:'qwen-thinking',label:'Qwen',levels:defaultReasoningLevels,values:standardWireLevels.filter(level=>level!=='no_think')},
  {id:'hunyuan-template',label:'腾讯混元 Hy3',levels:{low:'low',medium:'high'},values:['no_think','low','high']},
];
const reasoningLevels = [
  {value:'low',label:'低 (low)'},{value:'medium',label:'中 (medium)'},{value:'xhigh',label:'极高 (xhigh)'},
  {value:'minimal',label:'极简 (minimal)'},{value:'high',label:'高 (high)'},
];
const defaultReasoning = () => ({enabled:true,parameter:'standard',levels:{...defaultReasoningLevels}});
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
  contextWindowTokens: null,
  maxRetries: 3,
  apiKey: "",
  enabled: true,
  defaultModel: false,
  internetSearchEnabled: false,
  searchProviderId: "",
  searchProviderIds: [],
  codingReasoning: defaultReasoning(),
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
    reasoningFormats,
    reasoningLevels,
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
    requestTimeoutMinutes: {
      get() {
        const seconds = this.form.requestTimeoutSeconds;
        return seconds === -1 || seconds === "" ? seconds : seconds / 60;
      },
      set(minutes) {
        this.form.requestTimeoutSeconds = minutes === -1 || minutes === "" ? minutes : minutes * 60;
      },
    },
    reasoningSupported() {
      if (this.form.type !== 'chat') return false;
      const protocol = (this.form.protocol || 'auto').trim().toLowerCase().replaceAll('_', '-');
      if (protocol === 'openai' || protocol === 'openai-compatible') return true;
      if (protocol !== 'auto') return false;
      if (!this.form.baseUrl) return true;
      try {
        const path = new URL(this.form.baseUrl).pathname.toLowerCase().replace(/\/+$/, '');
        return !/\/(?:multimodal|text)-generation\/generation$/.test(path);
      } catch {
        return false;
      }
    },
    reasoningWireValues(){
      return reasoningFormats.find(format=>format.id===this.form.codingReasoning?.parameter)?.values || [];
    },
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
    setReasoningEnabled(enabled) {
      if(!enabled) { this.form.codingReasoning={enabled:false}; return; }
      this.applyReasoningFormat('standard');
    },
    applyReasoningFormat(id) {
      const format=reasoningFormats.find(option=>option.id===id);
      this.form.codingReasoning={enabled:true,parameter:id,levels:{...format.levels}};
    },
    openEdit(model) {
      this.form = { ...emptyForm(), ...model, requestTimeoutSeconds: model.requestTimeoutSeconds ?? 300, apiKey: "" };
      this.form.codingReasoning = model.codingReasoning
        ? {...model.codingReasoning, levels:{...model.codingReasoning.levels}}
        : defaultReasoning();
      this.form.searchProviderIds = [...(model.searchProviderIds ?? (model.searchProviderId ? [model.searchProviderId] : []))];
      this.isNew = false;
      this.editing = true;
    },
    async save() {
      if(this.form.type==='chat'&&this.form.internetSearchEnabled&&(!this.form.searchProviderIds.length||this.unavailableSearchProviders.length)){
        this.message="请选择至少一个已配置并启用的检索引擎";this.error=true;return;
      }
      const seconds = this.form.requestTimeoutSeconds;
      if (!(this.form.type === "chat" && seconds === -1)
          && (!Number.isInteger(seconds) || seconds < 1 || seconds > 1200)) {
        this.message = this.form.type === "chat" ? "请输入 20 分钟以内的正数，或输入 -1"
          : "请输入 20 分钟以内的正数";
        this.error = true;
        return;
      }
      let codingReasoning = this.reasoningSupported ? this.form.codingReasoning : null;
      if(codingReasoning?.enabled){
        const levels=Object.fromEntries(Object.entries(codingReasoning.levels).filter(([,value])=>value));
        if(!Object.keys(levels).length){
          this.message="请至少配置一个可用的思考强度档位";
          this.error=true;
          return;
        }
        codingReasoning={...codingReasoning,levels};
      }
      this.saving = true;
      try {
        await savePlatformModel({...this.form,contextWindowTokens:this.form.contextWindowTokens || null,codingReasoning,searchProviderId:this.form.searchProviderIds[0]||""});
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
