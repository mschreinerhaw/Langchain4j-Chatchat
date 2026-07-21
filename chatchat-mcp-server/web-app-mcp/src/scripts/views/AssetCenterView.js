import CrudCatalog from '../../components/CrudCatalog.vue';
import ModalPanel from '../../components/ModalPanel.vue';
import { assetsApi as api, newsApi } from '../../services/api';
import { prettyJson } from '../../utils/json';

const objectSchemaText = JSON.stringify({
  type: 'object',
  properties: {},
  required: [],
  additionalProperties: true
}, null, 2);

const livedataDynamicSessionBody = JSON.stringify({
  sessionId: '{{__livedata_session_id}}',
  data: {}
}, null, 2);

export default {
  name: 'AssetCenterView',
  components: { CrudCatalog, ModalPanel },
  emits: ['notify', 'error', 'result'],
  data() {
    return {
      api,
      activeTab: 'ssh',
      activeTemplateTab: 'ssh-template',
      busyAction: '',
      sshCommandTemplates: [],
      sqlOpsTemplates: [],
      searchBusy: false,
      searchResult: '',
      searchRows: [],
      assetIndexRebuildOpen: false,
      assetIndexRebuildType: '',
      templateImportOpen: false,
      templateImportResult: '',
      templateImport: {
        templateType: 'LINUX_CMD',
        dsl: ''
      },
      search: {
        indexType: 'sql_metadata',
        query: '',
        tableName: '',
        database: '',
        assetName: '',
        assetType: '',
        env: '',
        databaseType: '',
        labels: '',
        limit: 10,
        includeColumns: true
      },
      environmentOptions: envOptions(),
      databaseTypeOptions: databaseTypeOptions(),
      searchIndexOptions: [
        { value: 'sql_metadata', label: '元数据索引' },
        { value: 'ssh_host_assets', label: '服务器资产索引' },
        { value: 'sql_datasource_assets', label: '数据库资产索引' },
        { value: 'http_endpoint_assets', label: 'API 网关资产索引' },
        { value: 'api_service_assets', label: 'API 服务资产索引' },
        { value: 'templates', label: '模板索引' },
        { value: 'database_query', label: '业务查询索引' },
        { value: 'api_service', label: 'API 服务索引' },
        { value: 'document_search', label: '文档索引' },
        { value: 'news', label: '新闻资讯索引（OpenSearch）' }
      ],
      assetIndexRebuildOptions: [
        { value: '', label: '全部资产索引' },
        { value: 'ssh_host', label: '服务器资产索引' },
        { value: 'sql_datasource', label: '数据库资产索引' },
        { value: 'http_endpoint', label: 'API 网关资产索引' },
        { value: 'api_service', label: 'API 服务资产索引' }
      ],
      sqlExtraActions: [
        {
          key: 'refresh-metadata',
          label: '刷新元数据',
          type: 'primary',
          disabled: row => !row?.id,
          successMessage: '元数据刷新已提交',
          run: row => api.refreshSqlMetadata(row.id)
        }
      ],
      sshDefaults: {
        enabled: false,
        port: 22,
        environment: 'DEV',
        authType: 'PASSWORD',
        runtimeAction: 'confirm_required',
        connectTimeoutMs: 10000,
        commandTimeoutMs: 30000,
        allowedCommandsJson: '[]',
        routingLabelsJson: '[]',
        capabilitiesJson: '["ssh","linux_command_execute"]',
        governanceJson: ''
      },
      sqlDefaults: {
        enabled: false,
        environment: 'DEV',
        databaseType: 'generic',
        metadataScopeType: 'JDBC_DATABASE',
        metadataAutoRefreshEnabled: false,
        metadataRefreshIntervalMinutes: 60,
        defaultTimeoutSeconds: 30,
        defaultMaxRows: 1000,
        runtimeAction: 'confirm_required',
        allowedTemplatesJson: '[]',
        allowedTablesJson: '[]',
        sensitiveTablesJson: '[]',
        sensitiveFieldsJson: '[]',
        routingLabelsJson: '[]',
        capabilitiesJson: '["jdbc","sql_query_execute","metadata"]',
        governanceJson: ''
      },
      httpDefaults: {
        method: 'GET',
        enabled: false,
        environment: 'DEV',
        category: 'business_api',
        runtimeAction: 'readonly',
        timeoutMs: 10000,
        headersJson: '{}',
        inputSchemaJson: objectSchemaText,
        outputSchemaJson: objectSchemaText,
        capabilitySpecJson: '{}',
        dependencySpecJson: '{}',
        bodyTemplate: '',
        routingLabelsJson: '["api_gateway","http_endpoint"]',
        capabilitiesJson: '["api_gateway","http","http_request"]',
        governanceJson: ''
      },
      commandTemplateDefaults: {
        enabled: true,
        riskLevel: 'LOW',
        category: 'system_diagnostic',
        runtimeAction: 'confirm_required',
        parameterSchemaJson: objectSchemaText,
        intentSignalsJson: '[]'
      },
      sqlTemplateDefaults: {
        enabled: true,
        riskLevel: 'MEDIUM',
        category: 'sql_diagnostic',
        databaseType: 'generic',
        parameterSchemaJson: objectSchemaText,
        routingLabelsJson: '[]',
        intentSignalsJson: '[]'
      },
      sshColumns: [
        { key: 'name', label: '资产名称' },
        { key: 'toolName', label: '工具名称', type: 'code' },
        { key: 'hostname', label: 'Host' },
        { key: 'environment', label: '环境' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      sqlColumns: [
        { key: 'name', label: '资产名称' },
        { key: 'toolName', label: '工具名称', type: 'code' },
        { key: 'databaseType', label: '数据库类型' },
        { key: 'environment', label: '环境' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      httpColumns: [
        { key: 'name', label: '资产名称' },
        { key: 'toolName', label: '工具名称', type: 'code' },
        { key: 'method', label: '方法' },
        { key: 'urlTemplate', label: 'URL' },
        { key: 'environment', label: '环境' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      commandTemplateColumns: [
        { key: 'code', label: '模板编号', type: 'code' },
        { key: 'title', label: '模板名称' },
        { key: 'commandTemplate', label: '命令内容', formatter: value => previewText(value, 96) },
        { key: 'category', label: '分类' },
        { key: 'riskLevel', label: '风险' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      sqlTemplateColumns: [
        { key: 'code', label: '模板编号', type: 'code' },
        { key: 'title', label: '模板名称' },
        { key: 'sqlTemplate', label: 'SQL 内容', formatter: value => previewText(value, 96) },
        { key: 'databaseType', label: '数据库类型' },
        { key: 'riskLevel', label: '风险' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ]
    };
  },
  computed: {
    sshFields() {
      return [
        { key: 'name', label: '资产名称', required: true, placeholder: '如 生产订单服务器', help: '资产名称使用业务可读名称，可用中文；工具名称才使用系统规范编码。' },
        { key: 'toolName', label: '工具名称', placeholder: '如 ssh_hive01', help: '服务器资产工具名按 ssh_ 前缀命名，例如 ssh_hive01、ssh_order_prod；只使用小写字母、数字和下划线，保存后会用于工具发布。' },
        { key: 'title', label: '显示名称', placeholder: '如 生产订单服务器 SSH', help: '展示给用户和模型看的名称，可用中文；未填写时通常按资产名称展示。' },
        { key: 'enabled', label: '状态', type: 'select', options: boolOptions() },
        { key: 'description', label: '工具描述', type: 'textarea', span: 'col-12', placeholder: '说明这台主机可用于哪些运维场景', help: '描述越清晰，模型越容易在合适场景选择该资产。' },
        { key: 'hostname', label: 'Host', required: true, placeholder: '如 10.10.1.23 或 server.example.com', help: '填写 MCP server 所在网络可访问的 IP 或域名，不要带 ssh://。' },
        { key: 'port', label: '端口', type: 'number', min: 1, step: 1, placeholder: '22', help: 'SSH 端口，必须是 1-65535 的数字。' },
        { key: 'username', label: '用户名', required: true, placeholder: '如 deploy 或 readonly', help: '建议使用低权限运维账号，避免使用 root。' },
        { key: 'authType', label: '认证方式', type: 'select', required: true, options: authTypeOptions(), placeholder: '选择密码或私钥认证' },
        { key: 'password', label: '密码', type: 'password', required: form => String(form.authType || '').toUpperCase() === 'PASSWORD', placeholder: '密码认证时填写' },
        { key: 'privateKey', label: '私钥', type: 'textarea', required: form => String(form.authType || '').toUpperCase() === 'PRIVATE_KEY', rows: 5, span: 'col-12', placeholder: '-----BEGIN OPENSSH PRIVATE KEY-----', help: '私钥认证时填写完整 PEM/OpenSSH 私钥内容。' },
        { key: 'passphrase', label: '私钥口令', placeholder: '私钥有口令时填写' },
        { key: 'hostKeyFingerprint', label: 'Host Key 指纹', placeholder: '如 SHA256:xxxx', help: '可选；填写后会校验主机指纹，提升连接安全性。' },
        { key: 'environment', label: '环境', type: 'select', options: envOptions() },
        { key: 'runtimeAction', label: '运行策略', type: 'select', options: runtimeActionOptions() },
        { key: 'connectTimeoutMs', label: '连接超时秒', type: 'number', min: 1, step: 1, unitScale: 1000, help: '页面按秒填写，保存和测试时自动换算为毫秒。' },
        { key: 'commandTimeoutMs', label: '命令超时秒', type: 'number', min: 1, step: 1, unitScale: 1000, help: '页面按秒填写，保存和测试时自动换算为毫秒。' },
        { key: 'tags', label: '标签', span: 'col-12', placeholder: '输入逗号分隔标签，如 prod,core-api', help: '用于页面搜索、资产分组和人工识别，不参与角色授权。' },
        {
          key: 'allowedCommandsJson',
          label: '允许命令模板',
          type: 'templatePicker',
          itemKey: 'code',
          items: () => this.sshCommandTemplates,
          filterKey: 'category',
          filterLabel: '用途分类',
          filterOptions: commandCategoryOptions,
          span: 'col-12',
          help: '从已维护的 SSH 命令模板中筛选并勾选，保存时自动生成 JSON。'
        },
        { key: 'routingLabelsJson', label: '路由标签', type: 'jsonStringList', placeholder: '输入标签，如 linux、prod', span: 'col-md-6', help: '用于资产检索和路由匹配。' },
        { key: 'capabilitiesJson', label: '能力标签', type: 'jsonStringList', required: true, requiredAnyOf: ['linux_command_execute', 'ssh', 'linux'], requiredAnyOfMessage: '服务器能力标签必须包含 ssh 或 linux_command_execute', placeholder: '输入能力，如 ssh、diagnostic', span: 'col-md-6', help: '描述该资产可提供的能力。' }
      ].map(field => ({ ...field, ...assetFieldLayout('ssh', field.key) }));
    },
    sqlFields() {
      return [
        { key: 'name', label: '资产名称', required: true, placeholder: '如 生产交易库', help: '资产名称使用业务可读名称，可用中文；工具名称才使用系统规范编码。' },
        { key: 'toolName', label: '工具名称', placeholder: '如 db_query_mysql_metadata_prod', help: '数据库资产工具名按 db_query_ 前缀命名，建议格式 db_query_<数据库类型>_<用途>_<环境>，例如 db_query_mysql_metadata_prod；只使用小写字母、数字和下划线。' },
        { key: 'title', label: '显示名称', placeholder: '如 生产交易数据库', help: '展示给用户和模型看的名称，可用中文；未填写时通常按资产名称展示。' },
        { key: 'enabled', label: '状态', type: 'select', options: boolOptions() },
        { key: 'description', label: '工具描述', type: 'textarea', span: 'col-12', placeholder: '说明该数据源包含哪些业务数据，以及允许查询的范围', help: '建议写清楚库用途、数据敏感性和适用查询场景。' },
        { key: 'jdbcUrl', label: 'JDBC URL', required: true, span: 'col-12', placeholder: '如 jdbc:mysql://10.10.1.20:3306/orders?useSSL=false', help: '填写完整 JDBC URL，必须能从 MCP server 连接到数据库。' },
        {
          key: 'driverClass',
          label: 'Driver Class',
          type: 'select',
          allowCreate: true,
          options: driverClassOptions(),
          placeholder: '选择或输入 JDBC Driver Class',
          help: '可从常用驱动中选择，也可以直接输入自定义驱动类；留空时系统按数据库类型尝试推断。'
        },
        { key: 'databaseType', label: 'Database Type', type: 'select', options: databaseTypeOptions(), placeholder: '选择数据库类型', help: '数据库类型会影响探测 SQL、元数据读取和模板匹配。' },
        { key: 'username', label: '只读账号', placeholder: '如 readonly_user', help: '建议配置只读账号，避免使用 DDL/DML 权限账号。' },
        { key: 'password', label: '密码', type: 'password', placeholder: '数据库账号密码' },
        { key: 'environment', label: '环境', type: 'select', options: envOptions() },
        { key: 'runtimeAction', label: '运行策略', type: 'select', options: runtimeActionOptions() },
        { key: 'defaultTimeoutSeconds', label: '默认超时秒', type: 'number', min: 1, step: 1, placeholder: '30', help: '查询执行超时时间，建议 10-60 秒。' },
        { key: 'defaultMaxRows', label: '默认最大行数', type: 'number', min: 1, step: 100, placeholder: '1000', help: '限制查询返回行数，避免大结果集拖慢服务。' },
        { key: 'metadataScopeType', label: '元数据索引范围', type: 'select', options: metadataScopeOptions(), placeholder: '选择元数据索引范围', help: '决定系统自动索引哪些库或 Schema。' },
        {
          key: 'metadataScopeValue',
          label: '索引范围',
          type: 'metadataScopePicker',
          span: 'col-12',
          placeholder: '手动输入库/Schema，多个用逗号分隔',
          extractOptions: extractSqlMetadataScopeOptions,
          help: '测试连接后会展示可选库/Schema，勾选后自动写入范围。'
        },
        { key: 'metadataAutoRefreshEnabled', label: '元数据自动刷新', type: 'select', options: boolOptions('定时自动刷新', '手动刷新') },
        { key: 'metadataRefreshIntervalMinutes', label: '刷新间隔分钟', type: 'number', min: 5, step: 5, placeholder: '60', help: '启用自动刷新时生效，最小 5 分钟。' },
        {
          key: 'allowedTemplatesJson',
          label: '允许 SQL 运维模板',
          type: 'templatePicker',
          itemKey: 'code',
          items: () => this.sqlOpsTemplates,
          filterKey: 'databaseType',
          filterLabel: '数据库类型',
          filterOptions: databaseTypeOptions(),
          filterDefaultFromForm: 'databaseType',
          pickerSubtitle: '按数据库类型过滤并勾选允许该资产使用的 SQL 运维模板。',
          span: 'col-12',
          help: '从已维护的 SQL 运维模板中按数据库类型筛选并勾选，保存时自动生成 JSON。'
        },
        { key: 'allowedTablesJson', label: '允许表', type: 'jsonStringList', placeholder: '输入表名，如 public.orders', span: 'col-md-6', help: '为空表示不额外限制；可回车或用逗号批量添加。' },
        { key: 'sensitiveTablesJson', label: '敏感表', type: 'jsonStringList', placeholder: '输入敏感表名，如 user_secret', span: 'col-md-6', help: '用于标记需要更严格治理的表。' },
        { key: 'sensitiveFieldsJson', label: '敏感字段', type: 'jsonStringList', placeholder: '输入字段名，如 phone、id_card', span: 'col-md-6', help: '用于标记敏感字段，可按 表.字段 或字段名填写。' },
        { key: 'routingLabelsJson', label: '路由标签', type: 'jsonStringList', placeholder: '输入标签，如 mysql、prod', span: 'col-md-6', help: '用于资产检索和路由匹配。' },
        { key: 'capabilitiesJson', label: '能力标签', type: 'jsonStringList', required: true, requiredAnyOf: ['sql_query_execute', 'sql_exec', 'sql', 'jdbc'], requiredAnyOfMessage: '数据库能力标签必须包含 jdbc、sql 或 sql_query_execute', placeholder: '输入能力，如 sql、metadata', span: 'col-md-6', help: '描述该资产可提供的能力。' }
      ].map(field => ({ ...field, ...assetFieldLayout('sql', field.key) }));
    },
    httpFields() {
      return [
        { key: 'name', label: '资产名称', required: true, placeholder: '如 订单中心网关接口', help: '资产名称使用业务可读名称，可用中文；工具名称才使用系统规范编码。' },
        { key: 'toolName', label: '工具名称', placeholder: '如 http_query_monitor_status', help: 'API 网关资产工具名按 http_ 前缀命名，查询类接口建议使用 http_query_<业务能力>，例如 http_query_monitor_status；只使用小写字母、数字和下划线。' },
        { key: 'title', label: '显示名称', placeholder: '如 订单中心 API 网关', help: '展示给用户和模型看的名称，可用中文；未填写时通常按资产名称展示。' },
        { key: 'enabled', label: '状态', type: 'select', options: boolOptions() },
        { key: 'description', label: '工具描述', type: 'textarea', span: 'col-12', placeholder: '说明该 API 可查询或执行的业务能力', help: '建议写清楚接口用途、输入参数含义和返回结果范围。' },
        { key: 'method', label: '方法', type: 'select', required: true, options: methodOptions(), placeholder: '选择 HTTP 方法' },
        { key: 'urlTemplate', label: 'URL 模板', required: true, span: 'col-12', placeholder: '如 https://api.example.com/orders/{orderId}', help: '填写完整 HTTP/HTTPS 地址；路径变量使用 {参数名}，参数名需和入参 Schema 保持一致。' },
        { key: 'environment', label: '环境', type: 'select', options: envOptions() },
        { key: 'category', label: '分类', type: 'select', options: httpCategoryOptions(), placeholder: '选择接口分类' },
        { key: 'runtimeAction', label: '运行策略', type: 'select', options: httpRuntimeActionOptions(), placeholder: '选择执行策略', help: '只读适合查询接口；执行前确认适合可能产生业务影响的接口。' },
        { key: 'timeoutMs', label: '超时毫秒', type: 'number', min: 1, step: 1000, placeholder: '10000', help: '请求超时时间，单位毫秒；10000 表示 10 秒。' },
        { key: 'tags', label: '标签', placeholder: '输入逗号分隔标签，如 gateway,prod', help: '用于页面搜索、资产分组和人工识别，不参与角色授权。' },
        {
          key: 'headersJson',
          label: '请求头',
          type: 'jsonObjectString',
          span: 'col-md-6',
          objectPresets: commonHttpHeaderPresets,
          help: '按键值对维护请求头，可从常用请求头中选择后再修改；保存时自动生成 Headers JSON。'
        },
        { key: 'inputSchemaJson', label: '入参 Schema', type: 'jsonSchemaString', span: 'col-md-6', help: '参数名必须和 URL 模板、Body 模板里的占位符一致；保存时自动生成 JSON Schema。' },
        { key: 'outputSchemaJson', label: '结果 Schema', type: 'jsonSchemaString', span: 'col-md-6', help: '可视化维护接口返回字段，供需求覆盖评审和模型解释结果使用。' },
        { key: 'capabilitySpecJson', label: '能力说明', type: 'jsonObjectString', span: 'col-md-6', help: '描述业务能力、适用场景和意图别名，不包含真实 URL 或认证信息。' },
        { key: 'dependencySpecJson', label: '依赖说明', type: 'jsonObjectString', span: 'col-md-6', help: '描述前置能力、参数来源和调用顺序，供 Runtime 生成依赖 DAG。' },
        {
          key: 'bodyTemplate', label: 'Body 模板', type: 'textarea', rows: 5, span: 'col-12',
          placeholder: '{\n  "sessionId": "{{sessionId}}",\n  "orderId": "{{orderId}}"\n}',
          textPresets: [{ label: '使用 LiveData 动态 sessionId', value: livedataDynamicSessionBody }],
          help: 'POST/PUT/PATCH 可填写 JSON 模板；普通变量使用 {{参数名}} 并由调用方动态传入。LiveData 会话请使用 {{__livedata_session_id}}，服务端会在每次调用时自动获取并在失效后刷新，禁止填写固定 sessionId；GET 通常留空。'
        },
        { key: 'routingLabelsJson', label: '路由标签', type: 'jsonStringList', placeholder: '输入标签，如 gateway、prod', span: 'col-md-6', help: '用于资产检索和路由匹配。' },
        { key: 'capabilitiesJson', label: '能力标签', type: 'jsonStringList', required: true, requiredAnyOf: ['http_request', 'http', 'rest', 'api_call'], requiredAnyOfMessage: 'API 网关能力标签必须包含 http 或 http_request', placeholder: '输入能力，如 http、api', span: 'col-md-6', help: '描述该资产可提供的能力。' }
      ].map(field => ({ ...field, ...assetFieldLayout('http', field.key) }));
    },
    sqlTemplateListFilters() {
      return [
        {
          key: 'category',
          label: '分类',
          placeholder: '按分类筛选',
          options: sqlTemplateCategoryOptions()
        }
      ];
    },
    commandTemplateListFilters() {
      return [
        {
          key: 'category',
          label: '用途分类',
          placeholder: '按用途筛选',
          options: commandCategoryOptions()
        }
      ];
    },
    commandTemplateFields() {
      return [
        { key: 'code', label: '模板编号', required: true },
        { key: 'title', label: '模板名称', required: true },
        { key: 'enabled', label: '状态', type: 'select', options: boolOptions() },
        { key: 'riskLevel', label: '风险等级', type: 'select', options: riskLevelOptions() },
        { key: 'category', label: '分类', type: 'select', options: commandCategoryOptions() },
        { key: 'runtimeAction', label: '运行策略', type: 'select', options: runtimeActionOptions() },
        { key: 'description', label: '描述', type: 'textarea', span: 'col-12' },
        { key: 'commandTemplate', label: '命令模板', type: 'textarea', required: true, rows: 7, span: 'col-12' },
        {
          key: 'parameterSchemaJson',
          label: '参数 Schema',
          type: 'jsonSchemaString',
          span: 'col-12',
          emptyText: '暂无命令参数，点击下方按钮新增。',
          namePlaceholder: '参数名，如 serviceName',
          descriptionPlaceholder: '参数用途和取值说明',
          help: '可视化维护参数名称、类型、是否必填和说明，保存时自动生成 JSON Schema。'
        },
        {
          key: 'intentSignalsJson',
          label: '意图信号',
          type: 'jsonStringList',
          span: 'col-12',
          placeholder: '输入意图词，如服务状态、磁盘空间、日志检查',
          help: '输入后按回车添加，可逐项删除；保存时自动生成 JSON 数组。'
        }
      ];
    },
    sqlTemplateFields() {
      return [
        { key: 'code', label: '模板编号', required: true },
        { key: 'title', label: '模板名称', required: true },
        { key: 'enabled', label: '状态', type: 'select', options: boolOptions() },
        { key: 'riskLevel', label: '风险等级', type: 'select', options: riskLevelOptions() },
        { key: 'category', label: '分类', type: 'select', options: sqlTemplateCategoryOptions() },
        { key: 'databaseType', label: '数据库类型', type: 'select', options: databaseTypeOptions() },
        { key: 'datasourceId', label: '绑定数据源 ID' },
        { key: 'description', label: '描述', type: 'textarea', span: 'col-12' },
        { key: 'sqlTemplate', label: 'SQL 模板', type: 'textarea', required: true, rows: 8, span: 'col-12' },
        {
          key: 'parameterSchemaJson',
          label: '参数 Schema',
          type: 'jsonSchemaString',
          span: 'col-12',
          section: 'advanced',
          sectionTitle: '参数与检索配置',
          sectionSubtitle: '用可视化方式维护参数、路由标签和意图信号。'
        },
        {
          key: 'routingLabelsJson',
          label: '路由标签',
          type: 'jsonStringList',
          placeholder: '输入标签，如 sql、metadata、mysql',
          span: 'col-md-6',
          section: 'advanced',
          sectionTitle: '参数与检索配置',
          help: '用于模板检索和路由匹配，保存时自动生成 JSON。'
        },
        {
          key: 'intentSignalsJson',
          label: '意图信号',
          type: 'jsonStringList',
          placeholder: '输入意图词，如 表大小、慢查询、字段血缘',
          span: 'col-md-6',
          section: 'advanced',
          sectionTitle: '参数与检索配置',
          help: '用于提升自然语言检索命中率，保存时自动生成 JSON。'
        }
      ];
    }
  },
  mounted() {
    this.loadSshCommandTemplates();
    this.loadSqlOpsTemplates();
  },
  methods: {
    async loadSshCommandTemplates() {
      try {
        this.sshCommandTemplates = await api.listCommandTemplates() || [];
      } catch (error) {
        this.$emit('error', error);
      }
    },
    async loadSqlOpsTemplates() {
      try {
        this.sqlOpsTemplates = await api.listSqlTemplates() || [];
      } catch (error) {
        this.$emit('error', error);
      }
    },
    testHttp(item) {
      return api.testHttp(item);
    },
    testSql(item) {
      return api.testSql(item);
    },
    testSsh(item) {
      return api.testSsh(item);
    },
    async refreshOpsTools() {
      await this.runAction('ops', () => api.refreshOps(), '运维/API网关工具已刷新');
    },
    async refreshSqlTools() {
      await this.runAction('sql', () => api.refreshSqlTools(), '数据库工具已刷新');
    },
    openAssetIndexRebuild() {
      this.assetIndexRebuildType = '';
      this.assetIndexRebuildOpen = true;
    },
    async submitAssetIndexRebuild() {
      const assetType = this.assetIndexRebuildType;
      await this.runAction('asset-index', () => api.rebuildAssetIndex(assetType), `${assetIndexRebuildLabel(assetType)}已重建`);
      this.assetIndexRebuildOpen = false;
    },
    async rebuildAllAssetIndexes() {
      await this.runAction('asset-index', () => api.rebuildAssetIndex(''), '资产索引已重建');
    },
    async rebuildTemplateIndex() {
      await this.runAction('template-index', () => api.rebuildTemplateIndex(), '模板索引已重建');
    },
    async runAction(key, action, title) {
      this.busyAction = key;
      try {
        const result = await action();
        this.$emit('notify', { title, message: result?.message || '' });
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async runSearch() {
      this.searchBusy = true;
      try {
        const request = this.searchRequest();
        const result = request.indexType === 'news'
          ? await this.runNewsIndexSearch(request)
          : await api.searchIndex(request);
        this.searchRows = Array.isArray(result?.results) ? result.results : [];
        this.searchResult = prettyJson(result, {});
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.searchBusy = false;
      }
    },
    async runNewsIndexSearch(request) {
      const output = await newsApi.searchIndex({ query: request.query, size: request.limit });
      if (!output?.success) throw new Error(output?.errorMessage || '新闻索引检索失败');
      const data = output?.data || {};
      const items = Array.isArray(data.items) ? data.items : [];
      return {
        indexType: 'news',
        physicalIndex: 'runtime-news-YYYY.MM.DD',
        count: Number(data.count || items.length),
        reference_urls: data.reference_urls || [],
        results: items.map(item => ({
          ...item,
          kind: item.documentKind || 'news_article',
          name: item.title,
          description: item.summary || item.content,
          publishedAt: item.publishTime
        }))
      };
    },
    searchRequest() {
      const labels = String(this.search.labels || '')
        .split(/[,，\s]+/)
        .map(item => item.trim())
        .filter(Boolean);
      return {
        indexType: this.search.indexType,
        query: this.search.query,
        tableName: this.search.tableName,
        database: this.search.database,
        schema: this.search.database,
        assetName: this.search.assetName,
        assetType: this.search.assetType,
        env: this.search.env,
        databaseType: this.search.databaseType,
        dbType: this.search.databaseType,
        labels,
        limit: this.search.limit,
        includeColumns: this.search.includeColumns
      };
    },
    openTemplateImport() {
      this.templateImportOpen = true;
      this.templateImportResult = '';
    },
    async validateTemplateDsl() {
      this.busyAction = 'template-validate';
      try {
        const result = await api.validateTemplateDsl(this.templateImport);
        this.templateImportResult = prettyJson(result, {});
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    },
    async importTemplateDsl() {
      this.busyAction = 'template-import';
      try {
        const result = await api.importTemplateDsl(this.templateImport);
        this.templateImportResult = prettyJson(result, {});
        this.$emit('notify', { title: '模板导入完成' });
        if (this.templateImport.templateType === 'LINUX_CMD') {
          await this.loadSshCommandTemplates();
        } else {
          await this.loadSqlOpsTemplates();
        }
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busyAction = '';
      }
    }
  }
};

function assetFieldLayout(assetType, key) {
  const basic = {
    section: 'basic',
    sectionTitle: '基础信息',
    sectionSubtitle: '先填写业务可识别的信息，描述默认保持轻量。'
  };
  const connection = {
    section: 'connection',
    sectionTitle: assetType === 'http' ? '网关连接' : '连接配置',
    sectionSubtitle: '测试按钮会使用这里的当前表单内容发起真实校验。'
  };
  const runtime = {
    section: 'runtime',
    sectionTitle: '运行策略',
    sectionSubtitle: '控制环境、超时和执行策略。'
  };
  const metadata = {
    section: 'metadata',
    sectionTitle: '元数据范围',
    sectionSubtitle: '测试连接后可勾选探测到的库或 Schema。'
  };
  const request = {
    section: 'request',
    sectionTitle: '请求参数',
    sectionSubtitle: '维护请求头、入参和 Body 模板。'
  };
  const authorization = {
    section: 'authorization',
    sectionTitle: '授权与标签',
    sectionSubtitle: '按需展开维护模板、路由和能力标签。'
  };

  const layouts = {
    ssh: {
      description: { ...basic, rows: 2 },
      hostname: connection,
      port: { ...connection, compact: true },
      username: connection,
      authType: connection,
      password: connection,
      privateKey: { ...connection, rows: 3 },
      passphrase: connection,
      hostKeyFingerprint: connection,
      environment: runtime,
      runtimeAction: runtime,
      connectTimeoutMs: { ...runtime, span: 'col-md-6' },
      commandTimeoutMs: { ...runtime, span: 'col-md-6' },
      tags: runtime,
      allowedCommandsJson: authorization,
      routingLabelsJson: authorization,
      capabilitiesJson: authorization
    },
    sql: {
      description: { ...basic, rows: 2 },
      jdbcUrl: connection,
      driverClass: connection,
      databaseType: connection,
      username: connection,
      password: connection,
      environment: runtime,
      runtimeAction: runtime,
      defaultTimeoutSeconds: { ...runtime, span: 'col-md-6' },
      defaultMaxRows: { ...runtime, span: 'col-md-6' },
      metadataScopeType: metadata,
      metadataScopeValue: metadata,
      metadataAutoRefreshEnabled: metadata,
      metadataRefreshIntervalMinutes: { ...metadata, compact: true },
      allowedTemplatesJson: authorization,
      allowedTablesJson: authorization,
      sensitiveTablesJson: authorization,
      sensitiveFieldsJson: authorization,
      routingLabelsJson: authorization,
      capabilitiesJson: authorization
    },
    http: {
      description: { ...basic, rows: 2 },
      method: connection,
      urlTemplate: connection,
      environment: runtime,
      category: runtime,
      runtimeAction: runtime,
      timeoutMs: { ...runtime, compact: true },
      tags: runtime,
      headersJson: request,
      inputSchemaJson: request,
      bodyTemplate: { ...request, rows: 3 },
      routingLabelsJson: authorization,
      capabilitiesJson: authorization
    }
  };

  return layouts[assetType]?.[key] || basic;
}

function boolOptions(trueLabel = '启用', falseLabel = '停用') {
  return [{ value: true, label: trueLabel }, { value: false, label: falseLabel }];
}

function envOptions() {
  return ['DEV', 'TEST', 'UAT', 'PROD'].map(value => ({ value, label: value }));
}

function methodOptions() {
  return ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(value => ({ value, label: value }));
}

function authTypeOptions() {
  return ['PASSWORD', 'PRIVATE_KEY'].map(value => ({ value, label: value }));
}

function runtimeActionOptions() {
  return [
    { value: 'confirm_required', label: '执行前确认' },
    { value: 'readonly', label: '只读' },
    { value: 'auto_execute', label: '自动执行' }
  ];
}

function httpRuntimeActionOptions() {
  return [
    { value: 'readonly', label: '只读' },
    { value: 'confirm_required', label: '执行前确认' },
    { value: 'auto_execute', label: '自动执行' }
  ];
}

function commonHttpHeaderPresets() {
  return [
    { id: 'accept_json', key: 'Accept', label: 'Accept - 接收 JSON', value: 'application/json' },
    { id: 'content_type_json', key: 'Content-Type', label: 'Content-Type - JSON', value: 'application/json' },
    { id: 'content_type_form', key: 'Content-Type', label: 'Content-Type - 表单', value: 'application/x-www-form-urlencoded' },
    { id: 'authorization_bearer', key: 'Authorization', label: 'Authorization - Bearer Token', value: 'Bearer ${token}' },
    { id: 'api_key', key: 'X-API-Key', label: 'X-API-Key - API Key', value: '${apiKey}' },
    { id: 'request_id', key: 'X-Request-Id', label: 'X-Request-Id - 请求 ID', value: '${requestId}' },
    { id: 'trace_id', key: 'X-Trace-Id', label: 'X-Trace-Id - 链路追踪 ID', value: '${traceId}' },
    { id: 'user_agent', key: 'User-Agent', label: 'User-Agent', value: 'ChatChat-MCP/1.0' },
    { id: 'cache_control_no_cache', key: 'Cache-Control', label: 'Cache-Control - 不缓存', value: 'no-cache' },
    { id: 'accept_language_zh', key: 'Accept-Language', label: 'Accept-Language - 中文', value: 'zh-CN,zh;q=0.9' },
    { id: 'tenant_id', key: 'X-Tenant-Id', label: 'X-Tenant-Id - 租户 ID', value: '${tenantId}' },
    { id: 'env', key: 'X-Env', label: 'X-Env - 环境标识', value: '${env}' }
  ];
}

function metadataScopeOptions() {
  return [
    { value: 'JDBC_DATABASE', label: 'JDBC 当前数据库' },
    { value: 'LOGIN_USER_SCHEMA', label: '登录用户/Schema' },
    { value: 'EXPLICIT_SCHEMA', label: '指定数据库/Schema' }
  ];
}

function extractSqlMetadataScopeOptions(result) {
  const diagnostics = result?.diagnostics || {};
  const values = diagnostics.metadataScopeOptions || diagnostics.availableDatabases || diagnostics.availableSchemas || [];
  if (!Array.isArray(values)) return [];
  return [...new Set(values
    .map(value => value == null ? '' : String(value).trim())
    .filter(Boolean))]
    .sort((left, right) => left.localeCompare(right));
}

function databaseTypeOptions() {
  return [
    'generic',
    'mysql',
    'postgresql',
    'oracle',
    'hive',
    'inceptor',
    'goldendb',
    'dm',
    'tdsql',
    'tidb',
    'kingbase',
    'oceanbase',
    'sqlserver',
    'mariadb',
    'clickhouse'
  ].map(value => ({ value, label: value }));
}

function driverClassOptions() {
  return [
    { value: 'com.mysql.cj.jdbc.Driver', label: 'MySQL / TiDB / TDSQL / GoldenDB - com.mysql.cj.jdbc.Driver' },
    { value: 'org.mariadb.jdbc.Driver', label: 'MariaDB - org.mariadb.jdbc.Driver' },
    { value: 'org.postgresql.Driver', label: 'PostgreSQL - org.postgresql.Driver' },
    { value: 'oracle.jdbc.OracleDriver', label: 'Oracle - oracle.jdbc.OracleDriver' },
    { value: 'com.microsoft.sqlserver.jdbc.SQLServerDriver', label: 'SQL Server - com.microsoft.sqlserver.jdbc.SQLServerDriver' },
    { value: 'org.apache.hive.jdbc.HiveDriver', label: 'Hive / HiveServer2 - org.apache.hive.jdbc.HiveDriver' },
    { value: 'com.transwarp.jdbc.InceptorDriver', label: 'Inceptor - com.transwarp.jdbc.InceptorDriver' },
    { value: 'dm.jdbc.driver.DmDriver', label: 'DM 达梦 - dm.jdbc.driver.DmDriver' },
    { value: 'com.kingbase8.Driver', label: 'Kingbase - com.kingbase8.Driver' },
    { value: 'com.oceanbase.jdbc.Driver', label: 'OceanBase - com.oceanbase.jdbc.Driver' },
    { value: 'com.clickhouse.jdbc.ClickHouseDriver', label: 'ClickHouse - com.clickhouse.jdbc.ClickHouseDriver' },
    { value: 'org.h2.Driver', label: 'H2 - org.h2.Driver' }
  ];
}

function httpCategoryOptions() {
  return [
    { value: 'api_gateway', label: 'API 网关' },
    { value: 'business_api', label: '业务接口' },
    { value: 'monitoring', label: '监控接口' },
    { value: 'third_party', label: '第三方接口' },
    { value: 'webhook', label: 'Webhook' },
    { value: 'ops', label: '运维接口' },
    { value: 'other', label: '其他' }
  ];
}

function riskLevelOptions() {
  return ['LOW', 'MEDIUM', 'HIGH'].map(value => ({ value, label: value }));
}

function commandCategoryOptions() {
  return [
    { value: 'host_diagnostic', label: '服务器基础' },
    { value: 'system_diagnostic', label: '系统负载' },
    { value: 'service_diagnostic', label: '服务状态' },
    { value: 'process_diagnostic', label: '进程/JVM' },
    { value: 'middleware_diagnostic', label: '中间件' },
    { value: 'container_diagnostic', label: '容器/Docker' },
    { value: 'k8s_diagnostic', label: 'Kubernetes' },
    { value: 'network_diagnostic', label: '网络端口' },
    { value: 'storage_diagnostic', label: '磁盘挂载' },
    { value: 'log_diagnostic', label: '日志分析' },
    { value: 'other', label: '其他' }
  ];
}

function sqlTemplateCategoryOptions() {
  return [
    { value: 'sql_diagnostic', label: 'SQL 诊断', matches: ['connection', 'instance', 'lock'] },
    { value: 'metadata', label: '元数据' },
    { value: 'performance', label: '性能分析' },
    { value: 'capacity', label: '容量分析', matches: ['storage', 'capacity'] },
    { value: 'business_check', label: '业务核查' },
    { value: 'other', label: '其他' }
  ];
}

function previewText(value, maxLength = 80) {
  const text = String(value || '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!text) return '-';
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text;
}

function assetIndexRebuildLabel(assetType) {
  const labels = {
    ssh_host: '服务器资产索引',
    sql_datasource: '数据库资产索引',
    http_endpoint: 'API 网关资产索引',
    api_service: 'API 服务资产索引'
  };
  return labels[assetType] || '全部资产索引';
}
