package com.chatchat.mcpserver.license;

import com.chatchat.license.LicenseException;
import com.chatchat.license.LicenseManager;
import com.chatchat.license.LicenseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class McpLicenseService {
    private final LicenseProperties properties;
    private final LicenseManager manager;
    private final McpAdminMenuCatalog menuCatalog;

    @Autowired
    public McpLicenseService(LicenseProperties properties, ObjectMapper objectMapper,
                             McpAdminMenuCatalog menuCatalog) {
        this.properties = properties;
        this.menuCatalog = menuCatalog;
        this.manager = manager(properties, objectMapper);
    }

    public McpLicenseService(LicenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.menuCatalog = new McpAdminMenuCatalog(objectMapper);
        this.manager = manager(properties, objectMapper);
    }

    private static LicenseManager manager(LicenseProperties properties, ObjectMapper objectMapper) {
        return new LicenseManager(
            objectMapper,
            resolveLicenseFile(Path.of(properties.getLicenseFile())),
            Path.of(properties.getServerIdFile()),
            material(properties.getPublicKey(), properties.getPublicKeyPath())
        );
    }

    public LicenseStatus status() { return manager.status(); }
    public String serverId() { return manager.serverId(); }
    public java.util.List<String> macAddresses() { return manager.macAddresses(); }
    public boolean enforcementEnabled() { return true; }
    public String currentDocument() { return manager.documentText(); }
    public boolean hasModule(String module) { return manager.hasModule(module); }
    public boolean hasFeature(String feature) { return manager.hasFeature(feature); }

    public boolean allowsTool(String toolName) {
        return toolDenialReason(toolName) == null;
    }

    public String toolDenialReason(String toolName) {
        LicenseStatus status = status();
        if (!status.valid()) {
            return switch (status.status()) {
                case "EXPIRED" -> "License 已过期，新的 MCP 工具调用已停止，请联系供应商续期";
                case "NOT_YET_VALID" -> "License 尚未生效，暂时不能调用 MCP 工具";
                case "SERVER_MISMATCH" -> "License 绑定的 MAC 地址与当前服务器不匹配";
                case "NOT_INSTALLED" -> "尚未安装 License，不能调用 MCP 工具";
                default -> "License 无效，不能调用 MCP 工具: " + status.message();
            };
        }
        var toolModule = menuCatalog.moduleForTool(toolName);
        if (toolModule.isPresent() && !menuCatalog.authorized(status, toolModule.get().key())) {
            return "License 未授权 MCP 功能模块: " + toolModule.get().label();
        }
        return hasAnyMcpMenuEntitlement(status) ? null : "License 未授权任何 MCP 菜单模块";
    }

    public void requireRuntimeLicense() {
        LicenseStatus status = status();
        if (!status.valid()) throw new LicenseException(status.message());
        if (!hasAnyMcpMenuEntitlement(status)) throw new LicenseException("License 未授权任何 MCP 菜单模块");
    }

    private boolean hasAnyMcpMenuEntitlement(LicenseStatus status) {
        return menuCatalog.access(status).stream().anyMatch(McpAdminMenuCatalog.MenuAccess::authorized);
    }

    private static String material(String inline, String path) {
        if (inline != null && !inline.isBlank()) return inline.trim();
        if (path == null || path.isBlank()) return "";
        try {
            Path keyFile = Path.of(path).toAbsolutePath().normalize();
            if (Files.isDirectory(keyFile)) {
                keyFile = keyFile.resolve("license-public.pem");
            }
            if (!Files.isRegularFile(keyFile)) {
                return "";
            }
            return Files.readString(keyFile);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException argumentException) throw argumentException;
            throw new IllegalArgumentException("无法读取密钥文件: " + path, ex);
        }
    }

    private static Path resolveLicenseFile(Path configured) {
        Path normalized = configured.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) return normalized;
        Path directory = normalized.getParent();
        if (directory == null || !Files.isDirectory(directory)) return normalized;
        try (var files = Files.list(directory)) {
            java.util.List<Path> candidates = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dat"))
                .toList();
            return candidates.size() == 1 ? candidates.get(0) : normalized;
        } catch (Exception ignored) {
            return normalized;
        }
    }
}
