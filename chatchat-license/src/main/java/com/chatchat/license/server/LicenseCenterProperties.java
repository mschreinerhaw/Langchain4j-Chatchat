package com.chatchat.license.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.license-center")
public class LicenseCenterProperties {
    private String privateKeyPath = "./data/license-center/license-private.pem";
    private String publicKeyPath = "./data/license-center/license-public.pem";
    private boolean autoGenerateKeys = true;
    private String keyId = "default";
    private String username = "license-admin";
    private String password = "";

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String value) { this.privateKeyPath = value; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String value) { this.publicKeyPath = value; }
    public boolean isAutoGenerateKeys() { return autoGenerateKeys; }
    public void setAutoGenerateKeys(boolean value) { this.autoGenerateKeys = value; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { this.keyId = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { this.username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { this.password = value; }
}
