import { apiRequest, fetchRoleAuthorization, saveRoleAuthorization } from "../../services/api.js";
import { resourceRules } from "./permissionWorkspace.js";
// UI commands only: all validation, persistence and execution decisions remain in the unified backend.
export async function saveResourceAuthorization(resource, catalog, form) {
  const rules = resourceRules(resource, catalog),
    targetId = form.targetId;
  if (resource.backend === "permission") {
    const auth = await fetchRoleAuthorization(targetId);
    return saveRoleAuthorization(targetId, {
      ...auth,
      permissionIds: [...new Set([...auth.permissionIds, resource.id])],
      orgScopes: auth.orgScopes.map(({ scopeType, orgId }) => ({ scopeType, orgId })),
    });
  }
  if (resource.backend === "tool") {
    const existing = rules.find(
      (r) =>
        r.backend === "tool" &&
        r.targetType === form.targetType &&
        r.targetId === targetId &&
        r.effect === form.effect &&
        (r.scopeExpression || "") === (form.scopeExpression || "") &&
        r.localToolName === resource.id
    );
    return apiRequest("/permission-center/tool-grants", {
      method: "POST",
      body: JSON.stringify({ ...existing, ...form, localToolName: resource.id, enabled: true }),
    });
  }
  const existing = rules.find(
    (r) =>
      !r.agentId &&
      r.targetType === form.targetType &&
      r.targetId === targetId &&
      r.effect.toLowerCase() === form.effect &&
      r.resourceId === resource.id
  );
  let effectiveAt = existing?.effectiveAt ?? null;
  let expiresAt = existing?.expiresAt ?? null;
  if (form.grantWindow === "permanent") {
    effectiveAt = null;
    expiresAt = null;
  } else if (form.grantWindow === "scheduled") {
    effectiveAt = form.effectiveAt ? new Date(form.effectiveAt).toISOString() : null;
    expiresAt = new Date(form.expiresAt).toISOString();
    if (effectiveAt && effectiveAt >= expiresAt) throw new Error("授权生效时间必须早于到期时间");
  }
  return apiRequest(
    "/enterprise/resource-grants" + (existing ? "/" + encodeURIComponent(existing.id) : ""),
    {
      method: existing ? "PUT" : "POST",
      body: JSON.stringify({
        tenantId: catalog.tenantId,
        resourceType: resource.type,
        resourceId: resource.id,
        principalType: form.targetType,
        principalId: targetId,
        effect: form.effect.toUpperCase(),
        enabled: true,
        effectiveAt,
        expiresAt,
      }),
    }
  );
}
