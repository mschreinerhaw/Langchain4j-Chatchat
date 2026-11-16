import { ref, computed } from 'vue';
import { infrastructureApi } from '../api/infrastructure';
import { deploymentsApi } from '../api/deployments';
import { environmentName } from '../utils/format';
const nodes = ref([]), inventory = ref([]), deployments = ref([]), updatedAt = ref(null), error = ref(''), loading = ref(false);
let pending;
async function refresh() {
  if (pending) return pending;
  loading.value = true;
  pending = (async () => {
    try {
      const values = await Promise.allSettled([infrastructureApi.nodes(), infrastructureApi.inventory(), deploymentsApi.list()]);
      const targets = [nodes, inventory, deployments];
      values.forEach((value, index) => { if (value.status === 'fulfilled') targets[index].value = value.value; });
      const failure = values.find(value => value.status === 'rejected');
      error.value = failure ? failure.reason.message : ''; updatedAt.value = Date.now();
    } finally { loading.value = false; pending = null; }
  })();
  return pending;
}
const containers = computed(() => inventory.value.flatMap(node => (node.data?.containers || []).map(c => ({ ...c, nodeId: node.id, nodeName: node.name, managerOwner: node.data.managementOwner, sampledAt: node.data.collectedAt }))));
const jobs = computed(() => deployments.value.flatMap(node => (node.jobs || []).map(job => ({ ...job, nodeId: node.node, nodeName: node.name, online: node.online }))));
const applications = computed(() => {
  const groups = new Map();
  for (const container of containers.value) {
    const environment = environmentName(container), key = `${container.nodeId}:${environment}`;
    if (!groups.has(key)) groups.set(key, { key, environment, nodeId: container.nodeId, nodeName: container.nodeName, containers: [], managed: false });
    const app = groups.get(key); app.containers.push(container);
    if (container.Labels?.['deployment-ops.installation'] === 'true') app.managed = true;
  }
  for (const job of jobs.value) {
    const key = `${job.nodeId}:${job.environment}`;
    if (!groups.has(key)) groups.set(key, { key, environment: job.environment, nodeId: job.nodeId, nodeName: job.nodeName, containers: [], managed: true });
    const app = groups.get(key);
    if (!app.latestJob || job.updatedAt > app.latestJob.updatedAt) app.latestJob = job;
    if (!app.job || (job.active && !app.job.active) || (!app.job.active && !job.active && job.updatedAt > app.job.updatedAt)) app.job = job;
  }
  return [...groups.values()].map(app => {
    const node = inventory.value.find(n => n.id === app.nodeId);
    const services = app.containers.filter(c => c.Labels?.['deployment-ops.kind'] !== 'job');
    return { ...app, services, status: node?.error || node == null ? 'UNKNOWN' : !services.length ? 'not_deployed' : services.every(c => c.State === 'running' && c.managerMetrics?.health !== 'unhealthy') ? 'running' : 'degraded' };
  });
});
const alerts = computed(() => [
  ...inventory.value.filter(node => node.error).map(node => ({ id: `node:${node.id}`, severity: 'critical', title: 'Docker 节点不可达', resource: node.name, detail: node.error, time: updatedAt.value, route: `/hosts/${node.id}` })),
  ...containers.value.filter(c => c.Labels?.['deployment-ops.kind'] !== 'job' && (c.State !== 'running' || c.managerMetrics?.health === 'unhealthy')).map(c => ({ id: c.Id, severity: c.State === 'exited' ? 'warning' : 'critical', title: c.managerMetrics?.health === 'unhealthy' ? '健康检查异常' : '容器未运行', resource: c.Names?.[0] || c.Id, detail: c.Status, time: c.sampledAt, route: `/containers/${c.nodeId}/${c.Id}` })),
  ...jobs.value.filter(job => ['failed', 'interrupted', 'recovery_required'].includes(job.status)).map(job => ({ id: job.id, severity: 'critical', title: '部署需要处理', resource: job.environment, detail: job.message, time: job.updatedAt, route: `/tasks/deployment/${job.nodeId}/${job.id}` })),
]);
export function useCluster() { return { nodes, inventory, deployments, jobs, containers, applications, alerts, updatedAt, error, loading, refresh }; }
