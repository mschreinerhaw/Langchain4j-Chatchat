package com.chatchat.api.datascience.python;

import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PythonDefaultEnvironmentService {
    private final PythonAssetRepository assets;
    private final PythonEnvironmentPreferenceRepository preferences;
    private final McpPythonControlPlanePort python;
    private com.chatchat.enterprise.service.EnterpriseAdminService identities;

    @org.springframework.beans.factory.annotation.Autowired
    public void setIdentities(com.chatchat.enterprise.service.EnterpriseAdminService value) { identities = value; }

    public PythonDefaultEnvironmentService(PythonAssetRepository assets,
            PythonEnvironmentPreferenceRepository preferences, McpPythonControlPlanePort python) {
        this.assets = assets; this.preferences = preferences; this.python = python;
    }

    public String defaultAssetId(String tenant, String owner) {
        return preferences.findById(key(tenant, owner)).map(PythonEnvironmentPreference::getAssetId).orElse(null);
    }

    @Transactional
    public Selection setDefault(String tenant, String owner, String assetId) {
        var asset = owned(tenant, owner, assetId);
        var environment = requirePublished(asset.getMcpEnvironmentId());
        var preference = preferences.findById(key(tenant, owner)).orElseGet(PythonEnvironmentPreference::new);
        preference.setId(key(tenant, owner)); preference.setTenantId(tenant); preference.setOwnerId(owner);
        preference.setAssetId(asset.getId()); preferences.saveAndFlush(preference);
        return new Selection(asset.getId(), environment, "USER_DEFAULT");
    }

    /** Resolve once for a new task. Saved contracts keep their original environment. */
    public Selection resolveForTask(String tenant, String authenticatedUserId, String legacyEnvironmentId) {
        if (identities == null) throw new IllegalStateException("Authenticated Python environment owner resolver is unavailable");
        var user = identities.getUserView(authenticatedUserId);
        if (user == null || !tenant.equals(user.tenantId()) || user.username() == null || user.username().isBlank())
            throw new SecurityException("Python environment owner does not match the authenticated tenant");
        return resolve(tenant, user.username(), legacyEnvironmentId);
    }

    public Selection resolve(String tenant, String owner, String legacyEnvironmentId) {
        String selected = defaultAssetId(tenant, owner);
        if (selected != null) {
            var asset = owned(tenant, owner, selected);
            return new Selection(asset.getId(), requirePublished(asset.getMcpEnvironmentId()), "WORKSHOP_DEFAULT");
        }
        var owned = assets.findByTenantIdAndOwnerIdOrderByCreatedAtDesc(tenant, owner);
        if (owned.size() == 1) {
            var asset = owned.get(0);
            return new Selection(asset.getId(), requirePublished(asset.getMcpEnvironmentId()), "SINGLE_WORKSHOP_ENVIRONMENT");
        }
        if (!owned.isEmpty())
            throw new IllegalStateException("请先在数据车间的 Python 环境中设置默认环境，然后重试任务。");
        // Retain compatibility for service principals without a workshop. A user's
        // workshop default always takes precedence over this old deployment option.
        if (legacyEnvironmentId != null && !legacyEnvironmentId.isBlank())
            return new Selection(null, requirePublished(legacyEnvironmentId), "LEGACY_DEPLOYMENT");
        var available = python.environments().stream().filter(e -> "PUBLISHED".equals(e.status())
            && "NONE".equals(e.networkPolicy()) && !e.networkEnabled()).toList();
        if (available.size() == 1) return new Selection(null, available.get(0), "SINGLE_PUBLISHED_ENVIRONMENT");
        throw new IllegalStateException("请先在数据车间添加已发布的 Python 环境并设为默认，然后重试任务。");
    }

    private PythonAssetEntity owned(String tenant, String owner, String id) {
        return assets.findByIdAndTenantIdAndOwnerId(id, tenant, owner)
            .orElseThrow(() -> new IllegalArgumentException("默认 Python 环境不存在或不属于当前用户，请重新选择。"));
    }
    private McpPythonControlPlanePort.EnvironmentView requirePublished(String id) {
        var environment = python.environment(id);
        if (environment == null || !"PUBLISHED".equals(environment.status()))
            throw new IllegalStateException("默认 Python 环境尚未发布或已停用，请选择已发布环境后重试。");
        if (!"NONE".equals(environment.networkPolicy()) || environment.networkEnabled())
            throw new IllegalStateException("任务分析需要禁用网络的 Python 环境，请选择符合要求的默认环境。");
        return environment;
    }
    private static String key(String tenant, String owner) {
        if (tenant == null || tenant.isBlank() || owner == null || owner.isBlank())
            throw new SecurityException("Python environment owner is required");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((tenant + "\u0000" + owner).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Selection(String assetId, McpPythonControlPlanePort.EnvironmentView environment, String source) { }
}
