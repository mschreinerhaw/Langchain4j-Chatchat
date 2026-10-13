import { apiRequest, getStoredAuthSession, submitAgentTask } from './api';
import { workspaceHeaders } from '../js/utils/workspaceContext';
const root = '/data-science/sql';
const project = id => `${root}/projects/${encodeURIComponent(id)}`;
const body = value => JSON.stringify(value);
export const sqlProjects = () => apiRequest(`${root}/projects`);
export const createSqlProject = value => apiRequest(`${root}/projects`, { method: 'POST', body: body(value) });
export const sqlFiles = id => apiRequest(`${project(id)}/files`);
export const sqlReferences = () => apiRequest(`${root}/reference-files`);
export const saveSqlFile = (id, value) => apiRequest(`${project(id)}/files`, { method: 'PUT', body: body(value) });
export const renameSqlFile = (id, fileId, path, expectedVersion) => apiRequest(`${project(id)}/files/${encodeURIComponent(fileId)}/path`, { method: 'PUT', body: body({ path, expectedVersion }) });
export const sqlVersions = (id, fileId) => apiRequest(`${project(id)}/files/${encodeURIComponent(fileId)}/versions`);
export const sqlGenerations = id => apiRequest(`${project(id)}/generations`);
export const prepareSqlGeneration = (id, value) => apiRequest(`${project(id)}/generations`, { method: 'POST', body: body(value) });
export const bindSqlTask = (id, generationId, taskId) => apiRequest(`${project(id)}/generations/${encodeURIComponent(generationId)}/task`, { method: 'PUT', body: body({ taskId }) });
export const sqlGeneration = (id, generationId) => apiRequest(`${project(id)}/generations/${encodeURIComponent(generationId)}`);
export const applySqlGeneration = (id, generationId, paths) => apiRequest(`${project(id)}/generations/${encodeURIComponent(generationId)}/apply`, { method: 'POST', body: body({ paths }) });
export async function uploadSqlInput(id, file, kind) {
  const form = new FormData(); form.append('file', file);
  return apiRequest(`${project(id)}/uploads?kind=${kind}&expectedVersion=0`, { method: 'POST', body: form });
}
export async function submitSqlGeneration(id, prepared) {
  // Same authentication, RBAC, session advice, idempotency and runtime as all other Agent tasks.
  const task = await submitAgentTask(prepared.taskRequest);
  await bindSqlTask(id, prepared.generationId, task.taskId);
  return task;
}
export async function downloadSqlFile(projectId, file) {
  const blob = await readSqlFileBlob(projectId, file);
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a'); link.href = url; link.download = file.path.split('/').at(-1); link.click();
  URL.revokeObjectURL(url);
}
export async function readSqlFileBlob(projectId, file) {
  const session = getStoredAuthSession();
  const response = await fetch(`/api/v1${project(projectId)}/files/${encodeURIComponent(file.id)}/download`, {
    headers: { ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}), ...workspaceHeaders() }
  });
  if (!response.ok) throw new Error('文件下载失败或无权访问');
  return response.blob();
}
