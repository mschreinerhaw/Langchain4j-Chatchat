package com.chatchat.mcpserver.license;

import com.chatchat.license.LicenseException;
import com.chatchat.license.LicenseManager;
import com.chatchat.license.LicenseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class McpLicenseService {
    private final LicenseProperties properties;
    private final LicenseManager manager;

    public McpLicenseService(LicenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.manager = new LicenseManager(
            objectMapper,
            Path.of(properties.getLicenseFile()),
            Path.of(properties.getServerIdFile()),
            material(properties.getPublicKey(), properties.getPublicKeyPath())
        );
    }

    public LicenseStatus status() { return manager.status(); }
    public String serverId() { return manager.serverId(); }
    public java.util.List<String> macAddresses() { return manager.macAddresses(); }
    public boolean enforcementEnabled() { return properties.isEnforcementEnabled(); }
    public String currentDocument() { return manager.documentText(); }
    public boolean hasModule(String module) { return !enforcementEnabled() || manager.hasModule(module); }
    public boolean hasFeature(String feature) { return !enforcementEnabled() || manager.hasFeature(feature); }

    public boolean allowsTool(String toolName) {
        return toolDenialReason(toolName) == null;
    }

    public String toolDenialReason(String toolName) {
        if (!enforcementEnabled()) return null;
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
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("sql") || name.contains("database_query")) {
            return manager.hasFeature("sql_query") ? null : "License 未授权 SQL 查询功能";
        }
        if (name.contains("news")) return manager.hasFeature("news_collect") ? null : "License 未授权新闻采集功能";
        if (name.contains("agent")) return manager.hasFeature("agent_runtime") ? null : "License 未授权 Agent Runtime 功能";
        if (name.contains("market") || name.contains("finance")) {
            return manager.hasFeature("market_analysis") ? null : "License 未授权行情分析功能";
        }
        return manager.hasModule("mcp") ? null : "License 未授权 MCP 服务模块";
    }

    public void requireRuntimeLicense() {
        if (!enforcementEnabled()) return;
        LicenseStatus status = status();
        if (!status.valid()) throw new LicenseException(status.message());
        if (!manager.hasModule("mcp")) throw new LicenseException("License 未授权 MCP 模块");
    }

    private static String material(String inline, String path) {
        if (inline != null && !inline.isBlank()) return inline.trim();
        if (path == null || path.isBlank()) return "";
        try {
            return Files.readString(Path.of(path).toAbsolutePath().normalize());
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法读取密钥文件: " + path, ex);
        }
    }
}
