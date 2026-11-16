<template>
  <div class="console">
    <aside class="sidebar">
      <RouterLink to="/overview" class="brand">
        <AppLogo />
        <span>
          Deployment Ops
          <small>部署与运维管理平台</small>
        </span>
      </RouterLink>
      <nav aria-label="主导航">
        <template v-for="group in navigation" :key="group.label">
          <p class="nav-group">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items.filter((i) => !i.admin || admin)"
            :key="item.path"
            :to="item.path"
          >
            <AppIcon class="nav-icon" :name="item.icon" />
            {{ item.label }}
            <span v-if="item.path === '/alerts' && alerts.length" class="nav-count">
              {{ alerts.length }}
            </span>
          </RouterLink>
        </template>
      </nav>
      <div class="sidebar-footer">
        <i class="online-dot" />
        本地控制平面
        <span>Spring Boot · H2</span>
      </div>
    </aside>
    <div class="console-main">
      <header class="topbar">
        <div class="breadcrumbs">
          运维工作台
          <span>/</span>
          {{ route.meta.title }}
        </div>
        <div class="topbar-actions">
          <RouterLink to="/tasks">
            任务
            <b>{{ activeTasks }}</b>
          </RouterLink>
          <RouterLink to="/alerts">
            异常
            <b>{{ alerts.length }}</b>
          </RouterLink>
          <span class="user-name">
            {{ user?.username }}
            <small>{{ admin ? '管理员' : '只读用户' }}</small>
          </span>
          <button class="text-button" @click="logout">退出</button>
        </div>
      </header>
      <div class="page-heading">
        <div>
          <p class="eyebrow">OPERATIONS WORKSPACE</p>
          <h1>{{ route.meta.title }}</h1>
        </div>
        <div class="refresh-tools">
          <small>{{ updatedAt ? '更新于 ' + dateTime(updatedAt) : '等待首次采样' }}</small>
          <button :disabled="loading" @click="refresh">
            {{ loading ? '刷新中…' : '刷新状态' }}
          </button>
        </div>
      </div>
      <main class="page-content">
        <div v-if="error" class="notice danger" role="alert">
          {{ error }}
          <button @click="refresh">重试</button>
        </div>
        <RouterView :key="route.path" />
      </main>
      <footer class="page-footer">Deployment Ops · 本地管理 / 资源状态以最近采样为准</footer>
    </div>
    <OperationDialog />
  </div>
</template>
<script setup>
  import { computed, onMounted, onUnmounted } from 'vue';
  import AppIcon from '../components/ui/AppIcon.vue';
  import AppLogo from '../components/ui/AppLogo.vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useSession } from '../stores/session';
  import { useCluster } from '../stores/cluster';
  import { dateTime } from '../utils/format';
  import OperationDialog from '../components/operations/OperationDialog.vue';
  const route = useRoute(),
    router = useRouter(),
    session = useSession(),
    { user, admin } = session,
    { alerts, jobs, loading, error, updatedAt, refresh } = useCluster();
  const activeTasks = computed(
    () =>
      jobs.value.filter((job) =>
        ['running', 'queued', 'starting', 'stopping', 'rolling_back'].includes(job.status)
      ).length
  );
  const navigation = [
    {
      label: '运行与服务',
      items: [
        { path: '/overview', label: '运行总览', icon: 'dashboard' },
        { path: '/applications', label: '应用与服务', icon: 'applications' },
        { path: '/deploy', label: '部署发布', icon: 'rocket', admin: true },
        { path: '/tasks', label: '任务中心', icon: 'tasks' },
        { path: '/alerts', label: '监控告警', icon: 'bell' },
      ],
    },
    {
      label: '基础设施',
      items: [
        { path: '/hosts', label: '服务器管理', icon: 'servers' },
        { path: '/containers', label: '容器管理', icon: 'containers' },
        { path: '/images', label: '镜像与发行库', icon: 'images' },
      ],
    },
    {
      label: '平台治理',
      items: [
        { path: '/audit', label: '操作审计', icon: 'audit' },
        { path: '/settings', label: '系统管理', icon: 'settings', admin: true },
      ],
    },
  ];
  let timer;
  onMounted(() => {
    refresh();
    timer = setInterval(refresh, 15000);
  });
  onUnmounted(() => clearInterval(timer));
  async function logout() {
    await session.logout();
    router.push('/login');
  }
</script>
