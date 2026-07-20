import LoginView from '../views/LoginView.vue';
import ApiServicesView from '../views/ApiServicesView.vue';
import McpServicesView from '../views/McpServicesView.vue';
import AssetCenterView from '../views/AssetCenterView.vue';
import DatabaseMcpView from '../views/DatabaseMcpView.vue';
import CacheSettingsView from '../views/CacheSettingsView.vue';
import NotificationChannelsView from '../views/NotificationChannelsView.vue';
import AuditLogsView from '../views/AuditLogsView.vue';
import CommandAuditLogsView from '../views/CommandAuditLogsView.vue';
import SettingsView from '../views/SettingsView.vue';
import NewsCollectionView from '../views/NewsCollectionView.vue';
import ModalPanel from '../components/ModalPanel.vue';
import JsonBlock from '../components/JsonBlock.vue';
import { ElNotification } from 'element-plus';
import { MCP_ENDPOINT } from '../services/config';
import { UnauthorizedError } from '../services/http';
import { getToken, getUser, logout } from '../services/session';
import '../styles/layout.css';

export default {
  name: 'App',
  components: {
    LoginView,
    ModalPanel,
    JsonBlock
  },
  data() {
    return {
      authenticated: Boolean(getToken()),
      user: getUser(),
      activeView: 'apiServices',
      mcpEndpoint: MCP_ENDPOINT,
      resultOpen: false,
      resultTitle: '',
      resultValue: null,
      navItems: [
        { key: 'apiServices', label: 'API 服务', icon: 'Connection', component: ApiServicesView },
        { key: 'mcpServices', label: 'MCP 服务', icon: 'Cpu', component: McpServicesView },
        { key: 'newsCollection', label: '资讯采集', icon: 'Tickets', component: NewsCollectionView },
        { key: 'assetCenter', label: '资产中心', icon: 'FolderOpened', component: AssetCenterView },
        { key: 'databaseMcp', label: '数据库查询', icon: 'Coin', component: DatabaseMcpView },
        { key: 'cacheSettings', label: '缓存设置', icon: 'DataLine', component: CacheSettingsView },
        { key: 'notificationChannels', label: '通知告警', icon: 'Bell', component: NotificationChannelsView },
        { key: 'auditLogs', label: '调用审计', icon: 'Tickets', component: AuditLogsView },
        { key: 'commandAuditLogs', label: '命令审计', icon: 'Tickets', component: CommandAuditLogsView },
        { key: 'settings', label: '系统设置', icon: 'Setting', component: SettingsView }
      ]
    };
  },
  computed: {
    activeNav() {
      return this.navItems.find(item => item.key === this.activeView) || this.navItems[0];
    }
  },
  methods: {
    handleAuthenticated(user) {
      this.authenticated = true;
      this.user = user?.username || getUser();
      this.notify({ title: '登录成功' });
    },
    async handleLogout() {
      await logout();
      this.authenticated = false;
      this.user = '';
    },
    forceRelogin() {
      this.notify({ title: '请使用新密码重新登录' });
      this.handleLogout();
    },
    notify(toast) {
      ElNotification({
        type: toast.type === 'danger' ? 'error' : toast.type || 'success',
        title: toast.title || '操作完成',
        message: toast.message || '',
        duration: 3200,
        position: 'bottom-right',
        showClose: true,
        customClass: 'app-notification'
      });
    },
    handleError(error) {
      if (error instanceof UnauthorizedError) {
        this.authenticated = false;
      }
      this.notify({ type: 'danger', title: '操作失败', message: error.message || '请求失败' });
    },
    showResult({ title, value }) {
      this.resultTitle = title || '执行结果';
      this.resultValue = value;
      this.resultOpen = true;
    }
  }
};



