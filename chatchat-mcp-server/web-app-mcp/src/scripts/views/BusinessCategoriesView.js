import CrudCatalog from '../../components/CrudCatalog.vue';
import { businessCategoriesApi as api } from '../../services/api';
import '../../styles/views/database-mcp.css';

export default {
  name: 'BusinessCategoriesView',
  components: { CrudCatalog },
  emits: ['notify', 'error', 'result'],
  data() {
    return {
      api,
      defaults: {
        enabled: true,
        code: '',
        name: '',
        domain: '',
        description: '',
        keywords: [],
        sortOrder: 100
      },
      columns: [
        { key: 'code', label: '分类编码', type: 'code' },
        { key: 'name', label: '分类名称' },
        { key: 'domain', label: '业务领域', formatter: value => value || '-' },
        { key: 'description', label: '用途说明', formatter: value => value || '-' },
        {
          key: 'keywords',
          label: '检索关键词',
          formatter: value => keywordList(value).join('、') || '-'
        },
        { key: 'sortOrder', label: '排序' },
        { key: 'enabled', label: '状态', type: 'badge', formatter: value => value === false ? '停用' : '启用' }
      ],
      formFields: [
        {
          key: 'code',
          label: '分类编码',
          required: true,
          placeholder: '如 customer_service',
          help: '使用稳定的小写英文、数字和下划线；分类编码参与模板检索和能力隔离。'
        },
        {
          key: 'name',
          label: '分类名称',
          required: true,
          placeholder: '如 客户服务'
        },
        {
          key: 'domain',
          label: '业务领域',
          required: true,
          placeholder: '如 finance、operations'
        },
        {
          key: 'description',
          label: '用途说明',
          type: 'textarea',
          span: 'col-12',
          placeholder: '说明该分类覆盖的真实业务请求、数据口径和边界。'
        },
        {
          key: 'keywords',
          label: '检索关键词',
          type: 'jsonStringList',
          payloadAsArray: true,
          span: 'col-12',
          placeholder: '输入用户常用表达、业务对象或指标同义词',
          help: '模板检索会先依据关键词识别业务分类，再在分类内进行语义排序。'
        },
        {
          key: 'sortOrder',
          label: '排序',
          type: 'number',
          min: 0,
          step: 1
        },
        {
          key: 'enabled',
          label: '启用状态',
          type: 'select',
          required: true,
          options: [
            { value: true, label: '启用' },
            { value: false, label: '停用' }
          ]
        }
      ]
    };
  }
};

function keywordList(value) {
  if (Array.isArray(value)) return value.filter(Boolean);
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter(Boolean) : [];
  } catch (error) {
    return String(value).split(/[\n,，]/).map(item => item.trim()).filter(Boolean);
  }
}
