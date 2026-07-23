package com.chatchat.mcpserver.license;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.license")
public class LicenseProperties {
    private boolean failStartupOnInvalid;
    private String licenseFile = "./data/license/license.dat";
    private String serverIdFile = "./data/license/server.id";
    private String publicKey = "";
    private String publicKeyPath = "";

    public boolean isFailStartupOnInvalid() { return failStartupOnInvalid; }
    public void setFailStartupOnInvalid(boolean value) { this.failStartupOnInvalid = value; }
    public String getLicenseFile() { return licenseFile; }
    public void setLicenseFile(String value) { this.licenseFile = value; }
    public String getServerIdFile() { return serverIdFile; }
    public void setServerIdFile(String value) { this.serverIdFile = value; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String value) { this.publicKey = value; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String value) { this.publicKeyPath = value; }
}
