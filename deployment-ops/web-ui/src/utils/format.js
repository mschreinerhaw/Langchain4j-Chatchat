export const bytes = (value) =>
  value == null
    ? '暂无采样'
    : value >= 1024 ** 3
    ? (value / 1024 ** 3).toFixed(1) + ' GiB'
    : value >= 1024 ** 2
    ? (value / 1024 ** 2).toFixed(1) + ' MiB'
    : Math.round(value / 1024) + ' KiB';
export const percent = (value) => (value == null ? '暂无采样' : Number(value).toFixed(1) + '%');
export const dateTime = (value) =>
  value
    ? new Date(typeof value === 'number' && value < 1e12 ? value * 1000 : value).toLocaleString(
        'zh-CN',
        { hour12: false }
      )
    : '—';
export const containerName = (value) =>
  (value?.Names?.[0] || value?.Name || value?.Id || '').replace(/^\//, '');
export const owned = (value) =>
  ['deployment-ops.manager', 'deployment-ops.managed'].some(
    (key) => value?.Labels?.[key] === 'true'
  ) &&
  (!value.managerOwner || value.Labels?.['deployment-ops.owner'] === value.managerOwner);
export const installationOwned = (value) =>
  value?.Labels?.['deployment-ops.installation'] === 'true';
export const environmentName = (value) =>
  value?.Labels?.['deployment-ops.environment'] ||
  value?.Labels?.['com.docker.compose.project']?.replace(/^ops-env-/, '') ||
  value?.Labels?.['deployment-ops.application'] ||
  '独立容器';
