import CrudCatalog from '../../components/CrudCatalog.vue';
import { assetsApi, databaseApi as api } from '../../services/api';
import { prettyJson } from '../../utils/json';
import { buildTestNotification } from '../../utils/test-result';
import '../../styles/views/database-mcp.css';

const inputSchema = { type: 'object', properties: {}, required: [], additionalProperties: false };

export default {
  name: 'DatabaseMcpView',
  components: { CrudCatalog },
  emits: ['notify', 'error', 'result'],
  data() {
    return {
      api,
      activeTab: 'queries',
      busy: false,
      sqlAssets: [],
      calendarFunctionName: 'trade_date',
      calendarQueryTestPassed: false,
      calendar: {
        enabled: true,
        datasourceId: '',
        sqlTemplate: 'select ZRR,JYR from dsc_cfg.t_xtjyr order by ZRR'
      },
      dslBody: '',
      dslDatasourceId: '',
      dslTargetRegistry: '',
      dslResult: '',
      defaults: {
        enabled: true,
        datasourceId: '',
        maxRows: 50,
        timeoutSeconds: 30,
        inputSchema,
        parameters: {},
        governance: {},
        sqlSteps: []
      },
      columns: [
        { key: 'toolName', label: '工具名称', type: 'code' },
        { key: 'title', label: '显示名称' },
        { key: 'datasourceId', label: '数据源', formatter: value => this.datasourceLabel(value) },
        { key: 'maxRows', label: '最大行数' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      formFields: [
        {
          key: 'toolName',
          label: '工具名称',
          required: true,
          placeholder: '如 query_customer_orders',
          help: '用于 MCP Tool 注册，建议使用小写字母、数字和下划线，保存后需保持稳定。',
          section: 'basic',
          sectionTitle: '基础信息',
          sectionSubtitle: '定义 MCP 工具标识、展示名称和所属数据源。'
        },
        {
          key: 'title',
          label: '显示名称',
          required: true,
          placeholder: '如 查询客户订单',
          help: '面向业务用户展示的名称，尽量说明这个查询能解决什么问题。',
          section: 'basic'
        },
        {
          key: 'datasourceId',
          label: '数据库资产',
          type: 'select',
          required: true,
          options: () => this.enabledDatasourceOptions,
          placeholder: '选择资产中心已维护并启用的数据库资产',
          help: '从资产中心已维护并启用的数据库资产中选择，不需要手工填写数据源 ID。',
          section: 'basic'
        },
        {
          key: 'description',
          label: '工具描述',
          type: 'textarea',
          required: true,
          span: 'col-12',
          placeholder: '说明查询用途、适用场景、返回字段含义和数据口径。',
          help: '描述会参与工具检索，建议包含业务对象、指标口径和使用限制。',
          section: 'basic'
        },
        {
          key: 'implementationSteps',
          label: '功能实现步骤',
          type: 'textarea',
          required: true,
          rows: 4,
          span: 'col-12',
          placeholder: '1. 查询客户基础信息。\n2. 查询客户资产汇总。\n3. 汇总多个结果集进行分析。',
          help: '集合层业务步骤说明会与依赖执行计划、SQL 明细和结果数据一起返回给模型。',
          section: 'basic'
        },
        {
          key: 'sqlSteps',
          label: 'SQL 明细',
          type: 'databaseSqlSteps',
          required: true,
          span: 'col-12',
          help: '通过前置依赖编排串行、并行和汇聚关系；下游参数可安全引用上游结果。',
          section: 'query',
          sectionTitle: 'SQL 流程编排',
          sectionSubtitle: '维护只读 SQL 节点、执行依赖、独立参数映射和结果集业务语义。'
        },
        {
          key: 'queryParameters',
          label: '查询参数配置',
          type: 'databaseParamConfig',
          schemaKey: 'inputSchema',
          paramsKey: 'parameters',
          sqlKey: 'sqlTemplate',
          sqlStepsKey: 'sqlSteps',
          defaultSchema: inputSchema,
          span: 'col-12',
          tableTitle: '查询参数配置',
          tableSubtitle: '维护模型入参和页面测试值。日期参数可在“默认来源”中直接选择。',
          help: '点击“同步参数”可从 SQL 模板中的 :name、${trade_date} 或 {{name}} 自动生成参数行。',
          section: 'query'
        },
        {
          key: 'enabled',
          label: '启用状态',
          type: 'select',
          options: [{ value: true, label: '启用' }, { value: false, label: '停用' }],
          help: '停用后不会作为 MCP 工具发布。',
          section: 'runtime',
          sectionTitle: '运行策略',
          sectionSubtitle: '控制查询发布状态、返回规模和执行超时。'
        },
        {
          key: 'maxRows',
          label: '最大行数',
          type: 'number',
          min: 1,
          step: 1,
          placeholder: '50',
          help: '用户可为当前模板自定义返回行数；执行时服务端会强制限制在 chatchat.tools.database-query.min-rows 与 max-rows 之间。',
          section: 'runtime'
        },
        {
          key: 'timeoutSeconds',
          label: '执行超时（秒）',
          type: 'number',
          min: 1,
          step: 1,
          placeholder: '30',
          help: '查询执行超时时间，建议按业务复杂度控制在 5-60 秒内。',
          section: 'runtime'
        }
      ]
    };
  },
  computed: {
    enabledDatasourceOptions() {
      return this.datasourceOptions(true);
    },
    datasourceSelectOptions() {
      return this.datasourceOptions(false);
    },
    tradingCalendarFunctionOptions() {
      return [
        { value: 'trade_date', label: '当前交易日 trade_date' },
        { value: 'trade_date-1', label: '上一交易日 trade_date-1' },
        { value: 'trade_date+1', label: '下一交易日 trade_date+1' },
        { value: 'today', label: '当天自然日 today' },
        { value: 'month', label: '当前月份 month' },
        { value: 'month_start', label: '当月第一天 month_start' },
        { value: 'month_end', label: '当月最后一天 month_end' }
      ];
    }
  },
  mounted() {
    this.loadSqlAssets();
    this.loadTradingCalendar();
  },
  methods: {
    datasourceOptions(enabledOnly) {
      return this.sqlAssets
        .filter(asset => !enabledOnly || asset.enabled !== false)
        .map(asset => ({
          value: asset.id,
          label: [asset.toolName || asset.name || asset.id, asset.environment || 'DEV', asset.databaseType].filter(Boolean).join(' / ')
        }))
        .sort((a, b) => a.label.localeCompare(b.label));
    },
    datasourceLabel(value) {
      if (!value) return '-';
      return this.datasourceSelectOptions.find(option => option.value === value)?.label || value;
    },
    async loadSqlAssets() {
      try {
        this.sqlAssets = await assetsApi.listSql() || [];
      } catch (error) {
        this.$emit('error', error);
      }
    },
    async refreshTradingCalendar() {
      await Promise.all([this.loadSqlAssets(), this.loadTradingCalendar()]);
    },
    testSaved(item, args) {
      return api.testSaved(item.id, args);
    },
    testDraft(payload) {
      return api.testDraft({
        sql: payload.sqlTemplate,
        sqlSteps: payload.sqlSteps || [],
        params: payload.parameters || {},
        maxRows: payload.maxRows,
        timeoutSeconds: payload.timeoutSeconds,
        datasourceId: payload.datasourceId
      });
    },
    async loadTradingCalendar() {
      this.busy = true;
      try {
        await this.loadSqlAssets();
        this.calendar = { ...this.calendar, ...(await api.getTradingCalendar() || {}) };
        this.resetCalendarFunctionTestState();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    async saveCalendar() {
      await this.run(() => api.saveTradingCalendar(this.calendar), '交易日历配置已保存');
      this.resetCalendarFunctionTestState();
    },
    async testCalendar() {
      const result = await this.runTest(() => api.testTradingCalendar(this.calendar), {
        successTitle: '交易日历查询通过',
        failureTitle: '交易日历查询失败'
      });
      this.calendarQueryTestPassed = !!result?.success;
      if (result) this.$emit('result', { title: '交易日历测试结果', value: result });
    },
    async testCalendarFunction() {
      const result = await this.runTest(
        () => api.testTradingCalendarFunction({ ...this.calendar, functionName: this.calendarFunctionName }),
        {
          successTitle: '交易日函数测试通过',
          failureTitle: '交易日函数测试失败'
        }
      );
      if (result) this.$emit('result', { title: '交易日函数测试结果', value: result });
    },
    resetCalendarFunctionTestState() {
      this.calendarQueryTestPassed = false;
    },
    async validateDsl() {
      await this.runDsl(api.validateDsl, '批量导入内容校验完成');
    },
    async importDsl() {
      await this.runDsl(api.importDsl, '批量导入完成');
      await this.$refs.catalog?.load?.();
    },
    async runDsl(action, message) {
      try {
        const result = await action({
          dsl: this.dslBody,
          datasourceId: this.dslDatasourceId || undefined,
          targetRegistry: this.dslTargetRegistry || undefined
        });
        this.dslResult = prettyJson(result, {});
        this.$emit('notify', { title: message });
      } catch (error) {
        this.$emit('error', error);
      }
    },
    async run(action, message) {
      this.busy = true;
      try {
        const result = await action();
        this.$emit('notify', { title: message });
        return result;
      } catch (error) {
        this.$emit('error', error);
        return null;
      } finally {
        this.busy = false;
      }
    },
    async runTest(action, notificationOptions) {
      this.busy = true;
      try {
        const result = await action();
        this.$emit('notify', buildTestNotification(result, notificationOptions));
        return result;
      } catch (error) {
        this.$emit('error', error);
        return null;
      } finally {
        this.busy = false;
      }
    }
  }
};



