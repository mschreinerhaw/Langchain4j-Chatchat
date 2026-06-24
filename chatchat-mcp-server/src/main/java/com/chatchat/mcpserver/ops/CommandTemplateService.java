package com.chatchat.mcpserver.ops;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommandTemplateService {

    private final CommandTemplateConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<CommandTemplateConfig> listAll() {
        ensureDefaults();
        return repository.findAll().stream()
            .sorted(Comparator.comparing(CommandTemplateConfig::getCode))
            .toList();
    }

    @Transactional
    public List<CommandTemplateConfig> listEnabled() {
        ensureDefaults();
        return repository.findByEnabledTrueOrderByCodeAsc();
    }

    @Transactional
    public CommandTemplateConfig save(CommandTemplateConfig config) {
        normalize(config);
        return repository.save(config);
    }

    @Transactional
    public CommandTemplateConfig update(String id, CommandTemplateConfig request) {
        CommandTemplateConfig config = getById(id);
        config.setCode(firstText(request.getCode(), config.getCode()));
        config.setTitle(firstText(request.getTitle(), config.getTitle()));
        config.setDescription(request.getDescription());
        config.setCommandTemplate(firstText(request.getCommandTemplate(), config.getCommandTemplate()));
        config.setParameterSchemaJson(request.getParameterSchemaJson());
        config.setRiskLevel(firstText(request.getRiskLevel(), config.getRiskLevel()));
        config.setCategory(firstText(request.getCategory(), config.getCategory()));
        config.setIntentSignalsJson(request.getIntentSignalsJson());
        config.setEnabled(request.isEnabled());
        config.setRuntimeAction(firstText(request.getRuntimeAction(), "confirm_required"));
        normalize(config);
        return repository.save(config);
    }

    @Transactional
    public void delete(String id) {
        CommandTemplateConfig config = getById(id);
        if (isDefaultCode(config.getCode())) {
            config.setEnabled(false);
            repository.save(config);
            return;
        }
        repository.delete(config);
    }

    public CommandTemplateConfig getById(String id) {
        return repository.findById(requireText(id, "Command template ID cannot be empty"))
            .orElseThrow(() -> new IllegalArgumentException("Command template not found: " + id));
    }

    public CommandTemplateConfig getByCode(String code) {
        ensureDefaults();
        return repository.findByCode(requireText(code, "Command template code cannot be empty"))
            .filter(CommandTemplateConfig::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Command template not found or disabled: " + code));
    }

    @Transactional
    public void ensureDefaults() {
        for (DefaultTemplate template : defaults()) {
            if (repository.findByCode(template.code()).isEmpty()) {
                CommandTemplateConfig config = new CommandTemplateConfig();
                config.setCode(template.code());
                config.setTitle(template.title());
                config.setDescription(template.description());
                config.setCommandTemplate(template.command());
                config.setParameterSchemaJson(writeJson(template.schema()));
                config.setRiskLevel("LOW");
                config.setCategory(categoryFromCode(template.code()));
                config.setIntentSignalsJson(writeJson(List.of(template.code(), template.title(), template.description())));
                config.setRuntimeAction("confirm_required");
                config.setEnabled(true);
                repository.save(config);
            }
        }
        ensureDefault(systemOverviewTemplate());
    }

    private void ensureDefault(DefaultTemplate template) {
        if (repository.findByCode(template.code()).isPresent()) {
            return;
        }
        CommandTemplateConfig config = new CommandTemplateConfig();
        config.setCode(template.code());
        config.setTitle(template.title());
        config.setDescription(template.description());
        config.setCommandTemplate(template.command());
        config.setParameterSchemaJson(writeJson(template.schema()));
        config.setRiskLevel("LOW");
        config.setCategory(categoryFromCode(template.code()));
        config.setIntentSignalsJson(writeJson(List.of(template.code(), template.title(), template.description())));
        config.setRuntimeAction("confirm_required");
        config.setEnabled(true);
        repository.save(config);
    }

    private void normalize(CommandTemplateConfig config) {
        config.setCode(requireText(config.getCode(), "Template code cannot be empty").trim().toUpperCase());
        if (!config.getCode().matches("[A-Z0-9_\\-]{2,128}")) {
            throw new IllegalArgumentException("Template code only supports uppercase letters, numbers, underscore and dash");
        }
        config.setTitle(firstText(config.getTitle(), config.getCode()));
        config.setCommandTemplate(requireText(config.getCommandTemplate(), "Command template cannot be empty"));
        config.setRiskLevel(normalizeRisk(config.getRiskLevel()));
        config.setCategory(normalizeCategory(config.getCategory(), config.getCode()));
        config.setParameterSchemaJson(normalizeJsonObject(config.getParameterSchemaJson()));
        config.setIntentSignalsJson(normalizeJsonArray(config.getIntentSignalsJson()));
        config.setRuntimeAction("confirm_required");
    }

    private String normalizeRisk(String riskLevel) {
        String normalized = firstText(riskLevel, "LOW").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "LOW";
        };
    }

    private String normalizeCategory(String category, String code) {
        String value = firstText(category, categoryFromCode(code));
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String categoryFromCode(String code) {
        String value = code == null ? "" : code.toLowerCase(Locale.ROOT);
        if (value.contains("log")) {
            return "log_diagnostic";
        }
        if (value.contains("service")) {
            return "service_diagnostic";
        }
        if (value.contains("disk") || value.contains("memory") || value.contains("cpu") || value.contains("system")) {
            return "system_diagnostic";
        }
        return "host_diagnostic";
    }

    private boolean isDefaultCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (systemOverviewTemplate().code().equals(normalized)) {
            return true;
        }
        return defaults().stream().anyMatch(template -> template.code().equals(normalized));
    }

    private String normalizeJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return writeJson(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?>) {
                return ModelProtocolJson.compact(value);
            }
        } catch (Exception ignored) {
            // Fall through to safe default.
        }
        return writeJson(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
    }

    private String normalizeJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return writeJson(List.of());
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof List<?>) {
                return ModelProtocolJson.compact(value);
            }
        } catch (Exception ignored) {
            // Fall through to safe default.
        }
        return writeJson(List.of());
    }

    private List<DefaultTemplate> defaults() {
        Map<String, Object> empty = Map.of("type", "object", "properties", Map.of(), "required", List.of());
        Map<String, Object> serviceSchema = Map.of(
            "type", "object",
            "properties", Map.of("service", Map.of("type", "string", "pattern", "^[A-Za-z0-9_.@:-]{1,128}$")),
            "required", List.of("service")
        );
        Map<String, Object> logSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "lines", Map.of("type", "integer", "minimum", 1, "maximum", 1000),
                "path", Map.of("type", "string", "pattern", "^[A-Za-z0-9_./:-]{1,300}$")
            ),
            "required", List.of("lines", "path")
        );
        return List.of(
            new DefaultTemplate("CHECK_HOSTNAME", "主机名", "查询主机名。", "hostname", empty),
            new DefaultTemplate("CHECK_UPTIME", "运行时长", "查询系统运行时间。", "uptime", empty),
            new DefaultTemplate("CHECK_DATE", "系统时间", "查询系统时间。", "date", empty),
            new DefaultTemplate("CHECK_WHOAMI", "当前用户", "查询 SSH 执行用户。", "whoami", empty),
            new DefaultTemplate("CHECK_UNAME", "内核信息", "查询内核和系统信息。", "uname -a", empty),
            new DefaultTemplate("CHECK_CPU", "CPU 状态", "使用 top 查询 CPU 状态。", "top -b -n 1", empty),
            new DefaultTemplate("CHECK_MEMORY", "内存状态", "查询内存使用。", "free -m", empty),
            new DefaultTemplate("CHECK_VMSTAT", "VMStat", "查询 vmstat。", "vmstat", empty),
            new DefaultTemplate("CHECK_DISK", "磁盘状态", "查询磁盘空间。", "df -h", empty),
            new DefaultTemplate("CHECK_BLOCK", "块设备", "查询块设备。", "lsblk", empty),
            new DefaultTemplate("CHECK_IP_ADDR", "网络地址", "查询网络地址。", "ip addr", empty),
            new DefaultTemplate("CHECK_SOCKET", "Socket 状态", "查询 socket 状态。", "ss -tulnp", empty),
            new DefaultTemplate("CHECK_PROCESS", "进程状态", "查询进程列表。", "ps aux", empty),
            new DefaultTemplate("CHECK_JAVA_PROCESS", "Java 进程", "查询 Java 进程。", "jps -lv", empty),
            new DefaultTemplate("CHECK_SERVICE_STATUS", "服务状态", "查询 systemd 服务状态。", "systemctl status {{service}}", serviceSchema),
            new DefaultTemplate("TAIL_LOG", "日志尾部", "读取日志尾部。", "tail -n {{lines}} {{path}}", logSchema)
        );
    }

    private DefaultTemplate systemOverviewTemplate() {
        Map<String, Object> empty = Map.of("type", "object", "properties", Map.of(), "required", List.of());
        String command = "echo '=== 系统负载 ==='; uptime; "
            + "echo '=== 详细负载 ==='; cat /proc/loadavg; "
            + "echo '=== 内存使用 ==='; free -h; "
            + "echo '=== 磁盘使用 ==='; df -h / /boot /var /tmp 2>/dev/null; "
            + "echo '=== CPU/内存占用前20进程 ==='; top -bn1 -o %CPU | head -20; "
            + "echo '=== Docker容器状态 ==='; docker ps -a --format 'table {{.ID}}\\t{{.Image}}\\t{{.Status}}\\t{{.Names}}'";
        command = writeJson(List.of(
            "echo '=== system load ==='; uptime",
            "echo '=== load details ==='; cat /proc/loadavg",
            "echo '=== memory usage ==='; free -h",
            "echo '=== disk usage ==='; df -h / /boot /var /tmp 2>/dev/null",
            "echo '=== top cpu processes ==='; top -bn1 -o %CPU | head -20",
            "echo '=== docker containers ==='; docker ps -a --format 'table {{.ID}}\\t{{.Image}}\\t{{.Status}}\\t{{.Names}}'"
        ));
        return new DefaultTemplate(
            "CHECK_SYSTEM_OVERVIEW",
            "System overview",
            "Read-only host load, memory, disk, process and Docker status overview.",
            command,
            empty
        );
    }

    private String writeJson(Object value) {
        try {
            return ModelProtocolJson.compact(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record DefaultTemplate(String code, String title, String description, String command, Map<String, Object> schema) {
    }
}
