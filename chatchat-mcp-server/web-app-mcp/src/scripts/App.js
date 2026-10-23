import LoginView from '../views/LoginView.vue';
import ApiServicesView from '../views/ApiServicesView.vue';
import McpServicesView from '../views/McpServicesView.vue';
import AssetCenterView from '../views/AssetCenterView.vue';
import BusinessCategoriesView from '../views/BusinessCategoriesView.vue';
import DatabaseMcpView from '../views/DatabaseMcpView.vue';
import CacheSettingsView from '../views/CacheSettingsView.vue';
import NotificationChannelsView from '../views/NotificationChannelsView.vue';
import AuditLogsView from '../views/AuditLogsView.vue';
import CommandAuditLogsView from '../views/CommandAuditLogsView.vue';
import LicenseView from '../views/LicenseView.vue';
import NewsCollectionView from '../views/NewsCollectionView.vue';
import PythonManagementView from '../views/PythonManagementView.vue';
import AdminUsersView from '../views/AdminUsersView.vue';
import AdminLoginAuditsView from '../views/AdminLoginAuditsView.vue';
import ModalPanel from '../components/ModalPanel.vue';
import JsonBlock from '../components/JsonBlock.vue';
import { ElNotification } from 'element-plus';
import { nextTick } from 'vue';
import { MCP_ENDPOINT } from '../services/config';
import { UnauthorizedError } from '../services/http';
import { licenseApi, authApi } from '../services/api';
import {
  getSessionIdleRemainingMs,
  getToken,
  getUser,
  logout,
  markSessionActivity
} from '../services/session';
import '../styles/layout.css';

const menuComponents = {
  apiServices: ApiServicesView,
  mcpServices: McpServicesView,
  pythonManagement: PythonManagementView,
  newsCollection: NewsCollectionView,
  assetCenter: AssetCenterView,
  businessCategories: BusinessCategoriesView,
  databaseMcp: DatabaseMcpView,
  cacheSettings: CacheSettingsView,
  notificationChannels: NotificationChannelsView,
  auditLogs: AuditLogsView,
  commandAuditLogs: CommandAuditLogsView
};
const licenseMenu = { key: 'license', label: 'License 授权', icon: 'Key', component: LicenseView };
const securityMenus = [
  {key:'adminUsers',label:'用户管理',icon:'User',component:AdminUsersView},
  {key:'adminLoginAudits',label:'登录审计',icon:'Tickets',component:AdminLoginAuditsView}
];
const databaseSections = [
  { key: 'databaseRelational', label: '关系库查询', queryFamily: 'relational' },
  { key: 'databaseTrino', label: 'Trino 查询', queryFamily: 'trino' },
  { key: 'databaseNeo4j', label: 'Neo4j 查询', queryFamily: 'neo4j' },
  { key: 'databaseOpenSearch', label: 'OpenSearch 查询', queryFamily: 'opensearch' },
  { key: 'databaseElasticsearch', label: 'Elasticsearch 查询', queryFamily: 'elasticsearch' },
  { key: 'databaseCalendar', label: '交易日历', section: 'calendar' },
  { key: 'databaseImport', label: '批量导入', section: 'dsl' }
].map(item => ({ ...item, icon: 'Coin', component: DatabaseMcpView }));
const activityEvents = ['pointerdown', 'pointermove', 'keydown', 'scroll', 'touchstart'];
const activityWriteThrottleMs = 1000;

