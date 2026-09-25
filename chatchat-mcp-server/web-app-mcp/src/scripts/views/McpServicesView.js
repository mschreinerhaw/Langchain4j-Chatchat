import CrudCatalog from '../../components/CrudCatalog.vue';
import { externalMcpServicesApi as externalApi, mcpServicesApi as api } from '../../services/api';

export default {
  name: 'McpServicesView',
  components: { CrudCatalog },
  emits: ['notify', 'error'],
  data() {
    return {
      api,
      externalApi,
      activeTab: 'external',
      parents: [],
      workflows: [],
      templatesOpen: false,
      selectedService: null,
      selectedTemplate: null,
      invokeArguments: '{}',
      invokeResult: '',
      invoking: false,
      externalDefaults: { workflowId: 'mcp_streamable_http' },
      externalColumns: [
        { key: 'name', label: '服务名称' },
        { key: 'endpoint', label: 'MCP 端点' },
        { key: 'parentToolName', label: '父类模板' },
        { key: 'workflowId', label: '执行工作流' },
        { key: 'discoveredAt', label: '发现时间' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value ? '启用' : '待审核 / 停用' }
      ],
      defaults: { enabled: true, serviceType: 'REMOTE', environment: 'DEV', routingLabels: {}, capabilities: {} },
      columns: [
        { key: 'name', label: '服务名称' },
        { key: 'endpoint', label: '端点' },
        { key: 'serviceType', label: '类型' },
        { key: 'environment', label: '环境' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      formFields: [
        {
          key: 'name',
          label: '服务名称',
          required: true,
          placeholder: '如 chatchat-api-prod',
          help: '用于识别调用方或上游 MCP 客户端，建议使用英文、数字、短横线或下划线。',
          section: 'basic',
          sectionTitle: '基础信息',
          sectionSubtitle: '定义服务身份、访问端点和鉴权信息。'
        },
        {
          key: 'endpoint',
          label: '服务端点',
          required: true,
          span: 'col-12',
          placeholder: '如 https://api.example.com/mcp 或 http://127.0.0.1:5178/mcp',
          help: '填写该服务访问 MCP 的入口地址，必须是完整 HTTP/HTTPS 地址。',
          section: 'basic'
        },
        {
          key: 'serviceToken',
          label: '服务 Token',
          type: 'password',
          span: 'col-12',
          placeholder: '可留空；需要固定鉴权时填写服务侧 Token',
          help: '用于服务间鉴权；编辑时留空表示不修改已有 Token。',
          section: 'basic'
        },
        {
          key: 'serviceType',
          label: '服务类型',
          type: 'select',
          required: true,
          options: ['REMOTE', 'LOCAL', 'GATEWAY'].map(v => ({ value: v, label: v })),
          help: 'REMOTE 表示远端服务，LOCAL 表示本地服务，GATEWAY 表示网关代理服务。',
          section: 'runtime',
          sectionTitle: '运行策略',
          sectionSubtitle: '设置服务类型、环境、权限组和发布状态。'
        },
        {
          key: 'environment',
          label: '环境',
          type: 'select',
          required: true,
          options: ['DEV', 'TEST', 'PROD'].map(v => ({ value: v, label: v })),
          help: '用于区分开发、测试和生产服务，路由和授权时会参考该环境。',
          section: 'runtime'
        },
        {
          key: 'permissionGroup',
          label: '权限组',
          placeholder: '如 default、ops、tenant-a',
          help: '用于按权限组管理服务访问范围；不需要分组时可留空。',
          section: 'runtime'
        },
        {
          key: 'enabled',
          label: '启用状态',
          type: 'select',
          required: true,
          options: [{ value: true, label: '启用' }, { value: false, label: '停用' }],
          help: '停用后该服务注册信息保留，但不允许作为可用服务访问。',
          section: 'runtime'
        },
        {
          key: 'routingLabels',
          label: '路由标签',
          type: 'jsonObject',
          defaultValue: {},
          span: 'col-md-6',
          keyPlaceholder: '标签名，如 region',
          valuePlaceholder: '标签值，如 cn',
          help: '按键值对维护路由标签，例如 region=cn、env=prod。',
          section: 'labels',
          sectionTitle: '路由与能力',
          sectionSubtitle: '用可视化键值对维护路由标签和能力描述。'
        },
        {
          key: 'capabilities',
          label: '能力描述',
          type: 'jsonObject',
          defaultValue: {},
          span: 'col-md-6',
          keyPlaceholder: '能力名，如 sql',
          valuePlaceholder: '能力值，如 true',
          help: '按键值对维护能力描述，例如 sql=true、ops=ssh。',
          section: 'labels'
        }
      ]
    };
  },
  computed: {
    externalFormFields() {
      return [
        { key: 'name', label: '服务名称', required: true, section: 'basic', sectionTitle: '外部服务',
          sectionSubtitle: '登记可访问的远端 MCP 端点。' },
        { key: 'endpoint', label: 'MCP 端点', required: true, span: 'col-12', section: 'basic',
          placeholder: 'https://example.com/mcp' },
        { key: 'authorization', label: 'Authorization 请求头', type: 'password', span: 'col-12',
          section: 'basic', placeholder: 'Bearer ...（编辑留空表示不修改）',
          help: '凭证仅用于出站连接，列表与编辑接口不会回显。' },
        { key: 'parentToolName', label: '归属父类模板', type: 'select', required: true,
          section: 'binding', sectionTitle: '模板归属与执行',
          sectionSubtitle: 'API、数据库、HTTP 使用同一注册流程；新增工作流由后端插件扩展。',
          options: this.parents.map(item => ({ value: item.toolName, label: `${item.title} (${item.assetType})` })) },
        { key: 'workflowId', label: '执行工作流', type: 'select', required: true, section: 'binding',
          options: this.workflows.map(value => ({ value, label: value })) }
      ];
    },
    externalActions() {
      return [
        { key: 'discover', label: '发现工具', run: row => this.externalApi.discover(row.id),
          successMessage: '已发现工具，请审核后启用服务' },
        { key: 'templates', label: '模板', run: row => { this.openTemplates(row); },
          successMessage: '已打开工具模板' }
      ];
    }
  },
  async mounted() {
    try {
      [this.parents, this.workflows] = await Promise.all([this.externalApi.parents(), this.externalApi.workflows()]);
    } catch (error) { this.$emit('error', error); }
  },
  methods: {
    parentTitle(name) { return this.parents.find(item => item.toolName === name)?.title || name || '未选择'; },
    openTemplates(row) {
      this.selectedService = row;
      this.selectedTemplate = null;
      this.invokeResult = '';
      this.templatesOpen = true;
    },
    syncSelectedService(items) {
      if (this.selectedService) this.selectedService = items.find(item => item.id === this.selectedService.id) || null;
    },
    selectTemplate(template) {
      this.selectedTemplate = template;
      this.invokeArguments = '{}';
      this.invokeResult = '';
    },
    async invokeTemplate() {
      try {
        const args = JSON.parse(this.invokeArguments || '{}');
        if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('参数必须是 JSON 对象');
        this.invoking = true;
        const result = await this.externalApi.invoke(this.selectedService.id, this.selectedTemplate.name, args);
        this.invokeResult = JSON.stringify(result, null, 2);
      } catch (error) { this.$emit('error', error); }
      finally { this.invoking = false; }
    }
  }
};



