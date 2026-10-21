// Product navigation provenance only. Authentication and object policies remain server-owned.
let activeContext = null;
export function setWorkspaceContext(context) { activeContext = context ? Object.freeze({ ...context }) : null; }
export function getWorkspaceContext() { return activeContext; }
export function workspaceHeaders(context = activeContext) {
  return context?.workspaceId ? { "X-Workspace-Id": context.workspaceId } : {};
}
export function workspaceStorageKey(namespace, context = activeContext) {
  if (!context?.tenantId || !context?.userId || !context?.workspaceId) return null;
  return `${namespace}:${[context.tenantId, context.userId, context.workspaceId].map(encodeURIComponent).join(":")}`;
}
export function parseWorkspaceRoute(hash = "") {
  let route;
  try { route = decodeURIComponent(hash.replace(/^#\/?/, "").split("?")[0]); }
  catch (_) { return { invalid: true }; }
  if (route === "workspaces") return { workspaceId: "zhice", pageId: "workspaces" };
  if (route.startsWith("w/")) {
    const parts = route.split("/");
    if (parts.length !== 3 || !/^[a-z][a-z0-9-]{0,63}$/.test(parts[1])
        || !/^[a-zA-Z][a-zA-Z0-9-]{0,63}$/.test(parts[2])) return { invalid: true };
    return { workspaceId: parts[1], pageId: parts[1]==='data-science' && parts[2]==='dataScienceScripts' ? 'dataScienceData' : parts[2] };
  }
  return { legacyView: route === "login" ? "" : route === "dataScience" ? "dataScienceEnvironment" : route === "dataScienceScripts" ? "dataScienceData" : route === "system" ? "systemUsers" : route };
}
export function chooseWorkspace(items, route, preference = {}) {
  const valid = (id) => items.find((item) => item.workspaceId === id);
  if (route.workspaceId) return valid(route.workspaceId) || null;
  if ((route.legacyView?.startsWith("dataScience") && route.legacyView !== "dataScienceSkills") || route.legacyView === "sqlStudio")
    return valid("data-science") || null;
  if (route.legacyView) return items.find((item) => item.defaultWorkspace) || items[0] || null;
  return valid(preference.defaultWorkspaceId)
    || items.find((item) => item.defaultWorkspace) || items[0] || null;
}