export default {
  name: 'App',
  components: {
    LoginView,
    ModalPanel,
    JsonBlock
  },
  data() {
    return {
      publicPath: import.meta.env.BASE_URL || './',
      authenticated: Boolean(getToken()),
      user: getUser(),
      activeView: 'apiServices',
      mcpEndpoint: MCP_ENDPOINT,
      resultOpen: false,
      resultTitle: '',
      resultValue: null,
      navItems: [],
      accountItems: [],
      passwordOpen: false,
      passwordBusy: false,
      currentPassword: '',
      newPassword: '',
      idleTimer: null,
      lastActivityRecordedAt: 0,
      idleLogoutInProgress: false
    };
  },
  computed: {
    isSystemSettingsView() {
      return this.activeView === 'license';
    },
    activeNav() {
      const items = [...this.navItems.flatMap(item => item.children || [item]), ...this.accountItems];
      return items.find(item => item.key === this.activeView) || items[0] || {};
    }
  },
  mounted() {
    if (this.authenticated) {
      this.startIdleMonitoring();
      this.loadLicensedMenus();
    }
  },
  beforeUnmount() {
    this.stopIdleMonitoring();
  },
  methods: {
    async handleAuthenticated(user) {
      this.authenticated = true;
      this.user = user?.username || getUser();
      this.startIdleMonitoring(true);
      await this.loadLicensedMenus();
      this.notify({ title: '登录成功' });
    },
    async loadLicensedMenus() {
      try {
        const [access, identity] = await Promise.all([licenseApi.menus(), authApi.currentUser()]);
        const granted = new Set(identity.menuKeys || []);
        const licensed = (Array.isArray(access) ? access : [])
          .filter(item => item.authorized && granted.has(item.key) && menuComponents[item.key] && !['templateQueryPublications','authorizationManagement','settings'].includes(item.key))
          .map(item => item.key === 'databaseMcp'
            ? { ...item, label: '查询能力模板', children: databaseSections }
            : { ...item, label: ({ assetCenter: '资产与执行模板', apiServices: 'API 能力模板', pythonManagement: 'Python 能力模板' })[item.key] || item.label, component: menuComponents[item.key] });
        this.accountItems = [
          ...securityMenus.filter(item=>granted.has(item.key)),
          ...(granted.has('license') ? [licenseMenu] : [])
        ];
        const diagnostics = licensed.filter(item => ['mcpServices', 'cacheSettings', 'notificationChannels', 'auditLogs', 'commandAuditLogs'].includes(item.key));
        this.navItems = [
          ...licensed.filter(item => !['settings', 'authorizationManagement', ...diagnostics.map(entry => entry.key)].includes(item.key)),
          ...(diagnostics.length ? [{ key: 'operations', label: '运行与诊断', icon: 'Setting', children: diagnostics }] : [])
        ];
        const available = [...this.navItems.flatMap(item => item.children || [item]), ...this.accountItems];
        if (!available.some(item => item.key === this.activeView)) {
          this.activeView = available[0]?.key || '';
        }
        await nextTick();
        this.$refs.sidebarMenu?.close('settings');
      } catch (error) {
        this.navItems = [];
        this.accountItems = [];
        this.activeView = '';
        await nextTick();
        this.$refs.sidebarMenu?.close('settings');
        if (error instanceof UnauthorizedError) {
          this.authenticated = false;
          this.user = '';
          this.stopIdleMonitoring();
        }
        else this.handleError(error);
      }
    },
    async handleLogout() {
      this.stopIdleMonitoring();
      this.authenticated = false;
      this.user = '';
      await logout();
    },
    async saveOwnPassword() {
      if(this.newPassword.length<8 || this.newPassword.length>128){this.handleError(new Error('新密码长度需为 8-128 位'));return;}
      this.passwordBusy=true;
      try{await authApi.changePassword(this.currentPassword,this.newPassword);this.passwordOpen=false;this.currentPassword='';this.newPassword='';this.forceRelogin();}
      catch(error){this.handleError(error);}finally{this.passwordBusy=false;}
    },
    forceRelogin() {
      this.notify({ title: '请使用新密码重新登录' });
      this.handleLogout();
    },
    startIdleMonitoring(resetActivity = false) {
      this.stopIdleMonitoring();
      if (!this.authenticated) return;
      if (resetActivity) markSessionActivity();
      activityEvents.forEach(eventName => window.addEventListener(eventName, this.handleSessionActivity, { passive: true }));
      document.addEventListener('visibilitychange', this.handleVisibilityChange);
      this.lastActivityRecordedAt = Date.now();
      this.scheduleIdleLogout();
    },
    stopIdleMonitoring() {
      if (this.idleTimer) {
        window.clearTimeout(this.idleTimer);
        this.idleTimer = null;
      }
      activityEvents.forEach(eventName => window.removeEventListener(eventName, this.handleSessionActivity));
      document.removeEventListener('visibilitychange', this.handleVisibilityChange);
    },
    handleSessionActivity() {
      if (!this.authenticated || this.idleLogoutInProgress) return;
      const now = Date.now();
      if (now - this.lastActivityRecordedAt < activityWriteThrottleMs) return;
      this.lastActivityRecordedAt = now;
      markSessionActivity(now);
      this.scheduleIdleLogout(now);
    },
    handleVisibilityChange() {
      if (document.visibilityState !== 'visible' || !this.authenticated) return;
      if (getSessionIdleRemainingMs() <= 0) {
        this.expireIdleSession();
        return;
      }
      this.handleSessionActivity();
    },
    scheduleIdleLogout(now = Date.now()) {
      if (this.idleTimer) window.clearTimeout(this.idleTimer);
      const remaining = getSessionIdleRemainingMs(now);
      if (remaining <= 0) {
        this.expireIdleSession();
        return;
      }
      this.idleTimer = window.setTimeout(() => this.expireIdleSession(), remaining);
    },
    async expireIdleSession() {
      if (!this.authenticated || this.idleLogoutInProgress) return;
      this.idleLogoutInProgress = true;
      this.stopIdleMonitoring();
      this.authenticated = false;
      this.user = '';
      this.notify({ type: 'warning', title: '登录已过期', message: '页面已连续 30 分钟无操作，请重新登录。' });
      try {
        await logout();
      } finally {
        this.idleLogoutInProgress = false;
      }
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
        this.user = '';
        this.stopIdleMonitoring();
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



