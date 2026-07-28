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
import LicenseView from '../views/LicenseView.vue';
import NewsCollectionView from '../views/NewsCollectionView.vue';
import ModalPanel from '../components/ModalPanel.vue';
import JsonBlock from '../components/JsonBlock.vue';
import { ElNotification } from 'element-plus';
import { MCP_ENDPOINT } from '../services/config';
import { UnauthorizedError } from '../services/http';
import { licenseApi } from '../services/api';
import { getToken, getUser, logout } from '../services/session';
import '../styles/layout.css';

const menuComponents = {
  apiServices: ApiServicesView,
  mcpServices: McpServicesView,
  newsCollection: NewsCollectionView,
  assetCenter: AssetCenterView,
  databaseMcp: DatabaseMcpView,
  cacheSettings: CacheSettingsView,
  notificationChannels: NotificationChannelsView,
  auditLogs: AuditLogsView,
  commandAuditLogs: CommandAuditLogsView,
  settings: SettingsView
};
const licenseMenu = { key: 'license', label: 'License 授权', icon: 'Key', component: LicenseView };

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
      navItems: [licenseMenu]
    };
  },
  computed: {
    activeNav() {
      return this.navItems.find(item => item.key === this.activeView) || this.navItems[0];
    }
  },
  mounted() {
    if (this.authenticated) this.loadLicensedMenus();
  },
  methods: {
    async handleAuthenticated(user) {
      this.authenticated = true;
      this.user = user?.username || getUser();
      await this.loadLicensedMenus();
      this.notify({ title: '登录成功' });
    },
    async loadLicensedMenus() {
      try {
        const access = await licenseApi.menus();
        const licensed = (Array.isArray(access) ? access : [])
          .filter(item => item.authorized && menuComponents[item.key])
          .map(item => ({ ...item, component: menuComponents[item.key] }));
        this.navItems = [...licensed, licenseMenu];
        if (!this.navItems.some(item => item.key === this.activeView)) {
          this.activeView = licensed[0]?.key || 'license';
        }
      } catch (error) {
        this.navItems = [licenseMenu];
        this.activeView = 'license';
        if (error instanceof UnauthorizedError) this.authenticated = false;
        else this.handleError(error);
      }
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



