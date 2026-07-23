package com.chatchat.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;

public class LicenseManager {

    private final ObjectMapper objectMapper;
    private final LicenseCrypto crypto;
    private final Path licenseFile;
    private final String publicKeyPem;
    private final String serverId;

    public LicenseManager(ObjectMapper objectMapper, Path licenseFile, Path serverIdFile, String publicKeyPem) {
        this.objectMapper = objectMapper;
        this.crypto = new LicenseCrypto(objectMapper);
        this.licenseFile = licenseFile.toAbsolutePath().normalize();
        this.publicKeyPem = publicKeyPem;
        this.serverId = MachineIdentity.resolve(serverIdFile);
    }

    public String serverId() {
        return serverId;
    }

    public java.util.List<String> macAddresses() {
        return MachineIdentity.macAddresses();
    }

    public LicenseStatus status() {
        if (!Files.isRegularFile(licenseFile)) {
            return LicenseStatus.invalid("NOT_INSTALLED", "尚未安装 License", serverId, null);
        }
        try {
            LicenseDocument document = objectMapper.readValue(Files.readAllBytes(licenseFile), LicenseDocument.class);
            return validate(document);
        } catch (Exception ex) {
            return LicenseStatus.invalid("INVALID_FILE", "License 文件无法读取: " + ex.getMessage(), serverId, null);
        }
    }

    public LicenseStatus install(byte[] content) {
        if (content == null || content.length == 0) throw new LicenseException("License 文件不能为空");
        Path temporary = null;
        try {
            LicenseDocument document = objectMapper.readValue(content, LicenseDocument.class);
            LicenseStatus candidate = validate(document);
            if (!candidate.valid()) throw new LicenseException(candidate.message());

            Path parent = licenseFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "license-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, licenseFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, licenseFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return candidate;
        } catch (LicenseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LicenseException("安装 License 失败: " + ex.getMessage(), ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // The installed license has already been moved; a stale temp file is harmless.
                }
            }
        }
    }

    public byte[] issue(LicensePayload payload, String privateKeyPem, String keyId) {
        validatePayload(payload);
        try {
            LicenseDocument document = crypto.sign(payload, privateKeyPem, keyId);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
        } catch (Exception ex) {
            throw ex instanceof LicenseException licenseException
                ? licenseException : new LicenseException("生成 License 失败", ex);
        }
    }

    public boolean hasModule(String module) {
        LicenseStatus status = status();
        return status.valid() && status.license().modules() != null
            && status.license().modules().stream().anyMatch(value -> normalize(value).equals(normalize(module)));
    }

    public boolean hasFeature(String feature) {
        LicenseStatus status = status();
        return status.valid() && status.license().features() != null
            && Boolean.TRUE.equals(status.license().features().get(feature));
    }

    public String documentText() {
        try {
            return Files.isRegularFile(licenseFile) ? Files.readString(licenseFile, StandardCharsets.UTF_8) : null;
        } catch (Exception ex) {
            throw new LicenseException("读取 License 失败", ex);
        }
    }

    private void validatePayload(LicensePayload payload) {
        if (payload == null) throw new LicenseException("License 内容不能为空");
        if (payload.customer() == null || payload.customer().isBlank()) throw new LicenseException("客户名称不能为空");
        if (payload.product() == null || payload.product().isBlank()) throw new LicenseException("产品不能为空");
        if (payload.serverId() == null || payload.serverId().isBlank()) throw new LicenseException("服务器机器码不能为空");
        if (payload.expireTime() == null) throw new LicenseException("授权到期日不能为空");
        if (payload.modules() == null || payload.modules().isEmpty()) throw new LicenseException("至少选择一个授权模块");
        if (payload.maxUsers() != null && payload.maxUsers() <= 0) throw new LicenseException("最大用户数必须大于 0");
        if (payload.issuedTime() != null && payload.expireTime().isBefore(payload.issuedTime())) {
            throw new LicenseException("授权到期日不能早于签发日");
        }
    }

    private LicenseStatus validate(LicenseDocument document) {
        if (!crypto.verify(document, publicKeyPem)) {
            return LicenseStatus.invalid("INVALID_SIGNATURE", "License 签名无效", serverId,
                document == null ? null : document.payload());
        }
        LicensePayload payload = document.payload();
        if (payload == null) {
            return LicenseStatus.invalid("INVALID_PAYLOAD", "License 内容为空", serverId, null);
        }
        String binding = payload.serverId();
        boolean machineMismatch = binding != null && !binding.isBlank() && !"*".equals(binding)
            && (MachineIdentity.normalizeMac(binding) != null
                ? !MachineIdentity.matchesMac(binding)
                : !serverId.equalsIgnoreCase(binding));
        if (machineMismatch) {
            return LicenseStatus.invalid("SERVER_MISMATCH", "License 与当前服务器不匹配", serverId, payload);
        }
        if (payload.issuedTime() != null && payload.issuedTime().isAfter(LocalDate.now())) {
            return LicenseStatus.invalid("NOT_YET_VALID", "License 尚未生效", serverId, payload);
        }
        if (payload.expireTime() != null && payload.expireTime().isBefore(LocalDate.now())) {
            return LicenseStatus.invalid("EXPIRED", "License 已过期", serverId, payload);
        }
        return LicenseStatus.valid(serverId, payload);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
