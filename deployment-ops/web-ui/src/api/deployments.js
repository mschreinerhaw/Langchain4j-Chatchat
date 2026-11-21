import { get, post, nodePath } from './client';
export const deploymentsApi = {
  list: (signal) => get('/installations', signal),
  catalog: (node, signal) => get(nodePath(node, '/packages'), signal),
  job: (node, id, signal) => get(nodePath(node, `/installations/${id}`), signal),
  health: (node, id, signal) => get(nodePath(node, `/installations/${id}/health`), signal),
  preflight: (node, spec) => post(nodePath(node, '/installations/preflight'), spec),
  deploy: (node, spec) => post(nodePath(node, '/installations'), spec),
  control: (node, id, action) => post(nodePath(node, `/installations/${id}/${action}`)),
  async upload(node, file, digest, onProgress, signal) {
    const key = `ops-upload:${node}:${digest}`;
    let job;
    const saved = localStorage.getItem(key);
    if (saved) {
      try {
        job = await get(nodePath(node, `/packages/uploads/${saved}`), signal);
      } catch (error) {
        if (error.name === 'AbortError') throw error;
        localStorage.removeItem(key);
      }
    }
    if (job?.status === 'failed') job = null;
    if (!job) {
      job = await post(
        nodePath(node, '/packages/uploads'),
        { name: file.name, size: file.size, sha256: digest },
        { signal }
      );
      localStorage.setItem(key, job.id);
    }
    while (job.offset < file.size) {
      job = await post(
        nodePath(node, `/packages/uploads/${job.id}/${job.offset}`),
        file.slice(job.offset, job.offset + 4 * 1024 ** 2),
        { signal }
      );
      onProgress({ offset: job.offset, size: file.size, status: job.status });
    }
    if (job.status === 'uploading')
      job = await post(nodePath(node, `/packages/uploads/${job.id}/commit`), {}, { signal });
    return job;
  },
  uploadStatus: (node, id, signal) => get(nodePath(node, `/packages/uploads/${id}`), signal),
};
