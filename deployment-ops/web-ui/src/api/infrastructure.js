import { get, post, nodePath } from './client';
export const infrastructureApi = {
  nodes: (signal) => get('/nodes', signal),
  inventory: (signal) => get('/inventory', signal),
  host: (node, signal) => get(nodePath(node, '/manager/host'), signal),
  history: (node, signal) => get(nodePath(node, '/manager/history'), signal),
  inspect: (node, id, signal) => get(nodePath(node, `/containers/${id}/inspect`), signal),
  imageParameters: (node, id, signal) => get(nodePath(node, `/manager/images/${id}`), signal),
  configuration: (node, id, signal) =>
    get(nodePath(node, `/manager/containers/${id}/configuration`), signal),
  logs: (node, id, signal) => get(nodePath(node, `/containers/${id}/logs`), signal),
  metrics: (node, id, signal) => get(nodePath(node, `/manager/containers/${id}/metrics`), signal),
  jobs: (node, signal) => get(nodePath(node, '/manager/jobs'), signal),
  job: (node, id, signal) => get(nodePath(node, `/manager/jobs/${id}`), signal),
  plan: (node, spec) => post(nodePath(node, '/manager/plans'), spec),
  execute: (node, plan, confirmation) =>
    post(nodePath(node, '/manager/execute'), {
      planId: plan.id,
      confirmation,
      idempotencyKey: Array.from(crypto.getRandomValues(new Uint8Array(24)), (v) =>
        v.toString(16).padStart(2, '0')
      ).join(''),
    }),
  importImage: (node, file, digest) =>
    post(nodePath(node, '/images/import'), file, { headers: { 'X-SHA256': digest } }),
};
