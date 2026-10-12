let activeWorkspace = "zhice";
const resourceOrigins = new Map();
export function setActiveWorkspace(id = "zhice") { activeWorkspace = id; }
export function getActiveWorkspace() { return activeWorkspace; }
export function resourceWorkspace(type, id) { return resourceOrigins.get(`${identity()}:${type}:${id}`) || activeWorkspace; }
export function resetWorkspaceContext() { activeWorkspace = "zhice"; resourceOrigins.clear(); }
function identity() {
  try { const user = JSON.parse(localStorage.getItem("chatchat.auth.session") || "null")?.user || {};
    return `${user.tenantId || user.tenant_id || "default"}:${user.id || user.username || "anonymous"}`;
  } catch { return "anonymous"; }
}
export function workspaceStorageKey(base, workspaceId = activeWorkspace) {
  return `${base}:${encodeURIComponent(identity())}:${encodeURIComponent(workspaceId)}`;
}
export function rememberResource(type, id, workspaceId = activeWorkspace) {
  const key = `${identity()}:${type}:${id}`;
  if (id && !resourceOrigins.has(key)) resourceOrigins.set(key, workspaceId);
}
export function workspaceHeaders(path = "", explicitWorkspace) {
  let match = path.match(/\/agent\/tasks\/(?:runtime\/tasks\/)?([^/?]+)/); let type = "task";
  if (!match) { match = path.match(/\/conversations\/([^/?]+)/); type = "session"; }
  if (!match) { match = path.match(/\/ui-artifacts\/([^/?]+)/); type = "artifact"; }
  const origin = match ? resourceOrigins.get(`${identity()}:${type}:${decodeURIComponent(match[1])}`) : null;
  return { "X-Workspace-Id": explicitWorkspace || origin || activeWorkspace };
}
export function rememberResponseOrigins(data, path, workspaceId, depth = 0, requestIdentity = identity()) {
  if (requestIdentity !== identity()) return;
  if (!data || typeof data !== "object" || depth > 5) return;
  if (data.taskId) rememberResource("task", data.taskId, workspaceId);
  if (data.conversationId || data.sessionId) rememberResource("session", data.conversationId || data.sessionId, workspaceId);
  if (data.artifactId) rememberResource("artifact", data.artifactId, workspaceId);
  if (path.startsWith("/conversations") && data.id) rememberResource("session", data.id, workspaceId);
  for (const value of Object.values(data)) if (typeof value === "object") rememberResponseOrigins(value, path, workspaceId, depth + 1, requestIdentity);
}
export function workspaceIdentity() { return identity(); }
export function parseWorkspaceRoute(hash = "") {
  let route;
  try { route = decodeURIComponent(hash.replace(/^#\/?/, "").split("?")[0]); } catch { return { invalid: true }; }
  if (route === "workspaces") return { selection: true };
  if (route.startsWith("w/")) {
    const match = route.match(/^w\/([a-z][a-z0-9-]{0,63})\/([a-zA-Z][a-zA-Z0-9/-]{0,127})$/);
    return match ? { workspaceId: match[1], route: match[2] } : { invalid: true };
  }
  if (route === "dataScience") route = "dataScienceEnvironment";
  if (route === "system") route = "systemUsers";
  return { workspaceId: "zhice", viewId: route === "login" ? "" : route, legacy: true };
}
export function workspaceFamily(viewId) {
  return ["dataScience", "dataScienceEnvironment", "dataScienceDevelop", "dataScienceData", "dataScienceScripts"].includes(viewId) ? "pythonStudio" : viewId;
}
