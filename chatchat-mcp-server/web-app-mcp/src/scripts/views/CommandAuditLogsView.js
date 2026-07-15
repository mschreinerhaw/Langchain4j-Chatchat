import ModalPanel from '../../components/ModalPanel.vue';
import JsonBlock from '../../components/JsonBlock.vue';
import { auditApi } from '../../services/api';
import { formatDateTime } from '../../utils/json';

export default {
  name: 'CommandAuditLogsView',
  components: { ModalPanel, JsonBlock },
  emits: ['error'],
  data() {
    return {
      busy: false,
      logs: [],
      detailOpen: false,
      detail: null,
      filters: {
        username: '',
        datasourceName: '',
        commandType: '',
        success: '',
        keyword: ''
      },
      pageInfo: {
        page: 1,
        pageSize: 20,
        totalCount: 0,
        filteredCount: 0,
        totalPages: 1
      }
    };
  },
  mounted() {
    this.load();
  },
  methods: {
    formatDateTime,
    commandTypeLabel(value) {
      return {
        SQL_QUERY: 'SQL 查询',
        SQL_SCRIPT: 'SQL 脚本',
        DATABASE_QUERY: '数据库模板',
        LINUX_COMMAND: 'Linux 命令'
      }[value] || value || '-';
    },
    async search() {
      this.pageInfo.page = 1;
      await this.load();
    },
    async changePage(page) {
      this.pageInfo.page = page;
      await this.load();
    },
    async changePageSize(pageSize) {
      this.pageInfo.pageSize = pageSize;
      this.pageInfo.page = 1;
      await this.load();
    },
    async load() {
      this.busy = true;
      try {
        const params = this.queryParams();
        const result = await auditApi.list(params);
        const items = Array.isArray(result) ? result : result?.items || result?.records || [];
        this.logs = items;
        this.pageInfo = {
          page: Number(result?.page || params.page || 1),
          pageSize: Number(result?.pageSize || params.pageSize || items.length || 20),
          totalCount: Number(result?.totalCount ?? items.length),
          filteredCount: Number(result?.filteredCount ?? result?.totalCount ?? items.length),
          totalPages: Math.max(1, Number(result?.totalPages || 1))
        };
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    queryParams() {
      return {
        page: this.pageInfo.page,
        pageSize: this.pageInfo.pageSize,
        auditCategory: 'COMMAND_EXECUTION',
        commandType: this.filters.commandType,
        username: this.filters.username,
        datasourceName: this.filters.datasourceName,
        success: this.filters.success,
        keyword: this.filters.keyword
      };
    },
    async openDetail(log) {
      this.detailOpen = true;
      this.detail = log;
      if (!log.id) return;
      try {
        this.detail = await auditApi.get(log.id);
      } catch (error) {
        this.$emit('error', error);
      }
    }
  }
};
