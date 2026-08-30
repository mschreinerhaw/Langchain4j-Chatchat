import LoginView from '../views/LoginView.vue';
import ApiServicesView from '../views/ApiServicesView.vue';
import McpServicesView from '../views/McpServicesView.vue';
import TemplateQueryPublicationsView from '../views/TemplateQueryPublicationsView.vue';
import AssetCenterView from '../views/AssetCenterView.vue';
import BusinessCategoriesView from '../views/BusinessCategoriesView.vue';
import DatabaseMcpView from '../views/DatabaseMcpView.vue';
import CacheSettingsView from '../views/CacheSettingsView.vue';
import NotificationChannelsView from '../views/NotificationChannelsView.vue';
import AuditLogsView from '../views/AuditLogsView.vue';
import CommandAuditLogsView from '../views/CommandAuditLogsView.vue';
import SettingsView from '../views/SettingsView.vue';
import LicenseView from '../views/LicenseView.vue';
import NewsCollectionView from '../views/NewsCollectionView.vue';
import PythonManagementView from '../views/PythonManagementView.vue';
import ModalPanel from '../components/ModalPanel.vue';
import JsonBlock from '../components/JsonBlock.vue';
import { ElNotification } from 'element-plus';
import { MCP_ENDPOINT } from '../services/config';
import { UnauthorizedError } from '../services/http';
import { licenseApi } from '../services/api';
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
  templateQueryPublications: TemplateQueryPublicationsView,
  newsCollection: NewsCollectionView,
  assetCenter: AssetCenterView,
  businessCategories: BusinessCategoriesView,
  databaseMcp: DatabaseMcpView,
  cacheSettings: CacheSettingsView,
  notificationChannels: NotificationChannelsView,
  auditLogs: AuditLogsView,
  commandAuditLogs: CommandAuditLogsView,
  settings: SettingsView
};
const licenseMenu = { key: 'license', label: 'License 授权', icon: 'Key', component: LicenseView };
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
      navItems: [licenseMenu],
      idleTimer: null,
      lastActivityRecordedAt: 0,
      idleLogoutInProgress: false
    };
  },
  computed: {
    activeNav() {
      return this.navItems.find(item => item.key === this.activeView) || this.navItems[0];
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



