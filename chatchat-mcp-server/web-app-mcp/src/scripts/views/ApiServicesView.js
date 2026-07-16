import CrudCatalog from '../../components/CrudCatalog.vue';
import { apiServicesApi as api, assetsApi } from '../../services/api';

const objectSchema = {
  type: 'object',
  properties: {},
  required: [],
  additionalProperties: false
};

export default {
  name: 'ApiServicesView',
  components: { CrudCatalog },
  emits: ['notify', 'error', 'result'],
  data() {
    return {
      api,
      busy: false,
      activeTab: 'services',
      gatewayAssets: [],
      livedataApis: [],
      livedataKeyword: '',
      selectedLivedata: new Set(),
      overwriteExisting: false,
      defaults: {
        enabled: true,
        cacheEnabled: false,
        cacheTtlSeconds: 300,
        inputSchema: objectSchema,
        outputSchema: objectSchema,
        capabilitySpec: {},
        dependencySpec: {},
        governance: {}
      },
      searchableFields: ['toolName', 'title', 'description', 'businessGroup', 'businessGroupName', 'urlTemplate'],
      columns: [
        { key: 'toolName', label: '工具名称', type: 'code' },
        { key: 'title', label: '显示名称' },
        { key: 'businessGroupName', label: '分组' },
        { key: 'gatewayId', label: '网关资产', formatter: value => value || '-' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ]
    };
  },
  computed: {
    formFields() {
      return [
        {
          key: 'toolName',
          label: '工具名称',
          required: true,
          placeholder: '如 weather_api',
          help: 'API 服务工具名沿用 <业务能力>_api 命名，例如 weather_api、customer_profile_api；只使用小写字母、数字和下划线，保存后避免随意修改。',
          section: 'basic',
          sectionTitle: '基础信息',
          sectionSubtitle: '定义 API 服务发布成 MCP 工具后的名称和展示信息。'
        },
        {
          key: 'title',
          label: '显示名称',
          required: true,
          placeholder: '如 查询客户画像',
          help: '面向业务用户展示的名称，尽量说明接口能查询或执行的业务能力。',
          section: 'basic'
        },
        {
          key: 'gatewayId',
          label: 'API 网关资产',
          type: 'select',
          required: true,
          span: 'col-12',
          options: this.gatewayOptions,
          placeholder: '选择已维护并测试通过的 API 网关资产',
          help: 'HTTP 方法、URL、Header 和 Body 模板在网关资产中维护；这里只关联已有网关资产。',
          section: 'basic'
        },
        {
          key: 'description',
          label: '工具描述',
          type: 'textarea',
          span: 'col-12',
          placeholder: '说明接口用途、输入参数含义、返回数据范围和适用场景。',
          help: '描述会参与工具检索，建议包含业务对象、关键词和调用限制。',
          section: 'basic'
        },
        {
          key: 'inputSchema',
          label: '请求参数',
          type: 'jsonSchema',
          defaultValue: objectSchema,
          span: 'col-12',
          namePlaceholder: '如 customerId',
          descriptionPlaceholder: '说明参数格式、来源或枚举范围',
          emptyText: '暂无请求参数。需要模型传参时点击“新增参数”。',
          help: '配置模型调用该 API 服务时需要传入的业务参数；参数名应和网关资产的 URL/Body 占位符保持一致。',
          section: 'request',
          sectionTitle: '请求参数',
          sectionSubtitle: '维护模型需要传入的业务参数，保存时自动生成 JSON Schema。'
        },
        {
          key: 'businessGroup',
          label: '业务分组编码',
          placeholder: '如 customer、order、risk',
          help: '用于工具检索和授权分类，建议使用稳定的英文编码。',
          section: 'group',
          sectionTitle: '业务分类',
          sectionSubtitle: '配置业务域信息，帮助检索和角色授权分类。'
        },
        {
          key: 'outputSchema',
          label: '返回字段',
          type: 'jsonSchema',
          defaultValue: objectSchema,
          span: 'col-12',
          namePlaceholder: '如 customerId、riskLevel',
          descriptionPlaceholder: '说明返回字段的业务含义和单位',
          emptyText: '暂无返回字段。需求覆盖分析需要返回字段时点击“新增参数”。',
          help: '用于判断 API 是否能提供需求需要的数据，不会作为请求参数发送。',
          section: 'capability',
          sectionTitle: '能力与结果',
          sectionSubtitle: '以可视化方式描述 API 能做什么、返回什么以及依赖哪些前置能力。'
        },
        {
          key: 'capabilitySpec',
          label: '能力说明',
          type: 'jsonObject',
          span: 'col-12',
          keyPlaceholder: '如 businessObject、operation、intentAliases',
          valuePlaceholder: '如 客户、查询画像、客户信息/客户画像',
          emptyText: '暂无能力标签，点击“新增键值”配置。',
          help: '建议维护业务对象、操作、适用场景和意图别名；这些内容参与 API 能力检索。',
          section: 'capability'
        },
        {
          key: 'dependencySpec',
          label: '依赖说明',
          type: 'jsonObject',
          span: 'col-12',
          keyPlaceholder: '如 dependsOn、preconditions、parameterSources',
          valuePlaceholder: '如 customer_profile_api 或 需先取得 customerId',
          emptyText: '无前置依赖时可以留空。',
          help: '描述前置 API、调用条件和参数来源，用于生成多 API 执行 DAG。',
          section: 'capability'
        },
        {
          key: 'businessGroupName',
          label: '业务分组名称',
          placeholder: '如 客户中心、订单分析',
          help: '面向管理员展示的业务分组名称。',
          section: 'group'
        },
        {
          key: 'businessGroupDescription',
          label: '业务分组描述',
          type: 'textarea',
          span: 'col-12',
          placeholder: '描述该业务域包含的接口能力，以及模型应在什么场景下选择。',
          section: 'group'
        },
        {
          key: 'enabled',
          label: '启用',
          type: 'select',
          required: true,
          options: boolOptions(),
          help: '停用后不会作为 MCP 工具发布。',
          section: 'runtime',
          sectionTitle: '运行策略',
          sectionSubtitle: '控制发布状态和查询缓存策略。'
        },
        {
          key: 'cacheEnabled',
          label: '查询缓存',
          type: 'select',
          required: true,
          options: boolOptions(),
          help: '适合只读查询接口；会按请求参数缓存结果。',
          section: 'runtime'
        },
        {
          key: 'cacheTtlSeconds',
          label: '缓存有效期（秒）',
          type: 'number',
          required: form => form.cacheEnabled === true,
          min: 1,
          step: 1,
          placeholder: '300',
          help: '缓存开启时生效，单位秒。',
          section: 'runtime'
        }
      ];
    },
    gatewayOptions() {
      const options = [];
      this.gatewayAssets.forEach(asset => {
        options.push({
          value: asset.id,
          label: [asset.name || asset.title || asset.toolName || asset.id, asset.environment, asset.method].filter(Boolean).join(' / ')
        });
      });
      return options;
    },
    filteredLivedata() {
      const keyword = this.livedataKeyword.toLowerCase();
      if (!keyword) return this.livedataApis;
      return this.livedataApis.filter(item => ['name', 'title', 'toolName', 'serviceName', 'namespace'].some(key => String(item[key] || '').toLowerCase().includes(keyword)));
    }
  },
  mounted() {
    this.loadGatewayAssets();
  },
  methods: {
    async loadGatewayAssets() {
      try {
        this.gatewayAssets = await assetsApi.listHttp() || [];
      } catch (error) {
        this.$emit('error', error);
      }
    },
    async testService(item, args) {
      return api.test(item.id, args);
    },
    async loadLivedata() {
      this.busy = true;
      try {
        this.livedataApis = await api.listLivedata() || [];
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    toggleLivedata(id) {
      const next = new Set(this.selectedLivedata);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      this.selectedLivedata = next;
    },
    async registerSelected() {
      this.busy = true;
      try {
        await api.registerLivedata([...this.selectedLivedata], this.overwriteExisting);
        this.$emit('notify', { title: '注册成功', message: `已提交 ${this.selectedLivedata.size} 个 API` });
        this.selectedLivedata.clear();
        await this.$refs.catalog.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    }
  }
};

function boolOptions() {
  return [{ value: true, label: '启用' }, { value: false, label: '停用' }];
}
