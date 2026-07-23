package com.chatchat.license.server;

import com.chatchat.license.LicenseCrypto;
import com.chatchat.license.LicenseDocument;
import com.chatchat.license.LicenseException;
import com.chatchat.license.LicensePayload;
import com.chatchat.license.MachineIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

@Service
public class LicenseIssuanceService {
    private final LicenseCenterProperties properties;
    private final ObjectMapper objectMapper;
    private final LicenseCrypto crypto;

    public LicenseIssuanceService(LicenseCenterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.crypto = new LicenseCrypto(objectMapper);
    }

    public byte[] issue(LicensePayload requested) {
        String mac = MachineIdentity.normalizeMac(requested == null ? null : requested.serverId());
        if (mac == null) throw new LicenseException("请输入有效的客户服务器 MAC 地址");
        LicensePayload payload = new LicensePayload(
            requested.licenseNo(), requested.customer(), requested.customerCode(), requested.product(),
            requested.edition(), requested.modules(), requested.maxUsers(), mac, requested.expireTime(),
            requested.features(), requested.issuedTime());
        validate(payload);
        try {
            LicenseDocument document = crypto.sign(payload, privateKey(), properties.getKeyId());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
        } catch (LicenseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LicenseException("生成 License 失败", ex);
        }
    }

    private synchronized String privateKey() {
        if (properties.getPrivateKeyPath() == null || properties.getPrivateKeyPath().isBlank()) {
            throw new LicenseException("未配置 License Center 签发私钥");
        }
        try {
            Path privateKey = Path.of(properties.getPrivateKeyPath()).toAbsolutePath().normalize();
            if (!Files.exists(privateKey)) {
                generateKeyPair(privateKey);
            }
            return Files.readString(privateKey);
        } catch (Exception ex) {
            if (ex instanceof LicenseException licenseException) throw licenseException;
            throw new LicenseException("无法读取 License Center 签发私钥", ex);
        }
    }

    private void generateKeyPair(Path privateKey) throws Exception {
        if (!properties.isAutoGenerateKeys()) {
            throw new LicenseException("未配置 License Center 签发私钥");
        }
        String configuredPublicKey = properties.getPublicKeyPath();
        Path publicKey = configuredPublicKey == null || configuredPublicKey.isBlank()
            ? privateKey.resolveSibling("license-public.pem")
            : Path.of(configuredPublicKey).toAbsolutePath().normalize();
        if (privateKey.getParent() != null) Files.createDirectories(privateKey.getParent());
        if (publicKey.getParent() != null) Files.createDirectories(publicKey.getParent());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        restrictPrivateKey(privateKey);
    }

    private String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----\n";
    }

    private void restrictPrivateKey(Path privateKey) {
        try {
            Files.setPosixFilePermissions(privateKey, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use their native ACLs.
        } catch (Exception ex) {
            throw new LicenseException("无法限制 License Center 私钥文件权限", ex);
        }
    }

    private void validate(LicensePayload payload) {
        if (payload.customer() == null || payload.customer().isBlank()) throw new LicenseException("客户名称不能为空");
        if (payload.product() == null || payload.product().isBlank()) throw new LicenseException("产品不能为空");
        if (payload.modules() == null || payload.modules().isEmpty()) throw new LicenseException("至少选择一个授权模块");
        if (payload.expireTime() == null) throw new LicenseException("授权到期日不能为空");
        if (payload.issuedTime() != null && payload.expireTime().isBefore(payload.issuedTime())) {
            throw new LicenseException("授权到期日不能早于签发日");
        }
        if (payload.maxUsers() != null && payload.maxUsers() <= 0) throw new LicenseException("最大用户数必须大于 0");
    }
}
