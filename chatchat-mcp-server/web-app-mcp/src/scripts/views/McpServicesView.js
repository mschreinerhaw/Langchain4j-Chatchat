import CrudCatalog from '../../components/CrudCatalog.vue';
import { mcpServicesApi as api } from '../../services/api';

export default {
  name: 'McpServicesView',
  components: { CrudCatalog },
  emits: ['notify', 'error'],
  data() {
    return {
      api,
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
  }
};



