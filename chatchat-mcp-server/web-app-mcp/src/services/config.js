export const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';
export const MCP_ENDPOINT = `${window.location.origin}/mcp`;
// Deployment setting: the workspace and MCP admin can be hosted on different origins.
export const WORKSPACE_PUBLICATION_URL = import.meta.env.VITE_WORKSPACE_URL
  ? `${import.meta.env.VITE_WORKSPACE_URL.replace(/\/$/, '')}/#/w/mcp-publication/toolPublications`
  : '';
