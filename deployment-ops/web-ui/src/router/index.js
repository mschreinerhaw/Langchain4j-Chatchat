import { createRouter, createWebHashHistory } from 'vue-router';
import { useSession } from '../stores/session';
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      component: () => import('../views/auth/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      component: () => import('../layouts/ConsoleLayout.vue'),
      children: [
        { path: '', redirect: '/overview' },
        {
          path: 'overview',
          component: () => import('../views/overview/OverviewView.vue'),
          meta: { title: '运行总览' },
        },
        {
          path: 'applications',
          component: () => import('../views/applications/ApplicationsView.vue'),
          meta: { title: '应用与服务' },
        },
        {
          path: 'applications/:node/:environment/:tab?',
          component: () => import('../views/applications/ApplicationDetailView.vue'),
          meta: { title: '应用详情' },
        },
        {
          path: 'deploy',
          component: () => import('../views/deployments/DeploymentWizardView.vue'),
          meta: { title: '部署发布', admin: true },
        },
        {
          path: 'tasks',
          component: () => import('../views/tasks/TasksView.vue'),
          meta: { title: '任务中心' },
        },
        {
          path: 'tasks/:kind/:node/:id',
          component: () => import('../views/tasks/TaskDetailView.vue'),
          meta: { title: '任务详情' },
        },
        {
          path: 'hosts/:node?',
          component: () => import('../views/hosts/HostsView.vue'),
          meta: { title: '服务器管理' },
        },
        {
          path: 'containers',
          component: () => import('../views/containers/ContainersView.vue'),
          meta: { title: '容器管理' },
        },
        {
          path: 'containers/:node/:id',
          component: () => import('../views/containers/ContainerDetailView.vue'),
          meta: { title: '容器详情' },
        },
        {
          path: 'images',
          component: () => import('../views/images/ImagesView.vue'),
          meta: { title: '镜像与发行库' },
        },
        {
          path: 'alerts',
          component: () => import('../views/alerts/AlertsView.vue'),
          meta: { title: '监控告警' },
        },
        {
          path: 'audit',
          component: () => import('../views/admin/AuditView.vue'),
          meta: { title: '操作审计' },
        },
        {
          path: 'settings',
          component: () => import('../views/admin/SettingsView.vue'),
          meta: { title: '系统管理', admin: true },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
});
router.beforeEach(async (to) => {
  document.title = `${to.meta.title} · Deployment Ops`;
  const session = useSession();
  try {
    await session.restore();
  } catch {
    if (!to.meta.public) return { path: '/login', query: { reason: 'offline' } };
  }
  if (!to.meta.public && !session.user.value)
    return { path: '/login', query: { redirect: to.fullPath } };
  if (to.meta.admin && !session.admin.value) return '/overview';
  if (to.path === '/login' && session.user.value) return '/overview';
});
window.addEventListener('ops-session-expired', () => {
  if (router.currentRoute.value.path !== '/login') router.replace('/login');
});
export default router;
