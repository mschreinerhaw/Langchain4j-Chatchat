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

    private String privateKey() {
        if (properties.getPrivateKeyPath() == null || properties.getPrivateKeyPath().isBlank()) {
            throw new LicenseException("未配置 License Center 签发私钥");
        }
        try {
            return Files.readString(Path.of(properties.getPrivateKeyPath()).toAbsolutePath().normalize());
        } catch (Exception ex) {
            throw new LicenseException("无法读取 License Center 签发私钥", ex);
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
