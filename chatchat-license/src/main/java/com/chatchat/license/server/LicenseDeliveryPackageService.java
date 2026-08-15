package com.chatchat.license.server;

import com.chatchat.license.LicenseCrypto;
import com.chatchat.license.LicenseDocument;
import com.chatchat.license.LicenseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class LicenseDeliveryPackageService {
    private final LicenseIssuanceService issuanceService;
    private final ObjectMapper objectMapper;

    public LicenseDeliveryPackageService(LicenseIssuanceService issuanceService, ObjectMapper objectMapper) {
        this.issuanceService = issuanceService;
        this.objectMapper = objectMapper;
    }

    public DeliveryPackage create(byte[] licenseDocument, String licenseNo) {
        try {
            String publicKey = issuanceService.publicKeyContent();
            LicenseDocument document = objectMapper.readValue(licenseDocument, LicenseDocument.class);
            if (!new LicenseCrypto(objectMapper).verify(document, publicKey)) {
                throw new LicenseException("签发公钥与当前 License 不匹配，已停止生成授权包");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                add(zip, "license.dat", licenseDocument);
                add(zip, "license-public.pem", publicKey.getBytes(StandardCharsets.UTF_8));
                add(zip, "README.txt", instructions().getBytes(StandardCharsets.UTF_8));
            }
            return new DeliveryPackage(fileName(licenseNo), "application/zip", output.toByteArray());
        } catch (LicenseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LicenseException("生成客户授权交付包失败", ex);
        }
    }

    private void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private String fileName(String licenseNo) {
        String safe = licenseNo == null ? "license" : licenseNo.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isBlank()) safe = "license";
        return "RuiHeng-Nexus-" + safe + "-license-package.zip";
    }

    private String instructions() {
        return """
            睿衡智联（RuiHeng Nexus）商业授权交付包
            =======================

            1. 将 license.dat 和 license-public.pem 一起复制到 MCP Server 的 ./data/license/ 目录。
            2. 配置以下环境变量，建议使用绝对路径：
               CHATCHAT_LICENSE_FILE=/opt/livemcp/data/license/license.dat
               CHATCHAT_LICENSE_PUBLIC_KEY_PATH=/opt/livemcp/data/license/license-public.pem
            3. 替换有效 license.dat 后工具调用可自动恢复；首次配置公钥路径后请重启 MCP Server。
            """;
    }

    public record DeliveryPackage(String fileName, String contentType, byte[] content) { }
}
